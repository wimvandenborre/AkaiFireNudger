package com.akai.fire.sequence;

import java.util.*;
import static com.akai.fire.sequence.GrooveSessionChecks.*;

/** Multi-child protocol with independent timelines and asynchronous read/write callbacks. */
public final class GrooveBatchChecks {
    static final class Fixture {
        final ArrayDeque<Runnable> queue=new ArrayDeque<>();
        final List<Fake> clips=List.of(new Fake(),new Fake(),new Fake());
        final List<String> messages=new ArrayList<>();
        String context="group1:scene2";
        final GrooveBatch batch=new GrooveBatch((r,ms)->queue.add(r),()->context,messages::add);
        Fixture(){
            for(int i=0;i<clips.size();i++) {
                Fake f=clips.get(i);
                f.notes=List.of(note("a",.25,36+i),note("b",.75,36+i));
                f.caps=new GrooveSafety.Capabilities(1.0/64,.25,false,false,true,true,false,true,false,true);
            }
        }
        void start(double amount){batch.start(new ArrayList<GrooveSession.Port>(clips),context,settings(amount));}
        void drain(){
            int n=0;
            while(!queue.isEmpty()||clips.stream().anyMatch(c->!c.queue.isEmpty())) {
                if(!queue.isEmpty())queue.remove().run();
                for(Fake f:clips)if(!f.queue.isEmpty())f.tick();
                check(n++<5000,"batch callbacks bounded");
            }
        }
        int writes(){return clips.stream().mapToInt(f->f.writes).sum();}
        void positions(double first){for(Fake f:clips)check(f.notes.get(0).start()==first,"all child positions: "+f.notes+messages);}
    }
    static GrooveSettings settings(double amount){var b=GrooveSettings.defaults().edit();b.depth=amount;b.resetEachLoop=true;return b.build();}
    public static void main(String[] args) {
        Fixture f=new Fixture();f.start(1);f.drain();
        check(!f.batch.failed()&&f.writes()==6,"one knob edits all three children "+f.messages);
        f.positions(.28125);
        check(f.messages.get(f.messages.size()-1).contains("6 notes moved"),"report confirmed movement count");
        f.batch.update(settings(1));f.drain();
        check(f.messages.get(f.messages.size()-1).contains("No notes moved"),"unchanged result is not claimed as a new nudge");
        f.batch.update(settings(.5));f.drain();f.positions(.265625);
        f.batch.update(settings(0));f.drain();f.positions(.25);
        check(f.messages.get(f.messages.size()-1).contains("Original timing"),"zero restores all children");
        for(int i=0;i<20;i++){f.batch.update(settings(1));f.batch.update(settings(.5));f.batch.update(settings(0));f.drain();}
        f.positions(.25);
        check(f.writes()==18,"coalesced knob changes do not issue obsolete edits");
        f=new Fixture();f.clips.get(2).recording=true;f.start(1);f.drain();
        check(f.writes()==0,"preflight every child before first write");
        f.clips.get(2).recording=false;f.batch.update(settings(.5));f.drain();f.positions(.265625);
        f=new Fixture();f.start(1);f.context="group1:scene5";f.drain();
        check(f.writes()==0,"scene switch cancels pending group edits");
        f=new Fixture();f.start(1);f.drain();
        f.clips.get(1).notes=List.of(note("a",.3,37),note("b",.78125,37));
        int count=f.writes();f.batch.update(settings(.5));f.drain();
        check(f.batch.failed()&&f.writes()==count,"external edit in any child blocks whole next update");
        f=new Fixture();f.clips.get(1).failAfter=1;f.start(1);f.drain();
        check(f.batch.failed()&&f.clips.get(2).writes==0,"partial write stops later children");
        f=new Fixture();f.start(1);f.drain();count=f.writes();f.batch.discard();f.drain();
        check(f.writes()==count,"leaving scene retains existing timing without extra writes");
        f=new Fixture();f.start(1);f.batch.update(settings(.5));f.drain();f.positions(.265625);
        f=new Fixture();
        for(Fake clip:f.clips) {
            clip.playing=true;
            clip.caps=new GrooveSafety.Capabilities(1.0/64,.25,false,false,true,true,false,true,true,true);
        }
        f.start(1);f.drain();f.positions(.28125);
        f.batch.update(settings(0));f.drain();f.positions(.25);
        check(!f.batch.failed(),"all-child groove and restoration work during playback when drum port supports it");
        System.out.println("Groove batch checks passed: all children, preflight, immutable amount/zero, coalescing, scene changes, external conflicts, partial failures.");
    }
}
