package com.akai.fire.sequence;

import com.akai.fire.display.OledDisplay;
import com.bitwig.extension.controller.api.*;
import java.util.*;
import java.util.function.Consumer;

/** Two controls: groove and amount, shared by the children in the Fire's captured scene. */
final class GrooveControl {
    private final ControllerHost host;
    private final DrumSequenceMode parent;
    private final OledDisplay oled;
    private final GrooveBatch batch;
    private final GrooveClipWorker worker;
    private final GrooveHostPort[] ports;
    private final BooleanValue workerIsVisible;
    private final Consumer<String> log;
    private static final GrooveShapes.Shape[] PRESETS = {
            GrooveShapes.Shape.LOGIC_16A, GrooveShapes.Shape.LOGIC_16B, GrooveShapes.Shape.LOGIC_16C,
            GrooveShapes.Shape.LOGIC_16D, GrooveShapes.Shape.LOGIC_16E};
    private GrooveShapes.Shape shape=GrooveShapes.Shape.LOGIC_16C;
    private GrooveVelocity velocity=GrooveVelocity.defaults();
    private GrooveVelocityPreferences velocityPreferences;
    private int amount;
    private boolean amountField,shown;
    private boolean locked=true;
    private long noteRevision;
    private String message="All child clips - 0% = original timing";
    private long request;
    private String lastAlert="";

