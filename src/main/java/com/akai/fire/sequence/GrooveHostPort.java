package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.*;
import java.util.*;
import java.util.function.*;

/** API 25 adapter: explicitly constrained single-instrument drum cells via FineNudge. */
final class GrooveHostPort implements GrooveSession.Port {
    static final int WIDTH=256, HEIGHT=8, SCOPE_WIDTH=32;
    static final String WRITE_BLOCK="API 25: exact starts/full note data unavailable";
    private final ControllerHost host;
    private final CursorTrack track;
    private final PinnableCursorClip view, source;
    private final CursorTrack sourceTrack;
    private final BooleanValue sameClip;
    private final Transport transport;
    private final BooleanSupplier sourceReady;
    private final DoubleSupplier grid;
    private FineNudge nudge;
    private PinnableCursorClip census;
    private BooleanValue sameFine, sameCensus;
    private int capturedPitch;
    private boolean scopeDirty=true, scopeValid;
    private double capturedGrid;
    private final Map<String,Integer> addresses = new LinkedHashMap<>();
    private final Map<String,Integer> channels = new HashMap<>();
    private final Map<String,String> unseenIds = new HashMap<>();
    private long nextNoteId;
    private GrooveSafety.Move pending;
    private Object binding;
    private String name;
    private double loopStart, loopLength;
    private int serial;
    private boolean capturing;
    private Runnable contentChanged = () -> {};
    void onContentChanged(Runnable callback) { contentChanged = callback; }
    private Consumer<String> failure;
    private final List<GrooveEngine.Note> observed=new ArrayList<>();

