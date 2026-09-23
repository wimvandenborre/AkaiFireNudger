package com.akai.fire.sequence;

import java.util.*;
import static com.akai.fire.sequence.GrooveSessionChecks.*;

/** Counts actual protocol reads and proves local edits do not wait for unrelated children. */
public final class GrooveLatencyChecks {
    static void tick(GrooveBatchChecks.Fixture f) {
        if(!f.queue.isEmpty())f.queue.remove().run();
        for(var clip:f.clips)if(!clip.queue.isEmpty())clip.tick();
    }
    static void resetReads(GrooveBatchChecks.Fixture f){for(var clip:f.clips)clip.reads=0;}
    static void add(GrooveBatchChecks.Fixture f,int child) {
        var clip=f.clips.get(child);var notes=new ArrayList<>(clip.notes);
        notes.add(note("new",1.25,36+child));clip.notes=notes;
    }
    public static void main(String[] args) {
        var f=new GrooveBatchChecks.Fixture();f.start(1);f.drain();resetReads(f);
        int writes=f.writes();f.batch.reconcile();f.drain();
        for(var clip:f.clips)check(clip.reads==1,"unchanged child needs only one comparison read");
        check(f.writes()==writes,"idle scan never writes");
        resetReads(f);f.batch.update(GrooveBatchChecks.settings(1));f.drain();
        for(var clip:f.clips)check(clip.reads==1,"unchanged settings result skips second write-pass visit");
        resetReads(f);int before=f.clips.get(2).writes;
        f.batch.notesEdited(2);add(f,2);f.batch.reconcile();
        int ticks=0;while(f.clips.get(2).writes==before){tick(f);check(ticks++<100,"bounded priority write");}
        check(f.clips.get(0).reads==0&&f.clips.get(1).reads==0,"edited last child writes before unrelated child reads");
        f.drain();
        check(f.clips.get(2).notes.get(2).start()==1.28125,"priority new note receives original-based groove");
        check(f.clips.get(0).reads==1&&f.clips.get(1).reads==1,"unchanged children skip re-preflight/write after local edit");
        // A settings change during reconciliation must still preflight every child before any new writes.
        f=new GrooveBatchChecks.Fixture();f.start(1);f.drain();writes=f.writes();
        f.batch.notesEdited(2);add(f,2);f.batch.reconcile();
        f.clips.get(0).recording=true;f.batch.update(GrooveBatchChecks.settings(.5));f.drain();
        check(f.writes()==writes,"pending global settings retains all-child recording guard");
        // Interrupted local preparation retains pending work even if the next read matches adopted content.
        f=new GrooveBatchChecks.Fixture();f.start(1);f.drain();resetReads(f);f.batch.notesEdited(2);add(f,2);
        before=f.clips.get(2).writes;f.batch.reconcile();
        ticks=0;while(f.clips.get(2).reads<3){tick(f);check(ticks++<100,"reach local reconcile");}
        // Inject another known edit during the local pass, then resume with a different priority.
        f.batch.notesEdited(1);f.batch.reconcile();f.drain();
        check(!f.batch.failed()&&f.clips.get(2).writes>before,"interrupted repair is not lost");
        // A temporarily blocked local repair must be retried even after its baseline was adopted.
        f=new GrooveBatchChecks.Fixture();f.start(1);f.drain();writes=f.writes();
        f.batch.notesEdited(2);add(f,2);f.clips.get(2).playing=true;f.batch.reconcile();f.drain();
        check(f.writes()==writes&&!f.batch.failed(),"local playing restriction blocks writes");
        f.clips.get(2).playing=false;f.batch.reconcile();f.drain();
        check(f.clips.get(2).notes.get(2).start()==1.28125,"blocked pending repair resumes without another note edit");
        // Target changes cancel the fast path before it can write.
        f=new GrooveBatchChecks.Fixture();f.start(1);f.drain();writes=f.writes();
        f.batch.notesEdited(2);add(f,2);f.batch.reconcile();f.context="different-scene";f.drain();
        check(f.writes()==writes&&f.batch.failed(),"fast repair retains context guard");
        System.out.println("Groove latency checks passed: selected-child priority, one-read unchanged clips, no redundant write pass, global preflight and interruption/context safety.");
    }
}
