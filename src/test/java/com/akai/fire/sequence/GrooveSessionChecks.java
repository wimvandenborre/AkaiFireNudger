package com.akai.fire.sequence;

import java.util.*;
import java.util.function.Consumer;
import static com.akai.fire.sequence.GrooveEngine.*;

public final class GrooveSessionChecks {
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    static final class Fake implements GrooveSession.Port {
        Object binding=new Object();
        List<Note> notes=new ArrayList<>(List.of(note("a",.25,42),note("b",.75,42)));
        final List<Note> otherClip=new ArrayList<>(List.of(note("same-pitch",.25,42)));
        final ArrayDeque<Runnable> queue=new ArrayDeque<>();
        GrooveSafety.Capabilities caps=new GrooveSafety.Capabilities(1.0/64,.25,true,true,true,true,false,false,false);
        int reads,writes,releases,failAfter=Integer.MAX_VALUE;boolean playing,recording,failWrite,delayAck;Runnable external;
        GrooveSession.Read snapshot(){return new GrooveSession.Read(binding,"Clip",0,4,notes,caps,playing,recording,"");}
        public void capture(Consumer<GrooveSession.Read> complete,Consumer<String> fail){queue.add(()->complete.accept(snapshot()));}
        public void read(Consumer<GrooveSession.Read> complete,Consumer<String> fail){reads++;queue.add(()->complete.accept(snapshot()));}
        public void move(GrooveSafety.Move move){
            if(failWrite||writes>=failAfter)throw new IllegalStateException("Injected write failure");
            writes++;
            Runnable change=()->{notes=notes.stream().map(n->n.id().equals(move.from().id())?move.to():n).toList();if(external!=null){external.run();external=null;}};
            if(delayAck)queue.add(()->queue.add(change));else change.run();
        }
        public void schedule(Runnable work,int ms){queue.add(work);}
        public void release(){releases++;}
        void tick(){check(!queue.isEmpty(),"pending callback");queue.remove().run();}
        void drain(){int count=0;while(!queue.isEmpty()){tick();check(count++<500,"bounded callbacks");}}
    }
    static Note note(String id,double start,int pitch){return new Note(id,start,.08,pitch,0,
            Map.of("velocity",.7,"release",.3,"chance",.4,"muted",true,"repeat",3,"expression",List.of(.1,.2,.8)),false);}
    static GrooveSession start(Fake f){var s=new GrooveSession(f,()->{});s.begin();f.drain();check(f.writes==0,"capture/stage no writes");return s;}
    public static void main(String[] args){
        Fake f=new Fake();List<Note> original=List.copyOf(f.notes);var s=start(f);
        s.preview();f.drain();check(s.state()==GrooveSession.State.PREVIEW&&f.writes==2,"explicit preview");
        check(f.notes.get(0).start()==.28125,"rounded to actual nudge quantum");
        check(f.notes.get(0).properties().equals(original.get(0).properties())&&f.notes.get(0).duration()==original.get(0).duration(),"properties/duration retained");
        check(f.otherClip.get(0).start()==.25,"another clip with same pitch untouched");
        int written=f.writes;s.apply();f.drain();check(f.writes==written&&s.state()==GrooveSession.State.APPLIED,"Apply after preview never doubles effect");
        s.begin();f.drain();check(f.writes==written,"new capture does not reapply settings");s.cancel();f.drain();
        f=new Fake();original=List.copyOf(f.notes);s=start(f);s.preview();f.drain();
        s.bypass();f.drain();check(f.notes.equals(original),"bypass exact originals");s.preview();f.drain();s.reset();f.drain();
        check(f.notes.equals(original)&&s.settings().depth()==0&&s.settings().quantize()==0&&s.settings().bias()==0,"reset restores and zeros controls");
        s.cancel();f.drain();check(s.state()==GrooveSession.State.CLOSED,"cancel closes");
        // Coalescing staged preview changes never recaptures a preview as baseline.
        f=new Fake();original=List.copyOf(f.notes);s=start(f);s.preview();f.drain();
        for(int i=0;i<50;i++){var b=s.settings().edit();b.depth=(i%5)/4.0;s.stage(b.build());}
        var zero=s.settings().edit();zero.depth=0;s.stage(zero.build());f.drain();check(f.notes.equals(original),"coalesced depth zero restores exact baseline");
        // Delayed own echoes are accepted only as before or expected-after states.
        f=new Fake();f.delayAck=true;s=start(f);s.preview();f.drain();check(s.state()==GrooveSession.State.PREVIEW,"delayed acknowledgement");
        f=new Fake();s=start(f);f.notes=List.of(note("a",.3,42),note("b",.75,42));s.apply();f.drain();
        check(s.state()==GrooveSession.State.CONFLICT&&f.writes==0,"external edit before apply");s.cancel();f.drain();check(f.notes.get(0).start()==.3,"cancel cannot overwrite external changes");
        s.keepHostEdits();check(s.state()==GrooveSession.State.CLOSED,"explicit conflict resolution");
        f=new Fake();s=start(f);Fake conflictHost=f;
        f.external=()->conflictHost.notes=List.of(note("a",.31,42),note("b",.75,42));s.preview();f.drain();
        check(s.state()==GrooveSession.State.CONFLICT&&f.writes==1,"external edit during ack stops remaining writes");
        f=new Fake();s=start(f);f.failWrite=true;s.apply();f.drain();check(s.state()==GrooveSession.State.CONFLICT,"uncertain write failure reported");
        f=new Fake();s=start(f);f.failAfter=1;s.apply();f.drain();
        check(f.writes==1&&s.state()==GrooveSession.State.CONFLICT&&s.message().contains("uncertain"),"partial batch failure reports uncertainty without restoring over unknown edits");
        f=new Fake();s=start(f);Fake recordHost=f;f.external=()->recordHost.recording=true;s.preview();f.drain();
        check(f.writes==1&&s.state()==GrooveSession.State.CONFLICT,"recording begun mid-batch stops remaining moves");
        f=new Fake();s=start(f);s.preview();s.invalidate("focus changed");f.drain();check(f.writes==0,"stale scheduled work discarded");
        f=new Fake();s=start(f);f.binding=new Object();s.apply();f.drain();check(f.writes==0&&s.state()==GrooveSession.State.CONFLICT,"renamed/identical-pitch different binding cannot be targeted");
        f=new Fake();s=start(f);f.playing=true;s.apply();f.drain();check(f.writes==0&&s.message().contains("Stop transport"),"playing writes blocked");
        f=new Fake();s=start(f);f.recording=true;s.preview();f.drain();check(f.writes==0,"recording writes blocked");
        f=new Fake();f.caps=new GrooveSafety.Capabilities(1.0/64,.25,false,false,true,false,false,false,false);s=start(f);s.preview();s.apply();f.drain();
        check(f.writes==0&&s.message().contains("Exact note starts"),"unverified host cannot write");s.cancel();check(s.state()==GrooveSession.State.CLOSED,"read-only staged cancel succeeds without writes");
        var n=List.of(note("a",.25,42),note("b",.75,42));var b=GrooveSettings.defaults().edit();b.depth=.01;
        var result=GrooveSafety.validate(n,n,plan(n,0,4,b.build()),0,4,b.build(),new Fake().caps,false,false,false);
        check(!result.allowed()&&result.blocked().contains("resolution"),"sub-resolution movement reported");
        b.depth=1;b.bias=.25;b.shape=GrooveShapes.Shape.GARAGE_SWING_62;
        result=GrooveSafety.validate(n,n,plan(n,0,4,b.build()),0,4,b.build(),new Fake().caps,false,false,false);
        check(!result.allowed()&&result.blocked().contains("40%"),"manual nudge bound retained");
        var crossing=List.of(note("a",0,42),note("b",.1,42));var targets=List.of(crossing.get(0).at(.1),crossing.get(1).at(0));
        check(GrooveSafety.order(crossing,targets,4)==null,"movement cycle rejected");
        var mixed=List.of(note("a",.25,42),note("b",.75,42),note("c",1.25,42));
        check(GrooveSafety.order(mixed,List.of(mixed.get(0).at(.28),mixed.get(1).at(.72),mixed.get(2).at(1.28)),4).size()==3,"mixed direction safe ordering");
        List<Double> curve=new ArrayList<>(List.of(.1,.5));
        Note frozen=new Note("curve",.25,.1,42,0,Map.of("curve",curve),false);
        curve.set(0,.9);check(frozen.properties().get("curve").equals(List.of(.1,.5)),"expression payload frozen deeply");
        System.out.println("Groove session checks passed: immutable originals, explicit writes, drift, acknowledgements, conflict/cancel, target isolation, safety gates.");
    }
}
