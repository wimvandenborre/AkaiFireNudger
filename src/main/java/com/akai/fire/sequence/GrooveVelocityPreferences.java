package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.*;
import java.util.function.BiConsumer;

/** Settings-only controls. Unchanged defaults need not deliver an initial observer callback. */
final class GrooveVelocityPreferences {
    private final BiConsumer<GrooveVelocity,Boolean> changed;
    private final SettableEnumValue enableSetting;
    private final SettableRangedValue amountSetting,minSetting,maxSetting,phaseSetting;
    private boolean active;
    GrooveVelocityPreferences(Settings preferences, BiConsumer<GrooveVelocity,Boolean> changed) {
        this.changed=changed;
        String category="Groove velocity";
        enableSetting=preferences.getEnumSetting("Enable",category,new String[]{"Off","On"},"Off");
        amountSetting=preferences.getNumberSetting("Amount",category,-100,100,1,"%",0);
        minSetting=preferences.getNumberSetting("Min",category,1,127,1,"",70);
        maxSetting=preferences.getNumberSetting("Max",category,1,127,1,"",120);
        phaseSetting=preferences.getNumberSetting("Phase",category,0,1,1,"",0);
        enableSetting.markInterested();amountSetting.markInterested();minSetting.markInterested();
        maxSetting.markInterested();phaseSetting.markInterested();
        enableSetting.addValueObserver(value->publish());
        amountSetting.addRawValueObserver(value->publish());
        minSetting.addRawValueObserver(value->{
            if(active && value>maxSetting.getRaw())maxSetting.setRaw(value);
            publish();
        });
        maxSetting.addRawValueObserver(value->{
            if(active && value<minSetting.getRaw())minSetting.setRaw(value);
            publish();
        });
        phaseSetting.addRawValueObserver(value->publish());
        preferences.getSignalSetting("Reset",category,"Reset velocity")
                .addSignalObserver(()->{amountSetting.setRaw(0);phaseSetting.setRaw(0);});
    }
    GrooveVelocity current() {
        int min=(int)Math.round(minSetting.getRaw()),max=(int)Math.round(maxSetting.getRaw());
        return new GrooveVelocity("On".equals(enableSetting.get()),(int)Math.round(amountSetting.getRaw()),
                Math.min(min,max),max,(int)Math.round(phaseSetting.getRaw()));
    }
    /** Called only by an explicit groove adjustment; startup preference delivery cannot edit clips. */
    GrooveVelocity activate() { active=true;return current(); }
    void deactivate() { active=false; }
    private void publish() {
        // During initial delivery other settings may not yet be synchronized. Read the
        // complete interested values when the user first operates the groove control.
        if(active)changed.accept(current(),true);
    }
}
