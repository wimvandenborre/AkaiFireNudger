package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.DocumentState;
import com.bitwig.extension.controller.api.SettableRangedValue;
import com.bitwig.extension.callback.DoubleValueChangedCallback;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Simulates Bitwig project settings being reloaded for a new controller instance. */
public final class EuclideanRotationChecks {
    public static void main(String[] args) {
        Map<String, Double> project = new HashMap<>();
        EuclideanRotations first = new EuclideanRotations(document(project));
        check(project.size() == 17, "Only notes 36 through 52 should have settings");
        check(first.get(42) == 0, "Default rotation");
        first.turn(36, 1, 16);
        first.turn(52, 3, 16);
        check(first.get(36) == 1 && first.get(52) == 3, "Inclusive note boundaries");
        check(first.turn(35, 1, 16) == 0 && first.turn(53, 1, 16) == 0,
                "Unsupported notes must not create rotations");
        first.turn(42, 2, 16);
        first.turn(46, -1, 16);
        check(first.get(42) == 2 && first.get(46) == 15, "Separate pad offsets");
        check(first.get(36) == 1, "Kick offset changed");
        EuclideanRotations reopened = new EuclideanRotations(document(project));
        check(reopened.get(52) == 3, "Note 52 restore");
        check(reopened.get(42) == 2 && reopened.get(46) == 15, "Project restore");
        reopened.turn(42, 14, 16);
        check(reopened.get(42) == 0, "Wrap at active length");
        EuclideanRotations newProject = new EuclideanRotations(document(new HashMap<>()));
        check(newProject.get(46) == 0, "Offsets leaked into a different project");
        System.out.println("Rotation settings checks passed: independent pads, project restore, wraparound, project isolation.");
    }

    private static DocumentState document(Map<String, Double> values) {
        return (DocumentState) Proxy.newProxyInstance(DocumentState.class.getClassLoader(),
                new Class<?>[]{DocumentState.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("getNumberSetting")) throw new AssertionError(method);
                    String key = args[1] + "/" + args[0];
                    values.putIfAbsent(key, ((Number) args[6]).doubleValue());
                    DoubleValueChangedCallback[] observer = new DoubleValueChangedCallback[1];
                    return Proxy.newProxyInstance(SettableRangedValue.class.getClassLoader(),
                            new Class<?>[]{SettableRangedValue.class}, (setting, operation, parameters) -> {
                                switch (operation.getName()) {
                                    case "addRawValueObserver":
                                        observer[0] = (DoubleValueChangedCallback) parameters[0];
                                        observer[0].valueChanged(values.get(key));
                                        return null;
                                    case "setRaw":
                                        values.put(key, ((Number) parameters[0]).doubleValue());
                                        observer[0].valueChanged(values.get(key));
                                        return null;
                                    default: throw new AssertionError(operation);
                                }
                            });
                });
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
