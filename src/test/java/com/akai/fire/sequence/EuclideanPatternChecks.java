package com.akai.fire.sequence;

import java.util.Arrays;

/** Dependency-free regression suite; run its main method after mvn test-compile. */
public final class EuclideanPatternChecks {
    public static void main(String[] args) {
        // One-based positions transcribed from the sixteen reference photos.
        int[][] photos = {
            {}, {1}, {1,9}, {1,7,12}, {1,5,9,13}, {1,5,8,11,14},
            {1,4,7,9,12,15}, {1,4,6,8,11,13,15}, {1,3,5,7,9,11,13,15},
            {1,3,5,7,9,10,12,14,16}, {1,3,5,6,8,9,11,13,14,16},
            {1,3,4,6,7,9,10,12,13,15,16}, {1,3,4,5,7,8,9,11,12,13,15,16},
            {1,3,4,5,6,8,9,10,11,13,14,15,16}, {1,3,4,5,6,7,8,9,11,12,13,14,15,16},
            {1,3,4,5,6,7,8,9,10,11,12,13,14,15,16}, {1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16}
        };
        for (int pulses = 0; pulses <= 16; pulses++) {
            boolean[] expected = new boolean[16];
            for (int step : photos[pulses]) expected[step - 1] = true;
            check(Arrays.equals(expected, EuclideanPattern.pattern(16, pulses)), "Photo " + pulses);
        }
        for (int steps = 1; steps <= 32; steps++) {
            for (int pulses = 0; pulses <= steps; pulses++) {
                boolean[] pattern = EuclideanPattern.pattern(steps, pulses);
                int count = 0;
                for (boolean hit : pattern) if (hit) count++;
                check(count == pulses, "Pulse count");
            }
            exerciseOverlay(steps, false);
            exerciseOverlay(steps, true);
        }
        delayedObservers();
        manualEditsKeepPulseCount();
        rotationChecks();
        System.out.println("Euclidean tests passed: 16 photos, lengths 1–32, reversibility, original-note protection, delayed observers, manual edits.");
    }

    private static void exerciseOverlay(int steps, boolean withOriginals) {
        boolean[] original = new boolean[steps];
        if (withOriginals) for (int i = 0; i < steps; i += 3) original[i] = true;
        boolean[] notes = original.clone();
        EuclideanPattern overlay = new EuclideanPattern(notes);
        for (int count = 1; count <= steps; count++) {
            turn(overlay, 1, notes, original);
            assertOverlay(notes, original, count);
        }
        turn(overlay, 10, notes, original);
        check(overlay.getPulses() == steps, "Upper limit");
        for (int count = steps - 1; count >= 0; count--) {
            turn(overlay, -1, notes, original);
            assertOverlay(notes, original, count);
        }
        turn(overlay, -10, notes, original);
        check(Arrays.equals(notes, original), "Return to original");
        check(overlay.getPulses() == 0, "Lower limit");
    }

    private static void turn(EuclideanPattern overlay, int inc, boolean[] notes, boolean[] original) {
        overlay.turn(inc, notes.clone(), step -> {
            check(!original[step], "Overwriting an original note");
            notes[step] = true;
        }, step -> {
            check(!original[step], "Deleting an original note");
            notes[step] = false;
        });
    }

    private static void assertOverlay(boolean[] notes, boolean[] original, int count) {
        boolean[] expected = EuclideanPattern.pattern(notes.length, count);
        for (int i = 0; i < notes.length; i++) expected[i] |= original[i];
        check(Arrays.equals(notes, expected), "Overlay at " + count);
    }

    private static void delayedObservers() {
        boolean[] notes = new boolean[16];
        EuclideanPattern overlay = new EuclideanPattern(notes);
        overlay.turn(2, notes.clone(), step -> notes[step] = true, step -> notes[step] = false);
        boolean[] stale = notes.clone();
        overlay.turn(1, stale, step -> notes[step] = true, step -> notes[step] = false);
        overlay.turn(1, stale, step -> notes[step] = true, step -> notes[step] = false);
        overlay.turn(-2, stale, step -> notes[step] = true, step -> notes[step] = false);
        check(Arrays.equals(notes, EuclideanPattern.pattern(16, 2)), "Rapid reversal");
        overlay.turn(-2, stale, step -> notes[step] = true, step -> notes[step] = false);
        check(Arrays.equals(notes, new boolean[16]), "Rapid return to zero");

        // A newly inserted external note is also protected.
        notes[5] = true;
        overlay.turn(16, notes.clone(), step -> notes[step] = true, step -> notes[step] = false);
        overlay.turn(-16, notes.clone(), step -> notes[step] = true, step -> notes[step] = false);
        check(notes[5], "External note protection");
    }

