package com.akai.fire.sequence;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** Velocity-only, reversible edit overlay. Contains no note creation/deletion operations. */
final class VelocityGroove {
    static final String[] NAMES = {"Agogo", "Timbales", "Congas", "Bongo"};
    // Bar heights transcribed from Torso's published 16-point illustrations, not firmware presets.
    // https://docs.torsoelectronics.com/t1/parameter-reference/groove/accent-groove/
    private static final int[][] HEIGHTS = {
        {73,21,68,21,102,116,21,68,21,68,21,73,21,116,102,21},
        {37,20,116,48,37,31,64,37,95,48,31,37,78,48,20,31},
        {30,41,75,25,41,41,116,34,25,30,85,52,99,41,116,19},
        {32,72,38,46,101,25,32,72,25,46,59,116,46,54,116,25}
    };
    private static final class Sample {
        double base, observed, output;
        final List<Double> pending = new ArrayList<>();
        Sample(double velocity) { base = observed = output = velocity; }
    }
    private final Map<Integer, Sample> samples = new HashMap<>();
    private int shape;
    private int amount;

    int shape() { return shape; }
    int amount() { return amount; }
    void turnShape(int delta) { shape = Math.floorMod(shape + delta, NAMES.length); }
    void turnAmount(int delta) { amount = Math.max(0, Math.min(100, amount + delta)); }
    void remove(int key) { samples.remove(key); }

    double apply(int key, int phase, double actual) {
        Sample sample = samples.computeIfAbsent(key, ignored -> new Sample(actual));
        // Repeated turns may arrive before Bitwig echoes the previous write.
        // An independent velocity edit becomes the new baseline.
        int acknowledged = -1;
        for (int i = 0; i < sample.pending.size(); i++) {
            if (near(actual, sample.pending.get(i))) acknowledged = i;
        }
        if (acknowledged >= 0) {
            sample.pending.subList(0, acknowledged + 1).clear();
        } else if (!near(actual, sample.output) && !near(actual, sample.observed)) {
            sample.base = actual;
            sample.pending.clear();
        }
        sample.observed = actual;
        sample.output = velocity(shape, amount, phase, sample.base);
        sample.pending.add(sample.output);
        return sample.output;
    }

    int newNote(int key, int phase, int baseline) {
        samples.put(key, new Sample(baseline / 127.0));
        return (int) Math.round(apply(key, phase, baseline / 127.0) * 127);
    }

    static double velocity(int shape, int amount, int phase, double baseline) {
        // Bipolar variation around each note's original velocity; keep MIDI notes audible.
        double contour = HEIGHTS[shape][Math.floorMod(phase, 16)] / 116.0 * 2 - 1;
        return Math.max(1.0 / 127, Math.min(1, baseline + contour * amount / 100.0));
    }

    private static boolean near(double a, double b) { return Math.abs(a - b) < 0.5 / 127; }
}
