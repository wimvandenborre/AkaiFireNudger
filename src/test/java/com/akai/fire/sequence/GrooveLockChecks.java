package com.akai.fire.sequence;

import java.util.*;
import static com.akai.fire.sequence.GrooveSessionChecks.*;

public final class GrooveLockChecks {
    static void reconcile(GrooveSession s,Fake f) {
        List<String> result=new ArrayList<>();s.reconcile(result::add);f.drain();
        check(result.equals(List.of("")),"stable lock reconciliation: "+result);
    }
    public static void main(String[] args) {
        Fake f=new Fake();GrooveSession s=start(f);s.preview();f.drain();
        var survivor=f.notes.get(0); // already grooved a
        f.notes=List.of(survivor,note("new",1.25,42)); // delete b, add new
        reconcile(s,f);s.preview();f.drain();
        check(f.notes.stream().map(n->n.start()).toList().equals(List.of(.28125,1.28125)),"new note inherits groove, survivor not grooved twice");
        for(int i=0;i<10;i++){reconcile(s,f);s.preview();f.drain();}
        check(f.notes.get(0).start()==.28125&&f.notes.get(1).start()==1.28125,"repeated lock checks cannot accumulate offsets");
        s.cancel();f.drain();
        check(f.notes.stream().map(n->n.start()).toList().equals(List.of(.25,1.25)),"zero/cancel restores originals of surviving and new notes, never resurrects deletion");

        f=new Fake();s=start(f);s.preview();f.drain();
        f.notes=List.of();reconcile(s,f);s.preview();f.drain();check(f.notes.isEmpty(),"clear lane stays empty");
        f.notes=List.of(note("new-after-empty",.75,42));reconcile(s,f);s.preview();f.drain();
        check(f.notes.get(0).start()==.78125,"refill after clearing retains groove");
        // Known Fire edits interrupt queued groove jobs before they can write to removed cells.
        f=new Fake();s=start(f);s.preview();s.pauseForNotes();f.notes=List.of(note("added",1.25,42));f.drain();
        check(f.writes==0,"pad edit cancels queued obsolete moves");reconcile(s,f);s.preview();f.drain();
        check(f.notes.get(0).start()==1.28125,"interrupted batch can groove new content");

        GrooveBatchChecks.Fixture group=new GrooveBatchChecks.Fixture();group.start(1);group.drain();
        int writes=group.writes();group.batch.reconcile();group.drain();
        check(group.writes()==writes,"idle lock polling does not write");
        group.batch.notesEdited();
        group.clips.get(1).notes=List.of(group.clips.get(1).notes.get(0),note("euclidean-add",1.25,37));
        group.batch.reconcile();group.drain();
        check(!group.batch.failed(),"group lock survives add/remove");
        check(group.clips.get(1).notes.get(1).start()==1.28125,"added child note grooved");
        group.batch.update(GrooveBatchChecks.settings(0));group.drain();
        check(group.clips.get(1).notes.stream().map(n->n.start()).toList().equals(List.of(.25,1.25)),"zero after group edits restores retained originals only");
        check(group.clips.get(0).notes.get(0).start()==.25,"other child baseline retained");
        group=new GrooveBatchChecks.Fixture();group.start(1);group.batch.notesEdited();
        group.clips.get(1).notes=List.of(note("added-during-capture",1.25,37));group.drain();
        check(!group.batch.failed()&&group.clips.get(1).notes.get(0).start()==1.28125,"edits during initial capture are reconciled before applying");
        euclideanOwnership();
        rampDownPreservesVelocity();
        System.out.println("Groove lock checks passed: insert/delete/refill, immutable surviving originals, zero restoration, cancelled queued edits and group synchronization.");
    }
    static void rampDownPreservesVelocity() {
        Fake f=new Fake();
        f.notes=List.of(new GrooveEngine.Note("soft",.25,.08,42,0,Map.of("velocity",.25),false),
                new GrooveEngine.Note("loud",.75,.08,42,0,Map.of("velocity",.9),false));
        GrooveSession session=start(f);var b=session.settings().edit();b.shape=GrooveShapes.Shape.RAMP_DOWN;
        session.stage(b.build());session.preview();f.drain();
        check(f.notes.get(0).properties().get("velocity").equals(.25)&&f.notes.get(1).properties().get("velocity").equals(.9),
                "Ramp Down preserves different original velocities");
        session.pauseForNotes();var notes=new ArrayList<>(f.notes);
        notes.add(new GrooveEngine.Note("added",1.25,.08,42,0,Map.of("velocity",.1),false));f.notes=notes;
        reconcile(session,f);session.preview();f.drain();
        check(f.notes.stream().map(n->n.properties().get("velocity")).toList().equals(List.of(.25,.9,.1)),
                "Ramp Down lock preserves both surviving and newly created velocities");
    }
    static boolean[] occupied(Fake f) {
        boolean[] slots=new boolean[16];
        for(var note:f.notes)slots[(int)Math.round(note.start()/.25)%16]=true;
        return slots;
    }
    static void euclideanOwnership() {
        Fake f=new Fake();f.notes=List.of(note("manual",0,42));
        GrooveSession session=start(f);session.preview();f.drain();
        EuclideanPattern pattern=new EuclideanPattern(occupied(f));
        java.util.function.IntConsumer add=slot->{
            var notes=new ArrayList<>(f.notes);notes.add(note("euc-"+slot,slot*.25,42));f.notes=notes;
        };
        java.util.function.IntConsumer remove=slot->f.notes=f.notes.stream()
                .filter(n->Math.round(n.start()/.25)!=slot).toList();
        for(int cycle=0;cycle<3;cycle++) {
            session.pauseForNotes();pattern.turn(16,occupied(f),add,remove);
            reconcile(session,f);session.preview();f.drain();
            check(f.notes.size()==16&&f.notes.stream().anyMatch(n->n.start()==.28125),"Euclidean fill receives groove");
            session.pauseForNotes();pattern.turn(-8,occupied(f),add,remove);
            reconcile(session,f);session.preview();f.drain();
            session.pauseForNotes();pattern.turn(-8,occupied(f),add,remove);
            reconcile(session,f);session.preview();f.drain();
            check(f.notes.size()==1&&f.notes.get(0).id().equals("manual"),"Euclidean zero removes grooved generated notes, retaining manual original");
        }
    }
}