    GrooveControl(ControllerHost host,CursorTrack track,PinnableCursorClip clip,DrumSequenceMode parent,Consumer<String> log) {
        this.host=host;this.parent=parent;this.log=log;oled=parent.getOled();
        batch=new GrooveBatch(host::scheduleTask,this::context,this::status);
        if(parent.getMulticlip()!=null) {
            worker=new GrooveClipWorker(host,log);
            workerIsVisible=worker.clip.createEqualsValue(clip);workerIsVisible.markInterested();
            ports=new GrooveHostPort[MulticlipTarget.LANES];
            for(int i=0;i<ports.length;i++)ports[i]=new GrooveHostPort(host,worker.track,worker.clip,
                    ()->true,parent::getGridResolution,worker.nudge,"FIRE_GROUP_GROOVE_"+i);
        } else {
            worker=null;workerIsVisible=null;
            ports=new GrooveHostPort[]{new GrooveHostPort(host,track,clip,parent::clipReady,parent::getGridResolution,parent.getFineNudge())};
        }
    }
    private void status(String text) {
        message=text;log.accept("GROOVE_GROUP "+text);
        boolean progress=text.startsWith("Reading child")||text.startsWith("Updating child")
                ||text.matches("[0-9]+ clips:.*")||text.endsWith("Notes still changing");
        if(!progress&&!text.equals(lastAlert)){lastAlert=text;host.showPopupNotification(text);}
        if(progress)lastAlert="";
        show();
    }
    void initLock() {
        host.getPreferences().getEnumSetting("Groove lock", "Sequencer",new String[]{"On","Off"},"On")
                .addValueObserver(value->{locked="On".equals(value);if(locked)notesEdited();});
        initVelocity();
        pollLock();
    }
    private void initVelocity() {
        velocityPreferences=new GrooveVelocityPreferences(host.getPreferences(),(next,userEdit)->{
            boolean different=!velocity.equals(next);velocity=next;
            log.accept("GROOVE_VELOCITY enabled="+next.enabled()+" amount="+next.amount()
                    +" min="+next.min()+" max="+next.max()+" phase="+next.phase()+" userEdit="+userEdit);
            if (!userEdit || !different) return;
            invalidate();
            long ticket=++request;
            host.scheduleTask(()->{if(ticket==request)apply();},150);
        });
    }
    private void pollLock() {
        host.scheduleTask(()->{
            if(locked&&batch.captured()&&!batch.failed()&&!batch.busy()) {
                invalidate();if(batch.captured()){batch.prioritize(selectedChild());batch.reconcile();}
            }
            pollLock();
        },500);
    }
    void notesEdited() {
        if(!locked||!batch.captured()||batch.failed())return;
        batch.notesEdited(selectedChild());long ticket=++noteRevision;
        host.scheduleTask(()->{
            if(locked&&ticket==noteRevision&&batch.captured())batch.reconcile();
        },150);
    }
    private int selectedChild() {
        MulticlipTarget target=parent.getMulticlip();
        if(target==null)return 0;
        var children=target.grooveChildren();
        int lane=target.midiNote()-MulticlipTarget.FIRST_NOTE;
        for(int i=0;i<children.size();i++)if(children.get(i).lane()==lane)return i;
        return -1;
    }
    private String context() {
        MulticlipTarget target=parent.getMulticlip();
        if(target==null)return parent.getEditTrack().position().get()+":"+parent.getCursorClip().clipLauncherSlot().sceneIndex().get()+":"+parent.getGridResolution();
        String key=target.grooveContext();
        if(key.isEmpty())return "";
        return key+":"+parent.getGridResolution()+":"+target.grooveChildren().stream()
                .map(c->c.lane()+"/"+c.position()+"/"+c.scene()).toList();
    }
    void visible(boolean visible) {
        if(visible&&!shown&&batch.failed())reset("New baseline - turn Amount to apply");
        shown=visible;show();
    }
    void invalidate() {
        if(batch.captured()&&!batch.context().equals(context()))reset("Scene changed - Amount reset to 0%");
    }
    void deactivate(){reset("All child clips - 0% = original timing");}
    private void reset(String text) {
        request++;batch.discard();if(worker!=null)worker.cancel();velocityPreferences.deactivate();amount=0;message=text;show();
    }
    void turn(int delta) {
        invalidate();
        if(amountField)amount=Math.max(0,Math.min(100,amount+delta));
        else shape=PRESETS[Math.floorMod(Arrays.asList(PRESETS).indexOf(shape)+delta,PRESETS.length)];
        show();
        long ticket=++request;
        host.scheduleTask(()->{if(ticket==request)apply();},150);
    }
    void press(){amountField=!amountField;show();}
    private GrooveSettings settings() {
        return GrooveSettings.forController(shape,amount,velocity);
    }
    private void apply() {
        velocity=velocityPreferences.activate();
        log.accept("GROOVE_REQUEST shape="+shape.id()+" amount="+amount
                +" velocityEnabled="+velocity.enabled()+" velocityAmount="+velocity.amount()
                +" effectiveVelocityAmount="+settings().velocity().amount());
        if(batch.failed()){message="Clips changed - reopen groove to continue";show();return;}
        if(batch.captured()){batch.prioritize(selectedChild());batch.update(settings());return;}
        if(amount==0)return;
        String key=context();
        if(key.isEmpty()){message="Select the drum group first";show();return;}
        List<GrooveSession.Port> selected=new ArrayList<>();
        if(worker==null)selected.add(ports[0]);
        else for(var child:parent.getMulticlip().grooveChildren())selected.add(childPort(child,key));
        if(selected.isEmpty()){message="No child MIDI clips in this scene";show();return;}
        batch.start(selected,key,settings());batch.prioritize(selectedChild());
    }
    private GrooveSession.Port childPort(MulticlipTarget.GrooveChild child,String key) {
        GrooveHostPort port=ports[child.lane()];
        return new GrooveSession.Port() {
            private void prepare(Runnable ready,Consumer<String> failure) {
                if(!key.equals(context())){failure.accept("Captured scene changed");return;}
                worker.prepare(child,()->{
                    if(key.equals(context())&&worker.matches(child))ready.run();
                    else failure.accept("Child target changed");
                },failure);
            }
            public void capture(Consumer<GrooveSession.Read> done,Consumer<String> failed) {prepare(()->port.capture(done,failed),failed);}
            public void read(Consumer<GrooveSession.Read> done,Consumer<String> failed) {prepare(()->port.read(done,failed),failed);}
            public void move(GrooveSafety.Move move) {
                if(!key.equals(context())||!worker.matches(child))throw new IllegalStateException("Child target changed");
                if(move.from().start()!=move.to().start() && workerIsVisible.get() && parent.getFineNudge().pitch()==move.from().pitch()) {
                    double start=worker.clip.getLoopStart().get();
                    int from=(int)Math.round((move.from().start()-start)/FineNudge.STEP_BEATS);
                    int to=(int)Math.round((move.to().start()-start)/FineNudge.STEP_BEATS);
                    parent.getFineNudge().mirrorMove(move.from().channel(),from,to,parent.getGridResolution(),()->port.move(move));
                } else port.move(move);
            }
            public void schedule(Runnable work,int millis){host.scheduleTask(work,millis);}
            public void adopt(List<GrooveEngine.Note> notes){port.adopt(notes);}
            public void release(){port.release();}
        };
    }
    private void show() {
        if(shown)oled.paramInfo((amountField?"  ":"> ")+shape.label,
                (amountField?"> ":"  ")+amount+"%","");
    }
}
