package com.akai.fire.sequence;

import java.util.function.IntConsumer;

/** A reversible Euclidean overlay. Notes present before the overlay are never edited. */
final class EuclideanPattern {
    private final boolean[] protectedSteps;
    private final boolean[] generatedSteps;
    private final boolean[] pendingRemoval;
    private int pulses;
    private int rotation;

    EuclideanPattern(final boolean[] occupied) {
        protectedSteps = occupied.clone();
        generatedSteps = new boolean[occupied.length];
        pendingRemoval = new boolean[occupied.length];
    }

    int getPulses() {
        return pulses;
    }

    /** Transfer an explicitly edited step out of generator ownership without resetting pulses. */
    void manualStep(final int step, final boolean present) {
        if (step < 0 || step >= generatedSteps.length) {
            return;
        }
        protectedSteps[step] = present;
        generatedSteps[step] = false;
        // Ignore an old NoteOn observation after an explicit manual deletion.
        pendingRemoval[step] = !present;
    }

    static boolean[] pattern(final int steps, final int pulses) {
        if (steps < 1 || pulses < 0 || pulses > steps) {
            throw new IllegalArgumentException("Expected 0 <= pulses <= steps, with steps > 0");
        }
        final boolean[] result = new boolean[steps];
        for (int hit = 0; hit < pulses; hit++) {
            // Round upward: E(3,16) = 1,7,12; E(5,16) = 1,5,8,11,14.
            result[(hit * steps + pulses - 1) / pulses] = true;
        }
        return result;
    }

    void turn(final int increment, final boolean[] occupied,
              final IntConsumer add, final IntConsumer remove) {
        final int next = Math.max(0, Math.min(generatedSteps.length, pulses + increment));
        if (next == pulses) {
            return;
        }
        apply(next, rotation, occupied, add, remove);
    }

    void rotate(final int rotation, final boolean[] occupied,
                final IntConsumer add, final IntConsumer remove) {
        final int nextRotation = Math.floorMod(rotation, generatedSteps.length);
        if (nextRotation != this.rotation) {
            apply(pulses, nextRotation, occupied, add, remove);
        }
    }

    private void apply(final int next, final int rotation, final boolean[] occupied,
                       final IntConsumer add, final IntConsumer remove) {
        if (occupied.length != generatedSteps.length) {
            throw new IllegalArgumentException("Step count changed during overlay");
        }
        final boolean[] base = pattern(generatedSteps.length, next);
        final boolean[] pattern = new boolean[base.length];
        for (int step = 0; step < base.length; step++) {
            pattern[(step + rotation) % base.length] = base[step];
        }
        for (int step = 0; step < pattern.length; step++) {
            // Also protect notes added elsewhere after the overlay began.
            if (!occupied[step]) {
                pendingRemoval[step] = false;
            }
            if (occupied[step] && !generatedSteps[step] && !pendingRemoval[step]) {
                protectedSteps[step] = true;
            }
            final boolean wanted = pattern[step] && !protectedSteps[step];
            if (generatedSteps[step] && !wanted) {
                remove.accept(step);
                pendingRemoval[step] = true;
            } else if (!generatedSteps[step] && wanted) {
                add.accept(step);
                pendingRemoval[step] = false;
            }
            // Track our own writes immediately; DAW observers can arrive later.
            generatedSteps[step] = wanted;
        }
        pulses = next;
        this.rotation = rotation;
    }
}
