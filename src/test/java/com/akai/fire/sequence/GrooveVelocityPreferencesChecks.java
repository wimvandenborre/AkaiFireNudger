package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;
import static com.akai.fire.sequence.GrooveSessionChecks.check;

public final class GrooveVelocityPreferencesChecks {
    static final class Setting implements InvocationHandler {
        Object value,observer;Runnable signal;
        Setting(Object value){this.value=value;}
        void emit(Object value){this.value=value;try{
            if(observer==null)return;
            for(Method method:observer.getClass().getMethods())if(method.getName().equals("valueChanged")){
                method.setAccessible(true);method.invoke(observer,value);return;
            }
            throw new AssertionError("missing value observer");
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}}
        public Object invoke(Object proxy,Method method,Object[] args){
            switch(method.getName()){
                case "addRawValueObserver", "addValueObserver" -> {observer=args[0];return null;}
                case "getRaw", "get" -> {return value;}
                case "setRaw", "set" -> {emit(args[0]);return null;}
                case "addSignalObserver" -> {signal=((com.bitwig.extension.callback.NoArgsCallback)args[0])::call;return null;}
                default -> {return null;}
            }
        }
    }
    static final class Fixture {
        Map<String,Setting> settings=new LinkedHashMap<>();
        List<GrooveVelocity> values=new ArrayList<>();List<Boolean> edits=new ArrayList<>();
        GrooveVelocityPreferences preferences;
        Fixture(){
            Settings api=(Settings)Proxy.newProxyInstance(Settings.class.getClassLoader(),new Class<?>[]{Settings.class},(p,m,a)->{
                String name=(String)a[0];
                check(a[1].equals("Groove velocity"),"settings-only velocity category");
                Object initial=m.getName().equals("getNumberSetting")?a[6]:m.getName().equals("getEnumSetting")?a[3]:null;
                Setting setting=new Setting(initial);settings.put(name,setting);
                return Proxy.newProxyInstance(m.getReturnType().getClassLoader(),new Class<?>[]{m.getReturnType()},setting);
            });
            preferences=new GrooveVelocityPreferences(api,(v,user)->{values.add(v);edits.add(user);});
        }
        void load(){for(var s:settings.values())if(s.observer!=null)s.emit(s.value);}
        void set(String name,Object value){settings.get(name).emit(value);}
        GrooveVelocity value(){return preferences.current();}
    }
    public static void main(String[] args) {
        Fixture f=new Fixture();check(f.values.isEmpty(),"construct does not trigger edits");f.load();
        check(f.value().equals(GrooveVelocity.defaults())&&f.edits.isEmpty(),"migration off/zero; initial delivery no apply");
        f.preferences.activate();f.set("Enable","On");f.set("Amount",-50.0);
        check(f.value().enabled()&&f.value().amount()==-50&&f.edits.get(f.edits.size()-1),"explicit settings edit applies");
        f.set("Min",125.0);check(f.value().min()==125&&f.value().max()==125,"Min raises Max");
        f.set("Max",40.0);check(f.value().min()==40&&f.value().max()==40,"Max lowers Min");
        f.set("Phase",1.0);f.settings.get("Reset").signal.run();
        check(f.value().amount()==0&&f.value().phase()==0&&f.value().enabled()&&f.value().min()==40,"velocity reset retains enable/range");
        f=new Fixture();f.settings.get("Enable").value="On";f.settings.get("Amount").value=80.0;f.load();
        check(f.value().active()&&f.edits.isEmpty(),"persisted enabled settings cannot automatically write to clips on initialization");
        // Actual NumberSetting caches defaults and may send no default-value echoes.
        f=new Fixture();f.set("Enable","On");f.set("Amount",60.0);
        check(f.edits.isEmpty(),"restored nondefault callbacks cannot initiate edits");
        var selected=f.preferences.activate();
        check(selected.enabled()&&selected.amount()==60&&selected.min()==70&&selected.max()==120&&selected.phase()==0,
                "missing initial Min/Max/Phase callbacks cannot block activation");
        f.set("Amount",80.0);check(f.value().amount()==80&&f.edits.equals(List.of(true)),"live settings changes after explicit activation");
        f.preferences.deactivate();f.edits.clear();f.set("Amount",90.0);
        check(f.edits.isEmpty(),"scene/context reset cannot silently capture new clips from settings updates");
        System.out.println("Velocity preferences checks passed: default off/zero, signed amount, paired range limits, reset, no startup writes.");
    }
}
