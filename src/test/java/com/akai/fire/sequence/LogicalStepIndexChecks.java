package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.NoteStep;
import java.lang.reflect.Proxy;
import java.util.*;

/** Exercise partial/delayed observer frames and mutable Bitwig note proxies. */
public final class LogicalStepIndexChecks {
    public static void main(String[] args) {
        NoteStep.State[] state = {NoteStep.State.NoteOn};
        NoteStep source = (NoteStep) Proxy.newProxyInstance(NoteStep.class.getClassLoader(),
                new Class<?>[]{NoteStep.class}, (p, method, values) -> {
                    if (method.getName().equals("state")) return state[0];
                    if (method.getName().equals("x")) return 16;
                    if (method.getName().equals("channel")) return 2;
                    throw new AssertionError(method);
                });
        LogicalStepIndex index = new LogicalStepIndex();
        var neighbour = new LogicalStepIndex.Address(2, 0);
        var from = new LogicalStepIndex.Address(2, 16);
        var early = new LogicalStepIndex.Address(2, 15);
        index.observe(Map.of(neighbour, source, from, source));
        index.move(from, early);
        state[0] = NoteStep.State.Empty; // Bitwig mutates the old source proxy after moving it
        LogicalNoteStep pad = new LogicalNoteStep(source, 1, 15, 2);
        check(pad.state() == NoteStep.State.NoteOn && pad.x() == 1 && pad.fineStep == 15,
                "pad identity does not inherit old source becoming Empty");
        check(!index.observe(Map.of(neighbour, source)), "partial removal frame must not erase moving note");
        check(index.pending() && addresses(index).equals(Set.of(neighbour, early)), "projected occupancy survives delayed destination");
        check(!index.observe(Map.of(neighbour, source, from, source, early, source)), "source and destination must both confirm");
        check(index.observe(Map.of(neighbour, source, early, source)) && !index.pending(), "complete frame confirms once");
        index.move(early, from);
        check(!index.observe(Map.of(neighbour, source, early, source)), "stale reverse frame cannot duplicate note");
        check(addresses(index).equals(Set.of(neighbour, from)), "reverse nudge retains two notes");
        index.observe(Map.of(neighbour, source, from, source));
        index.remove(from);
        check(!index.observe(Map.of(neighbour, source, from, source)), "stale deletion frame cannot relight note");
        check(addresses(index).equals(Set.of(neighbour)), "only intended slot is deleted");
        check(index.observe(Map.of(neighbour, source)), "deletion acknowledged");
        index.put(from, source);
        check(!index.observe(Map.of(neighbour, source)) && addresses(index).contains(from), "pending creation stays occupied");
        index.clear();
        check(!index.pending() && index.notes().isEmpty(), "track or scene switch drops pending identities");
        System.out.println("Logical index checks passed: mutable proxies, delayed and partial move frames, reverse moves, create/delete confirmation and context reset.");
    }
    static Set<LogicalStepIndex.Address> addresses(LogicalStepIndex index) {
        Set<LogicalStepIndex.Address> result = new HashSet<>();
        for (var note : index.notes()) result.add(note.address());
        return result;
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
