package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.NoteStep;
import java.util.*;

/** Stable note occupancy between asynchronous Bitwig observations and Fire edits. */
final class LogicalStepIndex {
    record Address(int channel, int step) {}
    record Note(Address address, NoteStep source) {}
    private Map<Address, NoteStep> notes = new LinkedHashMap<>();
    private final Map<Address, Boolean> expected = new HashMap<>();

    void clear() { notes.clear(); expected.clear(); }
    boolean pending() { return !expected.isEmpty(); }
    List<Note> notes() {
        return notes.entrySet().stream().map(e -> new Note(e.getKey(), e.getValue())).toList();
    }

    /** Do not expose an intermediate empty source / missing destination as an empty pad. */
    boolean observe(Map<Address, NoteStep> actual) {
        for (var entry : expected.entrySet()) {
            if (actual.containsKey(entry.getKey()) != entry.getValue()) return false;
        }
        notes = new LinkedHashMap<>(actual);
        expected.clear();
        return true;
    }

    void move(Address from, Address to) {
        NoteStep source = notes.remove(from);
        if (source == null) throw new IllegalStateException("Moving an unknown note");
        if (notes.containsKey(to)) throw new IllegalStateException("Overwriting a note");
        notes.put(to, source);
        expected.put(from, false);
        expected.put(to, true);
    }

    void remove(Address address) {
        notes.remove(address);
        expected.put(address, false);
    }

    void put(Address address, NoteStep source) {
        notes.put(address, source);
        expected.put(address, true);
    }

    /** Fallback after a rejected DAW edit, using actual fine onsets, never coarse cells. */
    void resync(Map<Address, NoteStep> actual) {
        expected.clear();
        notes = new LinkedHashMap<>(actual);
    }
}
