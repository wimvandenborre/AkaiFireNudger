package com.akai.fire.sequence;

import java.util.*;

/** Pure planning: every target is a function of an immutable original, never a preview. */
final class GrooveEngine {
    record Note(String id, double start, double duration, int pitch, int channel,
                Map<String, Object> properties, boolean protectedNote) {
        Note {
            Objects.requireNonNull(id);
            if (!Double.isFinite(start) || !Double.isFinite(duration) || duration <= 0
                    || pitch < 0 || pitch > 127 || channel < 0 || channel > 15) throw new IllegalArgumentException("Invalid note");
            // Values are immutable host scalar snapshots, not mutable NoteStep proxies.
            Map<String,Object> frozen = new LinkedHashMap<>();
            properties.forEach((key,value) -> frozen.put(key, freeze(value)));
            properties = Map.copyOf(frozen);
        }
        private static Object freeze(Object value) {
            if(value instanceof String || value instanceof Boolean || value instanceof Enum<?>
                    || value instanceof Double || value instanceof Float || value instanceof Long
                    || value instanceof Integer || value instanceof Short || value instanceof Byte
                    || value instanceof NoteSnapshot) return value;
            if(value instanceof List<?> values) return values.stream().map(Note::freeze).toList();
            if(value instanceof Map<?,?> values) {
                Map<String,Object> copy=new LinkedHashMap<>();
                values.forEach((k,v)->copy.put((String)k,freeze(v)));return Map.copyOf(copy);
            }
            throw new IllegalArgumentException("Note snapshot contains an unsupported mutable property");
        }
        Note at(double time) { return new Note(id, time, duration, pitch, channel, properties, protectedNote); }
    }
    enum Eligibility { AFFECTED, PROTECTED, OFF_GRID, UNCHANGED }
    record Change(Note original, Note target, long gridIndex, int slot, Eligibility eligibility) {}
    record Plan(List<Change> changes, double periodBeats, boolean continuous, double minDelta, double maxDelta) {
        Plan { changes = List.copyOf(changes); }
        List<Note> notes() { return changes.stream().map(Change::target).toList(); }
        long count(Eligibility state) { return changes.stream().filter(c -> c.eligibility == state).count(); }
    }
    static long nearestEarlier(double position) {
        if (!Double.isFinite(position) || Math.abs(position) > 1e12) throw new IllegalArgumentException("Invalid grid position");
        return (long) Math.ceil(position - .5);
    }
    static Plan plan(List<Note> originals, double loopStart, double loopLength, GrooveSettings settings) {
        return plan(originals, loopStart, loopLength, settings, settings.pattern());
    }
    static Plan plan(List<Note> originals, double loopStart, double loopLength, GrooveSettings s, double[] pattern) {
        if (!Double.isFinite(loopStart) || !Double.isFinite(loopLength) || loopLength <= 0 || pattern.length == 0
                || Arrays.stream(pattern).anyMatch(v -> !Double.isFinite(v))) throw new IllegalArgumentException("Invalid loop/pattern");
        double origin = loopStart - s.alignment(), h = s.h();
        List<Change> changes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        double min = 0, max = 0;
        for (Note note : originals) {
            if (!ids.add(note.id)) throw new IllegalArgumentException("Duplicate frozen note identity");
            long k = nearestEarlier((note.start - origin) / h);
            double grid = origin + k * h;
            int slot = (int) Math.floorMod(k + s.phase(), (long) pattern.length);
            boolean anchor = s.anchors() && Math.floorMod(k, s.base() / 4) == 0;
            Eligibility eligible;
            double target = note.start;
            if (s.bypass()) eligible = Eligibility.UNCHANGED;
            else if (note.protectedNote || anchor) eligible = Eligibility.PROTECTED;
            else if (!s.offGrid() && Math.abs(note.start - grid) > s.captureWindow() * h + 1e-12) eligible = Eligibility.OFF_GRID;
            else {
                target += s.quantize() * (grid - note.start) + s.depth() * s.master() * h * (pattern[slot] + s.bias());
                eligible = Math.abs(target - note.start) > 1e-12 ? Eligibility.AFFECTED : Eligibility.UNCHANGED;
            }
            // Preserve the exact original double for mathematically unchanged notes.
            if (eligible != Eligibility.AFFECTED) target = note.start;
            min = Math.min(min, target - note.start); max = Math.max(max, target - note.start);
            Note desired = note.at(target);
            if (!s.bypass() && !note.protectedNote && eligible != Eligibility.OFF_GRID) {
                desired = s.velocity().apply(desired, k);
                if (!desired.equals(note)) eligible = Eligibility.AFFECTED;
            }
            changes.add(new Change(note, desired, k, slot, eligible));
        }
        double period = period(s, pattern);
        if (!s.bypass() && s.velocity().active()) {
            int velocityPeriod = s.velocity().min() == s.velocity().max() ? 1 : 2;
            period = lcm(Math.max(1, (int)Math.round(period / h)), velocityPeriod) * h;
        }
        return new Plan(changes, period, compatible(loopLength, period)
                && (s.bypass() || s.quantize() == 0 || compatible(loopLength, h)), min, max);
    }
    static boolean compatible(double loop, double period) {
        return period == 0 || Math.abs(loop / period - Math.rint(loop / period)) < 1e-9;
    }
    /** Effective transformation includes anchor masks and quantization, not declared array size. */
    static double period(GrooveSettings s, double[] pattern) {
        if (s.bypass()) return 0;
        int anchorPeriod = s.base() / 4;
        int n = lcm(pattern.length, s.anchors() ? anchorPeriod : 1);
        double[] displacement = new double[n];
        boolean[] quantized = new boolean[n];
        for (int k = 0; k < n; k++) {
            boolean protectedGrid = s.anchors() && k % anchorPeriod == 0;
            displacement[k] = protectedGrid ? 0 : s.depth() * s.master()
                    * (pattern[Math.floorMod(k + s.phase(), pattern.length)] + s.bias());
            quantized[k] = s.quantize() != 0 && !protectedGrid;
        }
        boolean constant = true;
        for (int k = 0; k < n; k++) if (Math.abs(displacement[k] - displacement[0]) > 1e-12 || quantized[k]) constant = false;
        if (constant) {
            // A nonzero 'constant' offset still has a rhythmic capture mask when
            // far-off-grid events are excluded; account for that at the loop seam.
            return !s.offGrid() && s.captureWindow() < .5 && Math.abs(displacement[0]) > 1e-12 ? s.h() : 0;
        }
        for (int p = 1; p <= n; p++) if (n % p == 0) {
            boolean repeats = true;
            for (int k = p; k < n; k++) if (Math.abs(displacement[k] - displacement[k % p]) > 1e-12
                    || quantized[k] != quantized[k % p]) repeats = false;
            if (repeats) return p * s.h();
        }
        return n * s.h();
    }
    private static int lcm(int a, int b) { int x=a, y=b; while(y!=0){int r=x%y; x=y; y=r;} return a/x*b; }
    static double milliseconds(double beats, double bpm) { return beats * 60000 / bpm; }
}