    private static void manualEditsKeepPulseCount() {
        boolean[] notes = new boolean[16];
        boolean[] manual = new boolean[16];
        manual[15] = notes[15] = true;
        EuclideanPattern overlay = new EuclideanPattern(notes);
        turn(overlay, 5, notes, manual);
        boolean[] stale = notes.clone();
        // Add a new note while the five-pulse pattern is running.
        overlay.manualStep(1, true);
        manual[1] = notes[1] = true;
        // A copied/re-added note on a generated hit must also become protected.
        overlay.manualStep(4, true);
        manual[4] = true;
        check(overlay.getPulses() == 5, "Manual additions reset pulse index");
        overlay.turn(1, stale, step -> {
            check(!manual[step], "Overwriting manual note before DAW update");
            notes[step] = true;
        }, step -> {
            check(!manual[step], "Deleting manual note before DAW update");
            notes[step] = false;
        });
        check(overlay.getPulses() == 6, "Next turn must advance from five to six");
        assertOverlay(notes, manual, 6);
        turn(overlay, -1, notes, manual);
        assertOverlay(notes, manual, 5);
        turn(overlay, -5, notes, manual);
        check(Arrays.equals(notes, manual), "Manual additions must survive zero pulses");

        turn(overlay, 3, notes, manual);
        stale = notes.clone();
        // Remove and manually re-add a previously generated hit, before observers catch up.
        overlay.manualStep(6, false);
        notes[6] = false;
        overlay.manualStep(6, true);
        notes[6] = manual[6] = true;
        overlay.turn(-3, stale, step -> notes[step] = true, step -> {
            check(!manual[step], "Deleting manually re-added hit");
            notes[step] = false;
        });
        check(Arrays.equals(notes, manual), "Re-added generated hit is now manual");

        // Explicit manual deletion is still allowed without resetting the pulse count.
        turn(overlay, 5, notes, manual);
        stale = notes.clone();
        overlay.manualStep(1, false);
        notes[1] = manual[1] = false;
        check(overlay.getPulses() == 5, "Manual deletion reset pulse index");
        overlay.turn(-5, stale, step -> notes[step] = true, step -> notes[step] = false);
        check(Arrays.equals(notes, manual), "Manual deletion leaves remaining manual notes intact");
    }

    private static void rotationChecks() {
        for (int length = 1; length <= 32; length++) {
            boolean[] notes = new boolean[length];
            boolean[] manual = new boolean[length];
            notes[length - 1] = manual[length - 1] = true;
            EuclideanPattern overlay = new EuclideanPattern(notes);
            // Preset the start before adding pulses.
            overlay.rotate(2, notes.clone(), step -> notes[step] = true, step -> {
                check(!manual[step], "Rotation deleted manual note");
                notes[step] = false;
            });
            int pulses = Math.min(4, length);
            turn(overlay, pulses, notes, manual);
            for (int rotation = 2; rotation >= -length; rotation--) {
                overlay.rotate(rotation, notes.clone(), step -> {
                    check(!manual[step], "Rotation overwrote manual note");
                    notes[step] = true;
                }, step -> {
                    check(!manual[step], "Rotation removed manual note");
                    notes[step] = false;
                });
                boolean[] base = EuclideanPattern.pattern(length, pulses);
                boolean[] expected = manual.clone();
                for (int step = 0; step < length; step++) {
                    expected[Math.floorMod(step + rotation, length)] |= base[step];
                }
                check(Arrays.equals(notes, expected), "Rotated positions");
                check(overlay.getPulses() == pulses, "Rotation changed pulse count");
            }
            turn(overlay, -pulses, notes, manual);
            check(Arrays.equals(notes, manual), "Rotated overlay did not return to manual notes");
        }
        boolean[] offbeats = new boolean[16];
        EuclideanPattern overlay = new EuclideanPattern(offbeats);
        overlay.rotate(2, offbeats.clone(), step -> offbeats[step] = true, step -> offbeats[step] = false);
        turn(overlay, 4, offbeats, new boolean[16]);
        for (int i = 0; i < 16; i++) check(offbeats[i] == (i % 4 == 2), "Offbeat hi-hat");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
