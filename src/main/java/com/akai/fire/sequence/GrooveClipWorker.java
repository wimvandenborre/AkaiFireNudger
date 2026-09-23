package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.*;
import java.util.function.*;

/** Private edit cursor: navigate children without changing the Fire/editor selection. */
final class GrooveClipWorker {
    final CursorTrack track;
    final PinnableCursorClip clip;
    final FineNudge nudge;
    private final ControllerHost host;
    private final Consumer<String> log;
    private final ClipSceneSeek seek=new ClipSceneSeek();
    private MulticlipTarget.GrooveChild current;
    private long generation;

    GrooveClipWorker(ControllerHost host, Consumer<String> log) {
        this.host=host;this.log=log;
        track=host.createCursorTrack("FIRE_GROOVE_WORKER","Fire group groove",0,16,false);
        track.position().markInterested();track.isPinned().markInterested();
        clip=track.createLauncherCursorClip("FIRE_GROOVE_WORKER_CLIP","Groove child",32,1);
        clip.exists().markInterested();clip.isPinned().markInterested();clip.getTrack().position().markInterested();
        clip.clipLauncherSlot().sceneIndex().markInterested();
        nudge=new FineNudge(host,track,clip,log,"FIRE_GROOVE_WORKER_FINE");
        nudge.clip().isPinned().markInterested();
    }
    boolean matches(MulticlipTarget.GrooveChild target) {
        return target.track().exists().get() && target.track().position().get()==target.position()
                && track.position().get()==target.position() && clip.exists().get()
                && clip.getTrack().position().get()==target.position()
                && clip.clipLauncherSlot().sceneIndex().get()==target.scene()
                && nudge.clip().exists().get() && nudge.clip().getTrack().position().get()==target.position()
                && nudge.clip().clipLauncherSlot().sceneIndex().get()==target.scene()
                && nudge.pitch()==MulticlipTarget.FIRST_NOTE+target.lane();
    }
    void prepare(MulticlipTarget.GrooveChild target,Runnable ready,Consumer<String> failed) {
        if(target.equals(current)&&matches(target)){ready.run();return;}
        long token=++generation;current=null;seek.reset();
        clip.isPinned().set(false);nudge.clip().isPinned().set(false);
        track.isPinned().set(true);track.selectChannel(target.track());
        await(token,target,0,false,ready,failed);
    }
    private void await(long token,MulticlipTarget.GrooveChild target,int attempt,boolean selected,
                       Runnable ready,Consumer<String> failed) {
        host.scheduleTask(()->{
            if(token!=generation)return;
            if(!target.track().exists().get() || target.track().position().get()!=target.position()) {
                failed.accept("Child track changed");return;
            }
            if(attempt>=40){failed.accept("Cannot reach child "+(target.lane()+1)+" scene "+(target.scene()+1));return;}
            if(track.position().get()!=target.position()) {
                await(token,target,attempt+1,selected,ready,failed);return;
            }
            if(!selected){clip.selectFirst();await(token,target,attempt+1,true,ready,failed);return;}
            if(!clip.exists().get() || clip.getTrack().position().get()!=target.position()
                    || clip.clipLauncherSlot().sceneIndex().get()!=target.scene()) {
                seek.advance(clip,target.position(),target.scene(),log);
                await(token,target,attempt+1,true,ready,failed);return;
            }
            clip.isPinned().set(true);
            nudge.clip().selectClip(clip);nudge.clip().isPinned().set(true);
            nudge.focus(MulticlipTarget.FIRST_NOTE+target.lane());
            settle(token,target,0,ready,failed);
        },75);
    }
    private void settle(long token,MulticlipTarget.GrooveChild target,int attempt,Runnable ready,Consumer<String> failed) {
        host.scheduleTask(()->{
            if(token!=generation)return;
            if(matches(target)){current=target;ready.run();}
            else if(attempt<20)settle(token,target,attempt+1,ready,failed);
            else failed.accept("Child note cursor did not settle");
        },125);
    }
    void cancel(){generation++;current=null;}
}
