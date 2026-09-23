package com.akai.fire.sequence;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Only a short, unmodified tap should remove an existing step. */
final class StepHoldGesture {
    static final long HOLD_MILLIS = 250;
    private final LongSupplier clock;
    private final Map<Integer, Long> pressedAt = new HashMap<>();

    StepHoldGesture() { this(() -> System.nanoTime() / 1_000_000); }
    StepHoldGesture(LongSupplier clock) { this.clock = clock; }

    void press(int step) { pressedAt.putIfAbsent(step, clock.getAsLong()); }

    boolean releaseIsTap(int step) {
        Long start = pressedAt.remove(step);
        return start != null && clock.getAsLong() - start < HOLD_MILLIS;
    }

    boolean releaseIsTap(int step, boolean modified) {
        boolean tap=releaseIsTap(step); // Always consume the press, including modified gestures.
        return tap&&!modified;
    }

    void clear() { pressedAt.clear(); }
}
