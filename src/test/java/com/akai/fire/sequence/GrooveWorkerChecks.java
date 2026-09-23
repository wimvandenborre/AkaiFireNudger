package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.*;
import java.lang.reflect.*;
import java.util.*;
import static com.akai.fire.sequence.GrooveHostChecks.*;

/** Exercises private cursor navigation, without slot selection or launching. */
public final class GrooveWorkerChecks {
    static final class WorkerApi extends Api {
        WorkerApi(String name){super(name);}
        @Override WorkerApi node(String key){return (WorkerApi)nodes.computeIfAbsent(key,k->new WorkerApi(name+"."+k));}
        @Override public Object invoke(Object proxy,Method method,Object[] args) {
            String op=method.getName();
            if(op.equals("createLauncherCursorClip")) {
                WorkerApi clip=node("clip:"+args[0]);
                clip.node("exists").value=true;
                clip.node("getLoopStart").value=0.0;clip.node("getLoopLength").value=4.0;
                return clip.proxy(PinnableCursorClip.class);
            }
            if(op.equals("selectChannel")) {
                Track target=(Track)args[0];int position=target.position().get();node("position").value=position;
                for(var entry:nodes.entrySet())if(entry.getKey().startsWith("clip:")) {
                    var clip=entry.getValue();clip.node("getTrack").node("position").value=position;
                    clip.node("clipLauncherSlot").node("sceneIndex").value=0;
                }
                return null;
            }
            if(op.equals("selectFirst")||op.equals("selectNext")||op.equals("selectPrevious")) {
                var scene=node("clipLauncherSlot").node("sceneIndex");int value=scene.value==null?0:(int)scene.value;
                scene.value=op.equals("selectFirst")?0:value+(op.equals("selectNext")?1:-1);
                calls.add(name+"."+op);return null;
            }
            if(op.equals("selectClip")) {
                Clip source=(Clip)args[0];node("clipLauncherSlot").node("sceneIndex").value=source.clipLauncherSlot().sceneIndex().get();
                node("getTrack").node("position").value=source.getTrack().position().get();return null;
            }
            return super.invoke(proxy,method,args);
        }
    }
    public static void main(String[] args) {
        calls.clear();queue.clear();WorkerApi host=new WorkerApi("host");
        GrooveClipWorker worker=new GrooveClipWorker(host.proxy(ControllerHost.class),s->{});
        WorkerApi track=new WorkerApi("child");track.node("exists").value=true;track.node("position").value=3;
        var target=new MulticlipTarget.GrooveChild(2,track.proxy(Track.class),3,5);
        boolean[] ready={false};List<String> failures=new ArrayList<>();
        worker.prepare(target,()->ready[0]=true,failures::add);drain();
        check(ready[0]&&failures.isEmpty()&&worker.matches(target),"private worker reaches exact child scene");
        check(worker.nudge.pitch()==38,"worker uses positional child drum pitch");
        int navigation=calls.size();worker.prepare(target,()->{},failures::add);
        check(calls.size()==navigation,"readback on current target does not navigate again");
        ready[0]=false;
        worker.prepare(new MulticlipTarget.GrooveChild(2,target.track(),3,7),()->ready[0]=true,failures::add);
        worker.cancel();drain();check(!ready[0],"stale navigation cancelled");
        check(calls.stream().noneMatch(s->s.endsWith(".select")||s.endsWith(".showInEditor")||s.endsWith(".selectSlot")||s.endsWith(".launch")),"worker never changes editor slot selection or launches clips");
        System.out.println("Groove worker checks passed: private child/scene navigation, pitch, readback reuse, cancellation and no launcher/editor actions.");
    }
}
