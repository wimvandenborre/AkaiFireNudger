package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.NoteInput;
import com.bitwig.extension.controller.api.Track;
import java.util.Arrays;
import java.util.function.Consumer;

/** Explicit rack notes: clip cursors, input monitoring and hardware translation are unrelated. */
final class DrumPadNoteInput {
    private final Track rack;
    private final boolean group;
    private final Consumer<String> diagnostic;
    private final int[] sounding = new int[16];
    private final boolean[] repeating = new boolean[16];
    private final boolean[] noteOn = new boolean[16];
    private final NoteRepeatInput repeatInput;
    private final long[] generation = new long[16];
    private boolean active;
    private boolean ready;
    private boolean enabled;
    private int offset = 36;

    DrumPadNoteInput(NoteInput input, Track rack, boolean group, NoteRepeatInput repeatInput, Consumer<String> diagnostic) {
        this.repeatInput = repeatInput;
        this.rack = rack;
        this.group = group;
        this.diagnostic = diagnostic;
        Arrays.fill(sounding, -1);
        Integer[] blocked = new Integer[128];
        Arrays.fill(blocked, -1);
        // Keep physical input silent until BROWSER enables the native mapping.
        // Select audition alone stays on the confirmed direct rack API.
        input.setKeyTranslationTable(blocked);
        input.setShouldConsumeEvents(false);
        // Preserve the pre-multiclip NoteInput behavior, including its normal
        // All Inputs path, alongside the explicit rack connection.
        input.includeInAllInputs().set(true);
    }

    NoteRepeatInput repeatInput() { return repeatInput; }

    void activate() {
        active = true;
        if (!group) rackReady();
    }

    void rackReady() {
        if (!active) return;
        stopAll();
        ready = rack.exists().get();
        if (ready) repeatInput.attach();
        repeatInput.context(active && enabled && ready, offset);
        diagnostic.accept("PAD_AUDITION_TARGET ready=" + ready + " name=" + rack.name().get());
    }

    void suspend() {
        stopAll();
        repeatInput.detach();
        ready = false;
    }

    void deactivate() {
        suspend();
        active = false;
        enabled = false;
    }

    void update(boolean enabled, int offset) {
        if (!enabled || offset != this.offset) stopAll();
        this.enabled = enabled;
        this.offset = offset;
        repeatInput.context(active && enabled && ready, offset);
    }

    void pad(int index, boolean pressed, int velocity, boolean repeat) {
        if (index < 0 || index >= sounding.length) return;
        if (!pressed) { stop(index); return; }
        if (!active || !enabled || !ready || !rack.exists().get()) {
            diagnostic.accept("PAD_AUDITION_SKIP pad=" + index + " active=" + active
                    + " enabled=" + enabled + " ready=" + ready + " exists=" + rack.exists().get());
            return;
        }
        // When repeat is active the hardware event is already processed by the
        // native engine. Sending it again here would double-trigger the note.
        if (repeat) return;
        int pitch = offset + index;
        if (pitch < 0 || pitch > 127) return;
        stop(index);
        sounding[index] = pitch;
        repeating[index] = repeat;
        fire(index, velocity);
        diagnostic.accept("PAD_AUDITION_ON pitch=" + pitch + " velocity=" + velocity
                + " repeat=" + repeat + " target=" + rack.name().get());
    }

    private void stop(int index) {
        generation[index]++;
        int pitch = sounding[index];
        if (pitch < 0) return;
        release(index);
        sounding[index] = -1;
        diagnostic.accept("PAD_AUDITION_OFF pitch=" + pitch + " repeat=" + repeating[index]);
    }

    private void fire(int index, int velocity) {
        release(index);
        rack.startNote(sounding[index], velocity);
        noteOn[index] = true;
    }

    private void release(int index) {
        if (noteOn[index]) rack.stopNote(sounding[index], 0);
        noteOn[index] = false;
    }

    void stopAll() {
        repeatInput.release();
        for (int i = 0; i < sounding.length; i++) stop(i);
    }

    Runnable testC1(int velocity) {
        if (!active || !ready || !rack.exists().get()) return null;
        stopAll();
        sounding[0] = 36;
        repeating[0] = false;
        fire(0, velocity);
        diagnostic.accept("PAD_AUDITION_TEST C1 target=" + rack.name().get());
        long ticket = generation[0];
        return () -> { if (generation[0] == ticket) stop(0); };
    }
}
