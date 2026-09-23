package com.akai.fire.sequence;

import java.nio.file.*;
import java.util.*;
import static com.akai.fire.sequence.GrooveEngine.*;

public final class GrooveFixtureChecks {
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object o){return (Map<String,Object>)o;}
    @SuppressWarnings("unchecked") static List<Object> list(Object o){return (List<Object>)o;}
    static double num(Map<String,Object> m,String k,double d){return m.containsKey(k)?((Number)m.get(k)).doubleValue():d;}
    static boolean bool(Map<String,Object> m,String k,boolean d){return m.containsKey(k)?(boolean)m.get(k):d;}
    static double[] doubles(Object o){return list(o).stream().mapToDouble(n->((Number)n).doubleValue()).toArray();}
    static GrooveSettings settings(Map<String,Object> m){
        var b=GrooveSettings.defaults().edit();
        b.shape=GrooveShapes.Shape.valueOf(((String)m.getOrDefault("presetId","deep_swing_57")).toUpperCase(Locale.ROOT));
        b.base=(int)num(m,"baseDenominator",16);b.depth=num(m,"clipDepth",1);b.master=num(m,"masterDepth",1);
        b.phase=(int)num(m,"phaseSlots",0);b.quantize=num(m,"quantizeAmount",0);b.bias=num(m,"biasInBaseUnits",0);
        b.alignment=num(m,"alignmentOffsetBeats",0);b.captureWindow=num(m,"captureWindowInBaseUnits",.25);
        b.anchors=bool(m,"protectQuarterAnchors",b.shape.motion());b.offGrid=bool(m,"includeOffGrid",false);b.bypass=bool(m,"bypass",false);
        return b.build();
    }
    static List<Note> notes(Object o){return list(o).stream().map(GrooveFixtureChecks::map).map(n->new Note((String)n.get("id"),num(n,"startBeats",0),
            num(n,"durationBeats",.1),(int)num(n,"pitch",42),(int)num(n,"channel",0),
            Map.of("velocity",num(n,"velocity",.75)),bool(n,"protected",false))).toList();}
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    static void near(double a,double b,double eps,String m){check(Math.abs(a-b)<=eps,m+" actual="+a+" expected="+b);}
    public static void main(String[] args)throws Exception{
        var fixture=map(new FixtureJson(Files.readString(Path.of("Groove/Bitwig_Groove_Shapes_Test_Vectors.json"))).read());
        int numerical=0;
        for(Object raw:list(fixture.get("numericCases"))){
            var c=map(raw);var m=map(c.get("settings"));var n=notes(c.get("notes"));var s=settings(m);
            var p=plan(n,num(m,"loopStartBeats",0),num(m,"loopLengthBeats",4),s);
            double[] expected=doubles(c.get("expectedStartBeats"));
            for(int i=0;i<n.size();i++){
                near(p.notes().get(i).start(),expected[i],num(c,"absoluteToleranceBeats",1e-10),(String)c.get("id"));
                check(n.get(i).at(p.notes().get(i).start()).equals(p.notes().get(i)),"all non-timing properties retained");
            }
            numerical++;
        }
        for(Object raw:list(fixture.get("presets"))){var c=map(raw);double[] a=GrooveShapes.samples(GrooveShapes.Shape.valueOf(((String)c.get("id")).toUpperCase(Locale.ROOT)),16,0),b=doubles(c.get("offsetsInBaseUnits"));check(a.length==b.length,"native period");for(int i=0;i<a.length;i++)near(a[i],b[i],1e-12,"preset");}
        for(Object raw:list(fixture.get("motionShapeSamples"))){var c=map(raw);double[] a=GrooveShapes.samples(GrooveShapes.Shape.valueOf(((String)c.get("id")).toUpperCase(Locale.ROOT)),(int)num(c,"cycleSlots",16),c.get("seed")==null?0:((Number)c.get("seed")).longValue()),b=doubles(c.get("normalizedSamples"));for(int i=0;i<a.length;i++)near(a[i],b[i],num(c,"absoluteSampleTolerance",1e-12),(String)c.get("id"));}
        for(Object raw:list(fixture.get("rejectionCases"))){var c=map(raw);var m=map(c.get("settings"));var s=settings(m);var n=notes(c.get("notes"));
            var p=plan(n,0,4,s,m.containsKey("customOffsetsInBaseUnits")?doubles(m.get("customOffsetsInBaseUnits")):s.pattern());
            double[] expected=doubles(c.get("unconstrainedStartBeats"));for(int i=0;i<n.size();i++)near(p.notes().get(i).start(),expected[i],1e-10,"unconstrained");
            var result=GrooveSafety.validate(n,n,p,0,4,s,new GrooveSafety.Capabilities(1e-6,.25,true,true,true,true,true,true,false),false,false,false);
            check(!result.allowed(),"rejection: "+c.get("id"));
        }
        for(Object raw:list(fixture.get("loopCompatibilityCases"))){var c=map(raw);boolean compatible=true;for(double l:doubles(c.get("loopLengthsBeats")))compatible&=compatible(l,num(c,"effectivePeriodBeats",0));check(compatible==bool(c,"continuousStaticCompatibility",false),"loop case");}
        double[] expanded=new double[16];for(int i=0;i<16;i++)expanded[i]=i%2==0?0:.14;
        near(period(GrooveSettings.defaults(),expanded),.5,1e-12,"effective period, not declared length");
        var original=List.of(new Note("a",.265,.1,42,0,Map.of("velocity",.7),false));
        for(int repeat=0;repeat<1000;repeat++)for(double depth:new double[]{0,1,.25,.8,0}){
            var b=GrooveSettings.defaults().edit();b.depth=depth;
            var target=plan(original,0,4,b.build()).notes().get(0);
            if(depth==0)check(target.equals(original.get(0)),"no cumulative drift");
        }
        for(var shape:GrooveShapes.Shape.values()) {
            var switcher=GrooveSettings.defaults().edit();switcher.shape=shape;
            plan(original,0,4,switcher.build());switcher.bypass=true;
            check(plan(original,0,4,switcher.build()).notes().equals(original),"bypass after every preset returns originals");
            switcher.bypass=false;switcher.depth=0;
            check(plan(original,0,4,switcher.build()).notes().equals(original),"preset switches never redefine originals");
        }
        var random0=GrooveShapes.samples(GrooveShapes.Shape.SEEDED_RANDOM,16,0);
        var random1=GrooveShapes.samples(GrooveShapes.Shape.SEEDED_RANDOM,16,1);
        check(!Arrays.equals(random0,random1),"explicit new seed changes template");
        random0[0]=100;
        check(GrooveShapes.samples(GrooveShapes.Shape.SEEDED_RANDOM,16,0)[0]!=100,"cached samples cannot be mutated by caller");
        check(nearestEarlier(.5)==0&&nearestEarlier(-.5)==-1,"signed half ties earlier");
        var b=GrooveSettings.defaults().edit();b.depth=0;b.quantize=1;b.anchors=true;
        near(period(b.build(),b.build().pattern()),1,1e-12,"anchor quantization mask period");
        System.out.println("Groove fixtures passed: "+numerical+" numerical, 8 presets, 6 motion, 3 rejection, 4 compatibility; 1000 drift cycles.");
    }
}
