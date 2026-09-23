package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.*;
import java.lang.reflect.*;
import java.util.*;

/** Exercises actual API adapter paging and capability gates without a Bitwig process. */
public final class GrooveHostChecks {
    static final ArrayDeque<Runnable> queue=new ArrayDeque<>();
    static final List<String> calls=new ArrayList<>();
    static class Api implements InvocationHandler {
        final String name;final Map<String,Api> nodes=new HashMap<>();Object value;
        final Map<Integer,Double> velocities=new HashMap<>();
        int page,pitch; Set<Integer> drumNotes; boolean otherPitch;
        Api(String name){this.name=name;}
        Api node(String key){return nodes.computeIfAbsent(key,k->new Api(name+"."+k));}
        <T>T proxy(Class<T> type){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},this));}
        public Object invoke(Object p,Method m,Object[] a){String op=m.getName();Class<?> type=m.getReturnType();
            if(op.equals("scheduleTask")){queue.add((Runnable)a[0]);return null;}
            if(op.equals("set")){value=a[0];calls.add(name+".set");return null;}
            if(op.equals("scrollToStep")){page=(int)a[0];return null;}
            if(op.equals("scrollToKey")){pitch=(int)a[0];return null;}
            if(op.equals("get")&&value!=null)return value;
            if(op.equals("getRaw"))return 125.0;
            if(op.equals("moveStep")) {
                check(drumNotes!=null,"write requires drum fixture");
                int from=(int)a[1],to=from+(int)a[3];
                check(drumNotes.remove(from)&&!drumNotes.contains(to),"move existing note into empty cell");
                velocities.put(to,velocities.getOrDefault(from,.7));velocities.remove(from);
                drumNotes.add(to);calls.add(name+".moveStep");return null;
            }
            if(op.equals("getStep")){
                int channel=(int)a[0],x=(int)a[1],y=(int)a[2],key=pitch+y;
                // Same pitch on another channel and an onset beyond first time page / key window.
                boolean onset=(channel==0&&key==36&&page+x==16)||(channel==15&&key==127&&page+x==300);
                if(drumNotes!=null)onset=channel==0&&key==36&&drumNotes.contains(page+x);
                final boolean noteOn=onset || (otherPitch&&key==127&&channel==0&&x==0);
                return Proxy.newProxyInstance(NoteStep.class.getClassLoader(),new Class<?>[]{NoteStep.class},(p2,m2,a2)->switch(m2.getName()){
                    case "state" -> noteOn?NoteStep.State.NoteOn:NoteStep.State.Empty;
                    case "x" -> x;case "y" -> y;case "channel" -> channel;
                    case "setVelocity" -> {velocities.put(page+x,(double)a2[0]);calls.add(name+".setVelocity");yield null;}
                    case "duration" -> .1;case "velocity" -> velocities.getOrDefault(page+x,.7);case "occurrence" -> NoteOccurrence.ALWAYS;
                    default -> m2.getReturnType()==boolean.class?false:m2.getReturnType()==int.class?(Object)0:0.0;
                });
            }
            if(type==void.class){calls.add(name+"."+op);return null;}
            if(type.isInterface())return node(op).proxy(type);
            if(type==boolean.class)return false;if(type==int.class)return 0;if(type==double.class)return 0.0;
            if(type==String.class)return "Test clip";return null;
        }
    }
    static void drumWrites() {
        Api host=new Api("drumHost"),source=new Api("source"),track=new Api("sourceTrack");
        Api fine=track.node("createLauncherCursorClip"),view=host.node("createCursorTrack").node("createLauncherCursorClip");
        host.node("createTransport").node("isPlaying").value=true;
        Set<Integer> notes=new HashSet<>(Set.of(16,48));fine.drumNotes=notes;view.drumNotes=notes;
        for(Api clip:List.of(source,fine,view)) {
            clip.node("exists").value=true;clip.node("createEqualsValue").value=true;
            clip.node("getLoopStart").value=0.0;clip.node("getLoopLength").value=4.0;
            clip.node("isLoopEnabled").value=true;
        }
        FineNudge nudge=new FineNudge(host.proxy(ControllerHost.class),track.proxy(CursorTrack.class),source.proxy(PinnableCursorClip.class),s->{});
        nudge.focus(36);drain();
        GrooveHostPort port=new GrooveHostPort(host.proxy(ControllerHost.class),track.proxy(CursorTrack.class),source.proxy(PinnableCursorClip.class),()->true,()->.25,nudge);
        GrooveSession session=new GrooveSession(port,()->{});
        session.begin();drain();
        check(session.original()!=null && session.original().playing() && session.original().capabilities().playingPreview(),"drum capture and edits supported during playback");
        check(session.state()==GrooveSession.State.STAGED,"capture actual fine nudge drum cells: "+session.message());
        check(session.original().capabilities().drumCellMode()&&!session.original().capabilities().exactStarts(),"explicit constrained mode, not exact onset claim");
        session.preview();drain();
        check(session.state()==GrooveSession.State.PREVIEW,"preview acknowledged: "+session.message());
        check(notes.equals(Set.of(18,50)),"swing moves existing fine cells");
        check(nudge.pageNotes(.25,0).stream().map(NoteStep::x).collect(java.util.stream.Collectors.toSet()).equals(Set.of(1,3)),"Fire logical slots stay fixed");
        var b=session.settings().edit();b.depth=.5;session.stage(b.build());drain();
        check(notes.equals(Set.of(17,49)),"depth derives from originals, not previous preview");
        b=session.settings().edit();b.quantize=.5;session.stage(b.build());drain();
        check(notes.equals(Set.of(17,49)),"unsupported pre-quantization cannot write");
        session.cancel();drain();check(notes.equals(Set.of(16,48)),"cancel restores originals even after unsupported parameter selection");
        session.stage(GrooveSettings.defaults());
        view.node("clipLauncherSlot").node("isRecording").value=true;session.begin();drain();
        check(session.state()==GrooveSession.State.CLOSED&&notes.equals(Set.of(16,48)),"recording still blocks capture without writes");
        view.node("clipLauncherSlot").node("isRecording").value=false;
        view.otherPitch=true;session.begin();drain();
        check(session.state()==GrooveSession.State.CLOSED&&notes.equals(Set.of(16,48)),"mixed pitch clip rejected before writes");
        view.otherPitch=false;session.begin();drain();notes.add(80);session.preview();drain();
        check(session.state()==GrooveSession.State.CONFLICT&&notes.equals(Set.of(16,48,80)),"external additions stop preview without overwrite");
        session.keepHostEdits();
        notes.clear();notes.addAll(Set.of(16,48));session.stage(GrooveSettings.defaults());session.begin();drain();session.preview();drain();
        notes.remove(50);notes.add(80);
        List<String> lockResult=new ArrayList<>();session.reconcile(lockResult::add);drain();
        check(lockResult.equals(List.of("")),"host adapter adopts new and deleted note identities");
        session.preview();drain();check(notes.equals(Set.of(18,82)),"adopted note moves through actual FineNudge");
        notes.remove(82);notes.add(80);lockResult.clear();session.reconcile(lockResult::add);drain();session.preview();drain();
        check(notes.equals(Set.of(18,82)),"recreated source cell gets a fresh identity without duplicate offsets");
        session.cancel();drain();check(notes.equals(Set.of(16,80)),"host lock restores new originals without deleted notes");
        session.stage(GrooveLogicVelocityChecks.settings(1,true,100,70,120,0));
        session.begin();drain();session.preview();drain();
        check(session.state()==GrooveSession.State.PREVIEW && notes.equals(Set.of(20,84)),"Logic velocity preview through actual adapter: "+session.message());
        check(fine.velocities.get(20)==70/127.0 && fine.velocities.get(84)==70/127.0,"NoteStep velocity setter at acknowledged destination");
        session.stage(GrooveLogicVelocityChecks.settings(1,false,100,70,120,0));drain();
        check(notes.equals(Set.of(20,84)) && fine.velocities.get(20)==.7,"velocity disable does not undo timing");
        session.stage(GrooveLogicVelocityChecks.settings(0,true,-100,70,120,0));drain();
        check(notes.equals(Set.of(16,80)) && fine.velocities.get(16)==120/127.0,"velocity-only via API adapter");
        session.cancel();drain();
        check(notes.equals(Set.of(16,80)) && fine.velocities.get(16)==.7 && fine.velocities.get(80)==.7,"adapter restores exact original velocity doubles");
        check(calls.stream().anyMatch(c->c.endsWith(".setVelocity")),"actual velocity API used");
        check(calls.stream().noneMatch(c->c.contains("setDuration")||c.endsWith(".setStep")||c.endsWith(".clearStep")),"groove never recreates notes or alters duration");
    }
    static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
    static void drain(){int n=0;while(!queue.isEmpty()){queue.remove().run();check(n++<500,"bounded inspection");}}
    public static void main(String[] args){
        Api host=new Api("host"),source=new Api("source"),sourceTrack=new Api("sourceTrack");
        source.node("exists").value=true;
        Api view=host.node("createCursorTrack").node("createLauncherCursorClip");
        view.node("exists").value=true;view.node("createEqualsValue").value=true;
        view.node("getLoopStart").value=0.0;view.node("getLoopLength").value=8.0;view.node("isLoopEnabled").value=true;
        var port=new GrooveHostPort(host.proxy(ControllerHost.class),sourceTrack.proxy(CursorTrack.class),source.proxy(PinnableCursorClip.class),()->true,()->.25);
        var session=new GrooveSession(port,()->{});
        check(queue.isEmpty(),"construct/open/restore settings do not capture or write");
        session.stage(GrooveSettings.defaults());check(queue.isEmpty(),"shape selection cannot write");
        session.begin();drain();
        check(session.original()!=null&&session.original().notes().size()==2,"inspection covers pitch 127/channel 15 and beyond first visible page");
        check(!session.original().capabilities().complete()&&!session.original().capabilities().exactStarts(),"stable cells are not claimed as complete exact notes");
        session.preview();session.apply();drain();
        check(calls.stream().noneMatch(c->c.endsWith(".moveStep")||c.endsWith(".setStep")||c.contains("setDuration")||c.endsWith(".clearStep")),"unsupported preview/apply cause no note writes");
        session.cancel();check(session.state()==GrooveSession.State.CLOSED,"read-only cancel closes");
        session.begin();view.node("createEqualsValue").value=false;drain();check(session.original()==null,"wrong host object cannot be captured");
        view.node("createEqualsValue").value=true;session.begin();session.cancel();drain();check(session.state()==GrooveSession.State.CLOSED,"stale paged capture cancelled");
        drumWrites();
        System.out.println("Groove host checks passed: no automatic writes, all-pitch/page inspection, exact-read gate, pinned equality, stale captures; live FineNudge bridge, stable pads, depth and Cancel.");
    }
}