    GrooveHostPort(ControllerHost host, CursorTrack sourceTrack, PinnableCursorClip source,
                   BooleanSupplier sourceReady, DoubleSupplier grid) {
        this(host,sourceTrack,source,sourceReady,grid,"FIRE_GROOVE");
    }
    private GrooveHostPort(ControllerHost host, CursorTrack sourceTrack, PinnableCursorClip source,
                   BooleanSupplier sourceReady, DoubleSupplier grid, String id) {
        this.host=host;this.sourceTrack=sourceTrack;this.source=source;this.sourceReady=sourceReady;this.grid=grid;
        transport=host.createTransport();transport.isPlaying().markInterested();transport.tempo().markInterested();
        track=host.createCursorTrack(id+"_TARGET","Groove inspection",0,1,false);
        track.isPinned().markInterested();track.position().markInterested();
        view=track.createLauncherCursorClip(id+"_INSPECT","Groove inspection",WIDTH,HEIGHT);
        view.setStepSize(FineNudge.STEP_BEATS);
        view.exists().markInterested();view.isPinned().markInterested();
        view.getLoopStart().markInterested();view.getLoopLength().markInterested();view.isLoopEnabled().markInterested();
        view.clipLauncherSlot().name().markInterested();view.clipLauncherSlot().isRecording().markInterested();
        view.getShuffle().markInterested();
        sameClip=view.createEqualsValue(source);sameClip.markInterested();
        // Establish grid observations. There is deliberately no 'complete = true' inference.
        view.addNoteStepObserver(note -> { if(nudge == null && binding != null && !capturing) contentChanged.run(); });
    }
    GrooveHostPort(ControllerHost host, CursorTrack sourceTrack, PinnableCursorClip source,
                   BooleanSupplier sourceReady, DoubleSupplier grid, FineNudge nudge) {
        this(host,sourceTrack,source,sourceReady,grid,nudge,"FIRE_GROOVE");
    }
    GrooveHostPort(ControllerHost host, CursorTrack sourceTrack, PinnableCursorClip source,
                   BooleanSupplier sourceReady, DoubleSupplier grid, FineNudge nudge, String id) {
        this(host,sourceTrack,source,sourceReady,grid,id);
        this.nudge=nudge;
        census=track.createLauncherCursorClip(id+"_SCOPE","Groove drum scope",SCOPE_WIDTH,128);
        census.exists().markInterested();census.isPinned().markInterested();
        census.addNoteStepObserver(note -> { if(note.y()!=capturedPitch)scopeDirty=true; });
        sameCensus=census.createEqualsValue(source);sameCensus.markInterested();
        sameFine=nudge.clip().createEqualsValue(source);sameFine.markInterested();
    }
    double bpm(){return transport.tempo().getRaw();}
    public void capture(Consumer<GrooveSession.Read> complete, Consumer<String> failed) {
        release();failure=failed;
        if(!sourceReady.getAsBoolean()||!source.exists().get()){failed.accept("Select a ready MIDI clip first");return;}
        if(nudge==null && transport.isPlaying().get()){failed.accept("Stop transport for stable inspection");return;}
        binding=new Object();capturing=true;
        track.isPinned().set(true);track.selectChannel(sourceTrack);
        view.isPinned().set(true);view.selectClip(source);
        int token=serial;
        awaitTarget(token,0,complete);
    }
    private void awaitTarget(int token,int attempt,Consumer<GrooveSession.Read> complete) {
        host.scheduleTask(()->{
            if(token!=serial)return;
            if(!sameClip.get()||!view.exists().get()) {
                if(attempt<20)awaitTarget(token,attempt+1,complete);else fail("Cannot pin selected clip identity");return;
            }
            loopStart=view.getLoopStart().get();loopLength=view.getLoopLength().get();
            if(!view.isLoopEnabled().get()||loopStart<0||loopLength<=0||loopLength>FineNudge.WINDOW*FineNudge.STEP_BEATS
                    ||!aligned(loopStart/FineNudge.STEP_BEATS)||!aligned(loopLength/FineNudge.STEP_BEATS)){
                fail("Inspection needs aligned loop, max 64 beats");return;
            }
            name=view.clipLauncherSlot().name().get();
            observed.clear();
            if(nudge==null)scan(token,0,0,complete);
            else {
                scopeDirty=true;scopeValid=false;capturedPitch=nudge.pitch();capturedGrid=grid.getAsDouble();
                if(capturedPitch<0 || !nudge.mapsGrid(capturedGrid) || !nudge.editsReady(capturedGrid)) {
                    fail("Select a ready drum lane first");return;
                }
                double size=FineNudge.STEP_BEATS*Math.ceil(loopLength/FineNudge.STEP_BEATS/SCOPE_WIDTH);
                if(!aligned(loopStart/size)){fail("Loop start is not aligned to groove scope");return;}
                census.isPinned().set(true);census.selectClip(source);
                census.setStepSize(size);census.scrollToStep((int)Math.round(loopStart/size));census.scrollToKey(0);
                host.scheduleTask(()->captureDrum(token,complete,null,0),150);
            }
        },100);
    }
    private static boolean aligned(double value){return Math.abs(value-Math.rint(value))<1e-8;}
    private boolean valid(){return sameClip.get()&&view.exists().get()&&view.isPinned().get()
            &&view.getLoopStart().get()==loopStart&&view.getLoopLength().get()==loopLength
            &&!view.clipLauncherSlot().isRecording().get()&&(nudge!=null||!transport.isPlaying().get());}
    private void scan(int token,int page,int pitch,Consumer<GrooveSession.Read> complete){
        if(token!=serial)return;
        if(!valid()){fail("Clip/focus/transport changed during inspection");return;}
        view.scrollToStep((int)Math.round(loopStart/FineNudge.STEP_BEATS)+page);
        view.scrollToKey(pitch);
        host.scheduleTask(()->sample(token,page,pitch,null,0,complete),100);
    }
    private void sample(int token,int page,int pitch,List<GrooveEngine.Note> previous,int tries,Consumer<GrooveSession.Read> complete){
        if(token!=serial)return;
        if(!valid()){fail("Clip changed during inspection");return;}
        List<GrooveEngine.Note> cells=new ArrayList<>();
        int length=(int)Math.round(loopLength/FineNudge.STEP_BEATS);
        for(int channel=0;channel<16;channel++)for(int y=0;y<HEIGHT;y++)for(int x=0;x<Math.min(WIDTH,length-page);x++){
            NoteStep note=view.getStep(channel,x,y);
            if(note.state()!=NoteStep.State.NoteOn)continue;
            NoteSnapshot scalar=NoteSnapshot.capture(note);
            if(!Double.isFinite(scalar.duration())||scalar.duration()<=0){fail("Invalid note duration");return;}
            String key=channel+":"+(pitch+y)+":"+(page+x);
            cells.add(new GrooveEngine.Note(key,loopStart+(page+x)*FineNudge.STEP_BEATS,scalar.duration(),pitch+y,channel,
                    Map.of("scalarSnapshot",scalar),false));
        }
        if(previous==null||!previous.equals(cells)){
            if(tries>=10){fail("Unstable observation window");return;}
            host.scheduleTask(()->sample(token,page,pitch,cells,tries+1,complete),100);return;
        }
        observed.addAll(cells);
        if(pitch+HEIGHT<128)scan(token,page,pitch+HEIGHT,complete);
        else if(page+WIDTH<length)scan(token,page+WIDTH,0,complete);
        else{
            capturing=false;
            complete.accept(new GrooveSession.Read(binding,name,loopStart,loopLength,observed,
                    new GrooveSafety.Capabilities(FineNudge.STEP_BEATS,grid.getAsDouble(),false,false,true,false,false,false,false),
                    transport.isPlaying().get(),view.clipLauncherSlot().isRecording().get(),
                    "Estimated onset cells; "+WRITE_BLOCK+(view.getShuffle().get()?"; native clip shuffle enabled":"")));
        }
    }
    private void fail(String text){capturing=false;Consumer<String> callback=failure;release();if(callback!=null)callback.accept(text);}
    public void read(Consumer<GrooveSession.Read> complete,Consumer<String> failed){
        if(nudge==null){failed.accept(WRITE_BLOCK);return;}
        try { complete.accept(drumRead()); } catch(IllegalStateException ex) { failed.accept(ex.getMessage()); }
    }
    public void move(GrooveSafety.Move move){
        if(nudge==null)throw new UnsupportedOperationException(WRITE_BLOCK);
        drumRead();
        int from=cell(move.from().start()),to=cell(move.to().start());
        if(!Objects.equals(addresses.get(move.from().id()),from))throw new IllegalStateException("Note address changed");
        if (from == to) {
            var step=nudge.clip().getStep(move.from().channel(),from,0);
            if (step.state()!=NoteStep.State.NoteOn
                    || !NoteSnapshot.properties(step).equals(move.from().properties().get("scalarSnapshot"))
                    || !GrooveVelocity.with(move.from(),GrooveVelocity.value(move.to())).equals(move.to()))
                throw new IllegalStateException("Velocity target/properties changed");
            step.setVelocity(GrooveVelocity.value(move.to()));
        } else {
            if (!move.from().at(move.to().start()).equals(move.to()))
                throw new IllegalStateException("Timing move changes properties");
            nudge.moveAbsolute(move.from().channel(),from,to,capturedGrid);
            pending=move;
        }
    }
    private int cell(double beat){return (int)Math.round((beat-loopStart)/FineNudge.STEP_BEATS);}
    private void captureDrum(int token,Consumer<GrooveSession.Read> complete,List<GrooveEngine.Note> previous,int tries) {
        if(token!=serial)return;
        try {
            GrooveSession.Read read=drumRead();
            if(previous==null || !previous.equals(read.notes())) {
                if(tries>=20){fail("Drum cells are not stable");return;}
                host.scheduleTask(()->captureDrum(token,complete,read.notes(),tries+1),100);return;
            }
            adopt(read.notes());
            capturing=false;complete.accept(read);
        } catch(IllegalStateException ex) {
            if(tries<20)host.scheduleTask(()->captureDrum(token,complete,null,tries+1),100);
            else fail(ex.getMessage());
        }
    }
    private GrooveSession.Read drumRead() {
        if(binding==null || !valid() || !sameFine.get() || !sameCensus.get() || !census.exists().get()
                || nudge.pitch()!=capturedPitch || grid.getAsDouble()!=capturedGrid || !nudge.mapsGrid(capturedGrid))
            throw new IllegalStateException("Drum target, grid or transport changed");
        // Coarse all-pitch census rejects mixed-instrument clips; fine cursor supplies drum timings.
        if(scopeDirty) {
            scopeValid=true;
            outer: for(int ch=0;ch<16;ch++)for(int y=0;y<128;y++)if(y!=capturedPitch)
                for(int x=0;x<SCOPE_WIDTH;x++)if(census.getStep(ch,x,y).state()==NoteStep.State.NoteOn) {
                    scopeValid=false;break outer;
                }
            scopeDirty=false;
        }
        if(!scopeValid)throw new IllegalStateException("Groove requires a separate clip per drum pitch");
        if(pending!=null) {
            int ch=pending.from().channel(),from=cell(pending.from().start()),to=cell(pending.to().start());
            if(nudge.clip().getStep(ch,from,0).state()!=NoteStep.State.NoteOn
                    && nudge.clip().getStep(ch,to,0).state()==NoteStep.State.NoteOn) {
                addresses.put(pending.from().id(),to);pending=null;
            }
        }
        Map<String,String> ids=new HashMap<>();
        for(var entry:addresses.entrySet()) {
            String id=entry.getKey();ids.put(channels.get(id)+":"+entry.getValue(),id);
        }
        List<GrooveEngine.Note> notes=new ArrayList<>();
        int length=(int)Math.round(loopLength/FineNudge.STEP_BEATS);
        for(int ch=0;ch<16;ch++)for(int x=0;x<length;x++) {
            NoteStep step=nudge.clip().getStep(ch,x,0);
            if(step.state()!=NoteStep.State.NoteOn)continue;
            var scalar=NoteSnapshot.properties(step);
            if(!Double.isFinite(scalar.duration())||scalar.duration()<=0)throw new IllegalStateException("Invalid note duration");
            String address=ch+":"+x;
            String id=ids.get(address);
            if(id==null)id=unseenIds.computeIfAbsent(address,key->"new:"+(++nextNoteId));
            notes.add(new GrooveEngine.Note(id,loopStart+x*FineNudge.STEP_BEATS,scalar.duration(),capturedPitch,ch,
                    Map.of("scalarSnapshot",scalar),false));
        }
        return new GrooveSession.Read(binding,name,loopStart,loopLength,notes,
                new GrooveSafety.Capabilities(FineNudge.STEP_BEATS,capturedGrid,false,false,true,true,false,true,true,true),
                transport.isPlaying().get(),view.clipLauncherSlot().isRecording().get(),
                "Drum cell mode: one onset per pitch/channel/1/64 cell; residual timing retained"
                        +(view.getShuffle().get()?"; native clip shuffle enabled":""));
    }
    public void adopt(List<GrooveEngine.Note> notes) {
        addresses.clear();channels.clear();unseenIds.clear();pending=null;
        for(var note:notes){addresses.put(note.id(),cell(note.start()));channels.put(note.id(),note.channel());}
        if(nudge!=null)nudge.acceptExternalSnapshot();
    }
    public void schedule(Runnable work,int millis){host.scheduleTask(work,millis);}
    public void release(){serial++;capturing=false;binding=null;pending=null;addresses.clear();channels.clear();unseenIds.clear();if(census!=null)census.isPinned().set(false);track.isPinned().set(false);view.isPinned().set(false);}
}
