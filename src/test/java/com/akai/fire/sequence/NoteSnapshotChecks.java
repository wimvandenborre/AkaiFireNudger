package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.*;
import java.lang.reflect.*;
import java.util.*;

public final class NoteSnapshotChecks {
    public static void main(String[] args) {
        Map<String, Object> source = new HashMap<>();
        source.put("x", 4); source.put("channel", 9);
        source.put("velocity", 0.7); source.put("duration", 0.125);
        source.put("chance", 0.33); source.put("isChanceEnabled", true);
        source.put("occurrence", NoteOccurrence.ALWAYS);
        source.put("recurrenceLength", 4); source.put("recurrenceMask", 5);
        source.put("repeatCount", 3); source.put("isRepeatEnabled", true);
        NoteStep live = (NoteStep) Proxy.newProxyInstance(NoteStep.class.getClassLoader(),
                new Class<?>[]{NoteStep.class}, (p, method, a) -> {
                    if (source.containsKey(method.getName())) return source.get(method.getName());
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return 0.0;
                });
        NoteSnapshot captured = NoteSnapshot.capture(live);
        source.put("velocity", 0.1); source.put("chance", 1.0); source.put("x", 20);
        Map<String, Object> writes = new HashMap<>();
        NoteStep destination = (NoteStep) Proxy.newProxyInstance(NoteStep.class.getClassLoader(),
                new Class<?>[]{NoteStep.class}, (p, method, a) -> {
                    writes.put(method.getName(), a.length == 1 ? a[0] : List.of(a));
                    return null;
                });
        captured.applyTo(destination);
        check(captured.x() == 4 && captured.channel() == 9, "coordinates captured");
        check(writes.get("setVelocity").equals(0.7), "live cursor cannot change copy velocity");
        check(writes.get("setChance").equals(0.33), "chance retained");
        check(writes.get("setIsChanceEnabled").equals(true), "chance enabled retained");
        check(writes.get("setRecurrence").equals(List.of(4, 5)), "recurrence retained");
        check(writes.get("setRepeatCount").equals(3), "repeats retained");
        check(writes.get("setDuration").equals(0.125), "duration retained");
        System.out.println("Note snapshot checks passed: live-cursor isolation, chance, recurrence, repeat, velocity and duration.");
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
