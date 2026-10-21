package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.Preferences;
import com.bitwig.extension.controller.api.SettableRangedValue;
import java.util.function.IntConsumer;

/** Created before the other preferences so scene selection appears at the top. */
public final class SceneSelectionSettings {
    private int requestedScene = 1;
    private IntConsumer select;
    private Runnable follow;
    private final SettableRangedValue scene;

    public SceneSelectionSettings(Preferences preferences, Runnable unavailable) {
        select = ignored -> unavailable.run();
        follow = unavailable;
        scene = preferences.getNumberSetting("Scene (1-based)", "Clip selection", 1,
                Integer.MAX_VALUE, 1, "", 1);
        scene.addRawValueObserver(value -> requestedScene = (int) Math.round(value));
        preferences.getSignalSetting("Select scene clips", "Clip selection", "Select")
                .addSignalObserver(() -> select.accept(requestedScene));
        preferences.getSignalSetting("Follow playing scene", "Clip selection", "Capture")
                .addSignalObserver(() -> follow.run());
    }

    void bind(IntConsumer select, Runnable follow) { this.select = select; this.follow = follow; }
    void selected(int absoluteScene) { scene.setRaw(absoluteScene + 1.0); }
}
