package com.akai.fire.sequence;

import java.util.LinkedHashMap;
import static com.akai.fire.sequence.GrooveEngine.Note;

/** Optional extension accent profile, independent of the Logic timing templates. */
record GrooveVelocity(boolean enabled, int amount, int min, int max, int phase) {
    GrooveVelocity {
        if (amount < -100 || amount > 100 || min < 1 || max > 127 || min > max || phase < 0 || phase > 1)
            throw new IllegalArgumentException("Invalid groove velocity range/amount/phase");
    }
    static GrooveVelocity defaults() { return new GrooveVelocity(false,0,70,120,0); }
    boolean active() { return enabled && amount != 0; }
    double transform(double original, long gridIndex) {
        if (!active()) return original; // Preserve the exact host double, without MIDI rounding.
        if (!Double.isFinite(original) || original < 0 || original > 1)
            throw new IllegalArgumentException("Invalid original velocity");
        boolean strong = Math.floorMod(gridIndex + phase, 2) == 0;
        if (amount < 0) strong = !strong;
        double target = strong ? max : min;
        long midi = Math.round(original * 127 + Math.abs(amount) / 100.0 * (target - original * 127));
        return Math.max(1,Math.min(127,midi)) / 127.0;
    }
    Note apply(Note original, long gridIndex) {
        return active() ? with(original,transform(value(original),gridIndex)) : original;
    }
    static boolean hasVelocity(Note note) {
        return note.properties().get("scalarSnapshot") instanceof NoteSnapshot
                || note.properties().get("velocity") instanceof Number;
    }
    static double value(Note note) {
        if (note.properties().get("scalarSnapshot") instanceof NoteSnapshot snapshot) return snapshot.velocity();
        if (note.properties().get("velocity") instanceof Number value) return value.doubleValue();
        throw new IllegalArgumentException("Velocity observation unavailable");
    }
    static Note with(Note note, double velocity) {
        var properties = new LinkedHashMap<>(note.properties());
        if (properties.get("scalarSnapshot") instanceof NoteSnapshot snapshot)
            properties.put("scalarSnapshot",snapshot.withVelocity(velocity));
        else if (properties.get("velocity") instanceof Number) properties.put("velocity",velocity);
        else throw new IllegalArgumentException("Velocity observation unavailable");
        return new Note(note.id(),note.start(),note.duration(),note.pitch(),note.channel(),properties,note.protectedNote());
    }
}
