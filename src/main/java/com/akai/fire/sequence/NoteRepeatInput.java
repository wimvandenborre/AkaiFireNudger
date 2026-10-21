package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.Arpeggiator;
import com.bitwig.extension.controller.api.NoteInput;
import com.bitwig.extension.controller.api.Track;
import com.bitwig.extension.controller.api.PlayingNote;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Native engine timing: hardware notes feed the arpeggiator, never a script timer. */
final class NoteRepeatInput {
    private final NoteInput input;
    private final Supplier<Track> target;
    private Track routedTrack;
    private final Arpeggiator arp;
    private final Consumer<String> diagnostic;
    private boolean lastMapped;
    private int lastOffset = -1;
    private boolean active;
    private boolean context;
    private boolean routed;
    private int offset = 36;
    private double rate = 0.25;
    private int testPitch = -1;
    private long testGeneration;
    private boolean testing;

    NoteRepeatInput(NoteInput input, Supplier<Track> target, Consumer<String> diagnostic) {
        this.diagnostic = diagnostic;
        this.input = input;
        this.target = target;
        arp = input.arpeggiator();
        arp.isEnabled().addValueObserver(value -> diagnostic.accept("NATIVE_REPEAT_ENABLED " + value));
        arp.usePressureToVelocity().addValueObserver(value -> diagnostic.accept("NATIVE_REPEAT_PRESSURE_VELOCITY " + value));
        arp.rate().addValueObserver(value -> diagnostic.accept("NATIVE_REPEAT_RATE beats=" + value));
        configure();
        arp.isEnabled().set(false);
        mapping();
    }

    private void configure() {
        arp.mode().set("all");
        arp.octaves().set(0);
        arp.isFreeRunning().set(false);
        arp.usePressureToVelocity().set(false);
        arp.shuffle().set(false);
        arp.humanize().set(0);
        arp.gateLength().set(0.5);
        arp.enableOverlappingNotes().set(false);
        arp.terminateNotesImmediately().set(true);
        arp.rate().set(rate);
    }

    void rate(double rate) { this.rate = rate; arp.rate().set(rate); }

    void velocity(int velocity) {
        Integer[] table = new Integer[128];
        Arrays.fill(table, velocity);
        table[0] = 0;
        input.setVelocityTranslationTable(table);
    }

    void activate() {
        // Rebind after initialization as well: the document's native note source
        // may not exist yet when the rack's first exists observer fires.
        if (routed) attach();
        active = true;
        configure();
        arp.isEnabled().set(true);
        mapping();
    }

    void deactivate() {
        active = false;
        mapping();
        release();
        arp.isEnabled().set(testPitch >= 0);
    }

    void attach() {
        boolean previousContext = context;
        detach();
        Track requested = target.get();
        if (requested == null || !requested.exists().get()) {
            diagnostic.accept("NATIVE_REPEAT_TARGET unavailable");
            return;
        }
        requested.addNoteSource(input);
        routedTrack = requested;
        routed = true;
        context = previousContext;
        // Only read the rack values subscribed during PadHandler initialization.
        // MasterTrack.position is not needed for routing and is not subscribed.
        diagnostic.accept("NATIVE_REPEAT_TARGET name=" + requested.name().get());
        mapping();
    }

    void detach() {
        context = false;
        mapping();
        release();
        cancelTest();
        // Keep the attached object: the setting may already point to another track.
        if (routedTrack != null) routedTrack.removeNoteSource(input);
        routedTrack = null;
        routed = false;
    }

    void context(boolean enabled, int offset) {
        if (!enabled || this.offset != offset) release();
        context = enabled;
        this.offset = offset;
        mapping();
    }

    void release() { arp.releaseNotes(); }

    void observeNotes(int lane, PlayingNote[] notes) {
        if (testing) diagnostic.accept("NATIVE_INPUT_OBSERVED lane=" + lane + " notes="
                + Arrays.toString(Arrays.stream(notes).map(n -> n.pitch() + ":" + n.velocity()).toArray()));
    }

    private void mapping() {
        Integer[] table = new Integer[128];
        Arrays.fill(table, -1);
        boolean mapped = active && context && routed && !testing;
        if (mapped != lastMapped || offset != lastOffset) {
            diagnostic.accept("NATIVE_REPEAT_MAP enabled=" + mapped + " offset=" + offset + " routed=" + routed);
            lastMapped = mapped;
            lastOffset = offset;
        }
        if (mapped) {
            for (int pad = 0; pad < 16; pad++) {
                int pitch = offset + pad;
                if (pitch >= 0 && pitch < 128) table[0x36 + pad] = pitch;
            }
        }
        input.setKeyTranslationTable(table);
    }

    /** Isolate native sound/routing from physical-pad handling during diagnosis. */
    Runnable testC1(int velocity) {
        return testC1(velocity, true, 0);
    }

    Runnable testC1(int velocity, boolean repeat, int octaves) {
        if (!routed || !routedTrack.exists().get()) return null;
        cancelTest();
        configure();
        testing = true;
        mapping();
        arp.octaves().set(octaves);
        arp.isEnabled().set(repeat);
        testPitch = 36;
        diagnostic.accept("NATIVE_INPUT_TEST target=" + routedTrack.name().get()
                + " repeat=" + repeat + " octaves=" + octaves + " velocity=" + velocity);
        input.sendRawMidiEvent(0x90, testPitch, velocity);
        long ticket = testGeneration;
        return () -> { if (testGeneration == ticket) cancelTest(); };
    }

    private void cancelTest() {
        testGeneration++;
        if (testPitch >= 0) input.sendRawMidiEvent(0x80, testPitch, 0);
        testPitch = -1;
        testing = false;
        configure();
        arp.isEnabled().set(active);
        mapping();
    }
}
