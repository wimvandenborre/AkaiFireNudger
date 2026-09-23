package com.akai.fire.sequence;

import java.util.*;
import java.util.function.Consumer;
import static com.akai.fire.sequence.GrooveEngine.*;

/** Reversible edit protocol. A port must prove capabilities before this can issue moves. */
final class GrooveSession {
    enum State { CLOSED, CAPTURING, STAGED, WRITING, PREVIEW, CONFLICT, APPLIED }
    record Read(Object binding, String name, double loopStart, double loopLength, List<Note> notes,
                GrooveSafety.Capabilities capabilities, boolean playing, boolean recording, String warning) {
        Read { notes=List.copyOf(notes); Objects.requireNonNull(binding); }
    }
    interface Port {
        void capture(Consumer<Read> complete, Consumer<String> failure);
        void read(Consumer<Read> complete, Consumer<String> failure);
        void move(GrooveSafety.Move move);
        void schedule(Runnable work, int millis);
        void release();
        default void adopt(List<Note> notes) {}
    }
    private final Port port;
    private final Runnable changed;
    private Read original;
    private List<Note> confirmed = List.of();
    private GrooveSettings settings = GrooveSettings.defaults();
    private Plan plan;
    private State state = State.CLOSED;
    private String message="Start: capture selected clip";
    private long generation, revision;
    private boolean preview, closeAfter, restore;
    private String precisionMessage="";
    private int lastMoveCount, lastVelocityCount;
    int lastVelocityCount(){return lastVelocityCount;}
    private GrooveSafety.Move inFlight;
    private boolean reconciledChange, preparedMoves;
    boolean preparedMoves(){return preparedMoves;}
    boolean reconciledChange(){return reconciledChange;}
    int lastMoveCount(){return lastMoveCount;}
    GrooveSession(Port port, Runnable changed) { this.port=port; this.changed=changed; }
    State state(){return state;} String message(){return message;}
    Read original(){return original;} Plan plan(){return plan;} GrooveSettings settings(){return settings;}
    boolean open(){return state!=State.CLOSED && state!=State.APPLIED;}
    private void show(String text){message=text;changed.run();}
    void begin() {
        if(open()){show("Resolve current session first");return;}
        long token=++generation; revision++; preview=false; state=State.CAPTURING;
        show("Reading selected clip cells...");
        port.capture(read->{if(token!=generation)return;original=read;confirmed=read.notes;state=State.STAGED;stage(settings);},
                error->{if(token==generation){state=State.CLOSED;port.release();show(error);}});
    }
    void stage(GrooveSettings next) {
        settings=next; revision++;
        if(original==null || !open() || state==State.CAPTURING){show("Staged settings; Start to capture");return;}
        plan=GrooveEngine.plan(original.notes,original.loopStart,original.loopLength,settings);
        if(state==State.CONFLICT){show("Conflict: keep host edits; capture anew");return;}
        String limitation=original.capabilities.blocked();
        show(limitation.isEmpty()?"Staged from originals": "Inspection only: "+limitation);
        if(preview && state!=State.WRITING) queue(false,false);
    }
    /** Batch coordinator owns scheduling; stage without starting independent preview jobs. */
    void prepare(GrooveSettings next, Consumer<String> complete) {
        preview=false;stage(next);
        if(!open() || original==null || state==State.CONFLICT){complete.accept("Clip session unavailable");return;}
        long token=generation;
        port.read(read->{
            if(token!=generation)return;
            if(!matches(read,confirmed)){invalidate("target/content changed");complete.accept(message);return;}
            var result=GrooveSafety.validate(original.notes,confirmed,plan,original.loopStart,original.loopLength,
                    settings,read.capabilities,read.playing,read.recording,false);
            preparedMoves=result.allowed()&&!result.moves().isEmpty();
            complete.accept(result.blocked());
        },error->{if(token==generation){invalidate(error);complete.accept(error);}});
    }
    /** Stop queued groove work before a known pad/Euclidean edit, retaining originals. */
    void pauseForNotes() {
        generation++;revision++;preview=false;
        if(original!=null)state=State.STAGED;
    }
    void reconcile(Consumer<String> complete) {
        if(original==null){complete.accept("Missing original snapshot");return;}
        pauseForNotes();long token=generation;
        port.read(first->{if(token!=generation)return;
            // No write or baseline adoption is needed when an entire snapshot is unchanged.
            // Changed content still requires the two stable reads below.
            if(inFlight==null && !first.recording && matches(first,confirmed)) {
                reconciledChange=false;complete.accept("");return;
            }
            port.schedule(()->{if(token!=generation)return;port.read(second->{
                if(token!=generation)return;
                if(first.binding!=second.binding || first.loopStart!=second.loopStart || first.loopLength!=second.loopLength
                        ||!GrooveSafety.index(first.notes).equals(GrooveSafety.index(second.notes))) {
                    complete.accept("Notes still changing");return;
                }
                if(second.binding!=original.binding || second.loopStart!=original.loopStart || second.loopLength!=original.loopLength
                        ||second.recording){complete.accept("Clip context changed or recording");return;}
                var old=GrooveSafety.index(original.notes);var previous=GrooveSafety.index(confirmed);
                List<Note> baseline=new ArrayList<>();
                for(Note note:second.notes) {
                    Note before=old.get(note.id()),last=previous.get(note.id());
                    if(before==null){baseline.add(note);continue;}
                    boolean expectedMove=inFlight!=null&&note.equals(inFlight.to());
                    if(last==null || note.pitch()!=last.pitch() || note.channel()!=last.channel()
                            ||(note.start()!=last.start()&&!expectedMove)) {
                        complete.accept("Unrecognized note movement");return;
                    }
                    // Own previews must never become velocity baselines. Adopt genuine external
                    // velocity edits, while keeping originals for unchanged surviving notes.
                    Note adopted = note.at(before.start());
                    if (GrooveVelocity.hasVelocity(note) && GrooveVelocity.hasVelocity(before)
                            && (GrooveVelocity.value(note) == GrooveVelocity.value(last) || expectedMove))
                        adopted = GrooveVelocity.with(adopted, GrooveVelocity.value(before));
                    baseline.add(adopted);
                }
                reconciledChange=!GrooveSafety.index(confirmed).equals(GrooveSafety.index(second.notes));
                if(reconciledChange||inFlight!=null)port.adopt(second.notes);
                original=new Read(original.binding,second.name,original.loopStart,original.loopLength,baseline,
                        second.capabilities,second.playing,second.recording,second.warning);
                confirmed=second.notes;inFlight=null;state=State.STAGED;
                plan=GrooveEngine.plan(original.notes,original.loopStart,original.loopLength,settings);
                complete.accept("");
            },complete);},125);
        },complete);
    }
    void markBatchPending(){ if(state==State.PREVIEW)state=State.STAGED; }
    void preview(){if(!usable())return;preview=true;queue(false,false);}
    void apply(){if(!usable())return;preview=false;queue(false,true);}
    void bypass(){if(!usable())return;preview=false;queue(true,false);}
    void reset(){if(!usable())return;preview=false;var b=settings.edit();b.depth=0;b.quantize=0;b.bias=0;stage(b.build());queue(false,false);}
    void cancel(){
        if(state==State.CAPTURING){generation++;state=State.CLOSED;port.release();show("Capture cancelled; no notes changed");return;}
        if(!usable())return;preview=false;queue(true,true);
    }
    /** Explicit conflict resolution, never described as restoration. */
    void keepHostEdits(){generation++;revision++;preview=false;state=State.CLOSED;original=null;plan=null;confirmed=List.of();port.release();show("Kept host content; originals discarded");}
    void invalidate(String reason){if(open()){generation++;revision++;preview=false;state=State.CONFLICT;show("Conflict: "+reason);}}
    private boolean usable(){
        if(state==State.CONFLICT){show("Conflict: restoration blocked; keep host edits");return false;}
        if(state==State.WRITING || state==State.CAPTURING){show("Wait for pending read/write");return false;}
        if(!open() || original==null){show("Start a selected-clip session first");return false;}return true;
    }
    private void queue(boolean restoring, boolean close) {
        // A staged-only Cancel/Bypass is a true no-write operation, even on a read-only port.
        if(restoring && confirmed.equals(original.notes)) {
            if(close)finish(true);else{state=State.STAGED;show("Originals unchanged; settings retained");}return;
        }
        if(!original.capabilities.blocked().isEmpty()) {
            preview=false;show("Blocked: "+original.capabilities.blocked());return;
        }
        long token=generation, edit=++revision;
        port.schedule(()->{if(token!=generation||edit!=revision||!open())return;
            port.read(read->{if(token!=generation||edit!=revision)return;
                if(!matches(read,confirmed)){invalidate("target/content changed");return;}
                Plan desired=restoring?GrooveEngine.plan(original.notes,original.loopStart,original.loopLength,bypassSettings()):plan;
                GrooveSafety.Result result=GrooveSafety.validate(original.notes,confirmed,desired,original.loopStart,original.loopLength,
                        settings,read.capabilities,read.playing,read.recording,restoring);
                if(!result.allowed()){preview=false;show("Blocked: "+result.blocked());return;}
                lastMoveCount=(int)result.moves().stream().map(m->m.from().id()).distinct().count();
                lastVelocityCount=(int)result.moves().stream().filter(m->!m.from().properties().equals(m.to().properties())).count();
                closeAfter=close;restore=restoring;
                precisionMessage=read.capabilities.drumCellMode()?"; 1/64 beat (rounded; tiny offsets may stay unchanged)":"";
                if(result.moves().isEmpty()){done(edit);return;}
                state=State.WRITING;show("Writing; awaiting each move");
                writeNext(token,edit,result.moves(),0);
            },error->{if(token==generation)invalidate(error);});
        },75);
    }
    private GrooveSettings bypassSettings(){var b=settings.edit();b.bypass=true;return b.build();}
    private boolean matches(Read read,List<Note> expected){
        return read.binding==original.binding && read.loopStart==original.loopStart && read.loopLength==original.loopLength
                && GrooveSafety.index(read.notes).equals(GrooveSafety.index(expected));
    }
    private void writeNext(long token,long edit,List<GrooveSafety.Move> moves,int index){
        if(token!=generation)return;
        if(index==moves.size()){done(edit);return;}
        GrooveSafety.Move move=moves.get(index);
        List<Note> before=confirmed;
        List<Note> after=before.stream().map(n->n.id().equals(move.from().id())?move.to():n).toList();
        // Re-read before EVERY move; this also catches transport/record changes mid-batch.
        port.read(read->{if(token!=generation)return;
            if(!read.capabilities.blocked().isEmpty()||!matches(read,before)||read.recording||(read.playing&&!read.capabilities.playingPreview())){invalidate("content/transport changed; partial batch");return;}
            try {inFlight=move;port.move(move);} catch(RuntimeException ex){invalidate("write failed; completion uncertain");return;}
            acknowledge(token,edit,moves,index,before,after,0);
        },error->{if(token==generation)invalidate(error);});
    }
    private void acknowledge(long token,long edit,List<GrooveSafety.Move> moves,int index,List<Note> before,List<Note> after,int attempt){
        port.schedule(()->{if(token!=generation)return;port.read(read->{if(token!=generation)return;
            if(matches(read,after)){confirmed=after;inFlight=null;writeNext(token,edit,moves,index+1);}
            else if((matches(read,before)||movingFrame(read,before,moves.get(index)))&&attempt<20)acknowledge(token,edit,moves,index,before,after,attempt+1);
            else invalidate("unexpected readback or timeout; partial/uncertain write");
        },error->{if(token==generation)invalidate(error);});},30);
    }
    private boolean movingFrame(Read read,List<Note> before,GrooveSafety.Move move) {
        if(read.binding!=original.binding || read.loopStart!=original.loopStart || read.loopLength!=original.loopLength)return false;
        Map<String,Note> expected=GrooveSafety.index(before),actual=GrooveSafety.index(read.notes);
        expected.remove(move.from().id());
        Note moving=actual.remove(move.from().id());
        return expected.equals(actual) && (moving==null || moving.equals(move.from()) || moving.equals(move.to()));
    }
    private void done(long edit){
        if(closeAfter){finish(restore);return;}
        state=preview?State.PREVIEW:State.STAGED;
        show(restore?"Originals restored":"Preview confirmed"+precisionMessage);
        if(preview && edit!=revision)queue(false,false);
    }
    private void finish(boolean cancelled){
        generation++;revision++;state=cancelled?State.CLOSED:State.APPLIED;preview=false;
        port.release();original=null;plan=null;confirmed=List.of();
        show(cancelled?"Cancelled; originals retained":"Applied"+precisionMessage+"; recapture for new baseline");
    }
}
