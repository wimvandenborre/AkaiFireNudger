package com.akai.fire.sequence;

import java.util.*;
import static com.akai.fire.sequence.GrooveSessionChecks.*;
import static com.akai.fire.sequence.GrooveEngine.*;

public final class GrooveLogicVelocityChecks {
    static GrooveSettings settings(double depth,boolean enable,int amount,int min,int max,int phase) {
        var b=GrooveSettings.defaults().edit();b.shape=GrooveShapes.Shape.LOGIC_16D;b.depth=depth;
        b.velocity=new GrooveVelocity(enable,amount,min,max,phase);return b.build();
    }
    static List<Note> notes(int[] grid,int[] velocity) {
        List<Note> notes=new ArrayList<>();
        for(int i=0;i<grid.length;i++)notes.add(GrooveVelocity.with(note("n"+grid[i],grid[i]*.25,42),velocity[i]/127.0));
        return notes;
    }
    static List<Integer> midi(List<Note> notes) {return notes.stream().map(n->(int)Math.round(GrooveVelocity.value(n)*127)).toList();}
    static void expect(List<Note> originals,GrooveSettings s,Integer... values) {
        check(midi(plan(originals,0,4,s).notes()).equals(List.of(values)),"velocity vector "+Arrays.toString(values));
    }
    static void vectors() {
        var shapes=List.of(GrooveShapes.Shape.LOGIC_16A,GrooveShapes.Shape.LOGIC_16B,GrooveShapes.Shape.LOGIC_16C,
                GrooveShapes.Shape.LOGIC_16D,GrooveShapes.Shape.LOGIC_16E);
        double[] offsets={0,.08,.16,.24,.32};
        for(int i=0;i<5;i++) {
            check(Arrays.equals(GrooveShapes.samples(shapes.get(i),16,0),new double[]{0,offsets[i]}),"exact Logic offsets");
            check(shapes.get(i).id().equals("logic_16"+(char)('a'+i))&&!shapes.get(i).motion(),"stable Logic ID / not motion");
        }
        check(Arrays.stream(GrooveShapes.Shape.values()).noneMatch(s->s.name().equals("LOGIC_16F")),"16F omitted by request");
        var n=notes(new int[]{0,1,2,3},new int[]{100,100,100,100});
        expect(n,settings(0,true,100,70,120,0),120,70,120,70);
        expect(n,settings(0,true,50,70,120,0),110,85,110,85);
        expect(n,settings(0,true,-100,70,120,0),70,120,70,120);
        expect(n,settings(0,true,-50,70,120,0),85,110,85,110);
        expect(n,settings(0,true,100,70,120,1),70,120,70,120);
        expect(notes(new int[]{0,3,4,7},new int[]{100,100,100,100}),settings(0,true,100,70,120,0),120,70,120,70);
        expect(notes(new int[]{0,1,2,3},new int[]{80,100,72,115}),settings(0,true,50,60,120,0),100,80,96,88);
        expect(n,settings(0,true,100,1,127,0),127,1,127,1);
        expect(n,settings(0,true,-100,64,64,1),64,64,64,64);
        var fractional=List.of(note("exact",.25,42));
        for(var s:List.of(settings(0,true,0,70,120,0),settings(0,false,100,70,120,1)))
            check(plan(fractional,0,4,s).notes().equals(fractional),"off/zero exact host doubles without rounding");
        var b=settings(1,true,100,70,120,0).edit();b.bypass=true;
        check(plan(n,0,4,b.build()).notes().equals(n),"whole-session bypass restores both");
        var p=plan(n,0,4,settings(.5,true,100,70,120,0));
        check(Math.abs(p.notes().get(1).start()-.28)<1e-12,"half timing depth");
        check(midi(p.notes()).equals(List.of(120,70,120,70)),"velocity independent of timing depth");
        b=settings(1,true,100,70,120,0).edit();b.phase=1;
        check(midi(plan(n,0,4,b.build()).notes()).equals(List.of(120,70,120,70)),"independent timing/velocity phase");
        boolean invalid=false;try{new GrooveVelocity(true,50,121,70,0);}catch(IllegalArgumentException e){invalid=true;}
        check(invalid,"inverted range rejected");
    }
    static void sessions() {
        Fake f=new Fake();f.delayAck=true;var originals=List.copyOf(f.notes);var s=start(f);
        s.stage(settings(1,true,100,70,120,0));s.preview();f.drain();
        check(s.state()==GrooveSession.State.PREVIEW&&f.writes==4,"separate timing/velocity acknowledgements");
        check(f.notes.get(0).start()==.3125&&midi(f.notes).equals(List.of(70,70)),"combined original-based desired state");
        for(int amount:new int[]{0,100,25,-60,80,-100,0}) {
            s.stage(settings(1,true,amount,70,120,0));f.drain();
            check(f.notes.get(0).start()==.3125,"velocity adjustment never moves timing");
        }
        for(int i=0;i<originals.size();i++)check(f.notes.get(i).properties().equals(originals.get(i).properties()),"no cumulative velocity drift");
        s.stage(settings(1,true,100,70,120,0));f.drain();s.reset();f.drain();
        check(f.notes.get(0).start()==.25&&midi(f.notes).equals(List.of(70,70)),"timing Reset preserves velocity");
        s.stage(settings(0,false,100,70,120,0));s.preview();f.drain();check(f.notes.equals(originals),"disable restores velocity independently");
        s.stage(settings(0,true,-100,70,120,0));f.drain();
        check(f.notes.get(0).start()==.25&&midi(f.notes).equals(List.of(120,120)),"velocity-only session");
        // A malformed plan may not smuggle changes to arbitrary note properties.
        var before=originals.get(0);var props=new HashMap<>(before.properties());props.put("chance",.8);
        var bad=new Note(before.id(),before.start(),before.duration(),before.pitch(),before.channel(),props,false);
        var forged=new Plan(List.of(new Change(before,bad,1,1,Eligibility.AFFECTED)),0,true,0,0);
        check(!GrooveSafety.validate(List.of(before),List.of(before),forged,0,4,settings(0,true,100,70,120,0),f.caps,false,false,false).allowed(),"nonvelocity property changes blocked");
        s.cancel();f.drain();check(f.notes.equals(originals),"cancel restores both layers exactly");
    }
    static void lockAndGroup() {
        Fake f=new Fake();var s=start(f);s.stage(settings(1,true,50,70,120,0));s.preview();f.drain();
        var survivor=f.notes.get(0);
        f.notes=List.of(survivor,GrooveVelocity.with(note("added",1.25,42),.9));
        for(int i=0;i<6;i++){GrooveLockChecks.reconcile(s,f);s.preview();f.drain();}
        check(midi(f.notes).equals(List.of(79,92)),"lock uses original velocity for survivors/new notes");
        s.stage(settings(0,true,0,70,120,0));f.drain();
        check(f.notes.stream().map(GrooveVelocity::value).toList().equals(List.of(.7,.9)),"lock zero restores distinct original doubles, no deleted note returns");
        s.stage(settings(1,true,50,70,120,0));f.drain();
        f.notes=List.of(GrooveVelocity.with(f.notes.get(0),.8),f.notes.get(1));
        GrooveLockChecks.reconcile(s,f);s.preview();f.drain();s.stage(settings(0,true,0,70,120,0));f.drain();
        check(GrooveVelocity.value(f.notes.get(0))==.8,"genuine external velocity edit becomes that note's baseline");
        var group=new GrooveBatchChecks.Fixture();
        group.batch.start(new ArrayList<GrooveSession.Port>(group.clips),group.context,settings(1,true,50,70,120,0));group.drain();
        check(!group.batch.failed(),"combined group preview");
        group.batch.notesEdited();group.clips.get(1).notes=List.of(group.clips.get(1).notes.get(0),note("euc-added",1.25,37));
        group.batch.reconcile();group.drain();
        group.batch.update(settings(0,true,0,70,120,0));group.drain();
        for(var clip:group.clips)for(var note:clip.notes)check(GrooveVelocity.value(note)==.7,"multi-child lock restores original velocities");
        check(group.clips.get(1).notes.get(1).id().equals("euc-added"),"deletion stays removed");
    }
    static void controllerMaster() {
        var preference=new GrooveVelocity(true,-80,70,120,0);
        var shape=GrooveShapes.Shape.LOGIC_16C;
        var group=new GrooveBatchChecks.Fixture();
        var original=group.clips.stream().map(c->List.copyOf(c.notes)).toList();
        group.batch.start(new ArrayList<GrooveSession.Port>(group.clips),group.context,
                GrooveSettings.forController(shape,100,preference));group.drain();
        for(int amount:new int[]{50,100,25,0,80,0}) {
            var settings=GrooveSettings.forController(shape,amount,preference);
            check(settings.velocity().amount()==-80*amount/100,"master scales signed velocity amount");
            group.batch.update(settings);group.drain();
            check(!group.batch.failed(),"master group update succeeds");
            if(amount==0)for(int i=0;i<group.clips.size();i++)
                check(group.clips.get(i).notes.equals(original.get(i)),"display zero restores exact timing AND velocity");
        }
        check(preference.amount()==-80&&preference.enabled(),"master does not overwrite saved velocity preferences");
        group.batch.update(GrooveSettings.forController(shape,50,preference));group.drain();
        group.batch.notesEdited();
        group.clips.get(1).notes=List.of(group.clips.get(1).notes.get(0),GrooveVelocity.with(note("new",1.25,37),.9));
        group.batch.reconcile();group.drain();
        var added=group.clips.get(1).notes.get(1);
        check(added.start()==1.265625&&GrooveVelocity.value(added)==117/127.0,
                "new note automatically inherits current 50% timing and velocity without turning the groove knob");
        check(GrooveVelocity.value(group.clips.get(1).notes.get(0))==101/127.0,
                "survivor retains the same master-scaled groove without accumulating velocity");
        group.batch.update(GrooveSettings.forController(shape,0,preference));group.drain();
        check(group.clips.get(1).notes.stream().map(GrooveVelocity::value).toList().equals(List.of(.7,.9)),
                "master zero after lock restores surviving/new velocities without resurrecting deleted notes");
        var straight=GrooveSettings.forController(GrooveShapes.Shape.LOGIC_16A,100,preference);
        var notes=original.get(0);var velocityOnly=plan(notes,0,4,straight).notes();
        check(velocityOnly.get(0).start()==notes.get(0).start()&&!velocityOnly.equals(notes),"16A retains velocity-only use");
    }
    public static void main(String[] args) {
        vectors();sessions();lockAndGroup();controllerMaster();
        System.out.println("Logic/velocity checks passed: A-E, signed amounts/ranges/phases, sparse grid, exact zero, drift, independent reset, split acknowledgements and group lock.");
    }
}
