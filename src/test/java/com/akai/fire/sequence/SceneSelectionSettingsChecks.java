package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.*;
import com.bitwig.extension.callback.DoubleValueChangedCallback;
import com.bitwig.extension.callback.NoArgsCallback;
import java.lang.reflect.Proxy;
import java.util.*;

/** Preference restore is inert; only the two explicit actions can change selection. */
public final class SceneSelectionSettingsChecks {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        List<String> settings = new ArrayList<>();
        Map<String, NoArgsCallback> actions = new HashMap<>();
        DoubleValueChangedCallback[] numberObserver = {null};
        double[] displayed = {1};
        Preferences preferences = (Preferences) Proxy.newProxyInstance(Preferences.class.getClassLoader(),
                new Class<?>[]{Preferences.class}, (p, method, a) -> {
                    settings.add(a[1] + ":" + a[0]);
                    if (method.getName().equals("getNumberSetting")) {
                        return Proxy.newProxyInstance(SettableRangedValue.class.getClassLoader(),
                                new Class<?>[]{SettableRangedValue.class}, (q, op, values) -> {
                                    switch (op.getName()) {
                                        case "addRawValueObserver": numberObserver[0] = (DoubleValueChangedCallback) values[0]; return null;
                                        case "setRaw": displayed[0] = (double) values[0]; numberObserver[0].valueChanged(displayed[0]); return null;
                                        case "get", "getRaw": throw new AssertionError("No unobserved preference reads");
                                        default: throw new AssertionError(op.getName());
                                    }
                                });
                    }
                    if (method.getName().equals("getSignalSetting")) {
                        return Proxy.newProxyInstance(Signal.class.getClassLoader(), new Class<?>[]{Signal.class},
                                (q, op, values) -> {
                                    actions.put((String) a[0], (NoArgsCallback) values[0]); return null;
                                });
                    }
                    throw new AssertionError(method.getName());
                });
        int[] unavailable = {0}, captures = {0};
        List<Integer> selections = new ArrayList<>();
        SceneSelectionSettings controls = new SceneSelectionSettings(preferences, () -> unavailable[0]++);
        check(settings.equals(List.of("Clip selection:Scene (1-based)", "Clip selection:Select scene clips",
                "Clip selection:Follow playing scene")), "scene controls are registered together in their top section");
        numberObserver[0].valueChanged(65);
        check(selections.isEmpty() && captures[0] == 0 && unavailable[0] == 0, "restoring or changing the scene number alone never selects or launches");
        actions.get("Select scene clips").call();
        check(unavailable[0] == 1, "selected-track mode explains that group-child mode is required");
        controls.bind(selections::add, () -> captures[0]++);
        actions.get("Select scene clips").call();
        check(selections.equals(List.of(65)), "Select passes the exact one-based scene to the group controller");
        actions.get("Follow playing scene").call();
        check(captures[0] == 1 && selections.size() == 1, "Capture shares the Metronome playing-scene action");
        controls.selected(32);
        check(displayed[0] == 33 && selections.size() == 1 && captures[0] == 1, "successful capture updates the displayed scene without recursively selecting");
        actions.get("Select scene clips").call();
        check(selections.equals(List.of(65, 33)), "the displayed scene and subsequent manual selection agree");
        System.out.println("Scene setting checks passed: top section, inert restore, explicit Select/Capture, one-based scene and readback without recursion.");
    }
}
