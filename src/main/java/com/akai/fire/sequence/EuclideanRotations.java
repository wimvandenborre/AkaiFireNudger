package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.DocumentState;
import com.bitwig.extension.controller.api.SettableRangedValue;

/** Project-owned offsets keyed by absolute drum MIDI note, independent of device identity. */
final class EuclideanRotations {
    private static final int FIRST_NOTE = 36;
    private static final int LAST_NOTE = 52;

    private final int[] rotations = new int[128];
    private final SettableRangedValue[] settings = new SettableRangedValue[128];

    EuclideanRotations(final DocumentState document) {
        for (int note = FIRST_NOTE; note <= LAST_NOTE; note++) {
            final int index = note;
            settings[note] = document.getNumberSetting("Note " + note,
                    "Euclidean pad rotation", 0, 31, 1, "steps", 0);
            settings[note].addRawValueObserver(value -> rotations[index] = (int) Math.round(value));
        }
    }

    static boolean supports(final int note) {
        return note >= FIRST_NOTE && note <= LAST_NOTE;
    }

    int get(final int note) {
        return supports(note) ? rotations[note] : 0;
    }

    int turn(final int note, final int increment, final int steps) {
        if (!supports(note)) {
            return 0;
        }
        final int rotation = Math.floorMod(rotations[note] + increment, steps);
        rotations[note] = rotation;
        settings[note].setRaw(rotation);
        return rotation;
    }
}
