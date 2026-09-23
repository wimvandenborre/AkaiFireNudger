package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.*;
import java.util.*;
import java.util.function.IntPredicate;
import java.util.function.Consumer;

/** Fine cursor and channel-aware loop nudge adapted from Oikontrol's MulticlipClipController. */
final class FineNudge {
    static final double STEP_BEATS = 1.0 / 64.0;
    static final int WINDOW = 4096;
    private final ControllerHost host;
    private final Consumer<String> diagnostic;
    private final PinnableCursorClip coarse;
    private final PinnableCursorClip fine;
    private int pitch = -1;
    private boolean settled;
    private boolean positioned;
    private boolean notesDirty = true;
    private List<NoteStep> observedNotes = List.of();
    private List<NoteStep> mappedNotes = List.of();
    private double mappedResolution = -1;
    private int mappedPage = -1;
    private boolean limited;
    private long generation;
    private Map<Integer, Set<Integer>> heldTargets;

    private final Map<Integer, Integer> gestureOffsets = new HashMap<>();

    void resetSelection() {
        heldTargets = null;
        gestureOffsets.clear();
    }

    /** Relative to this hold, not an absolute offset from musical grid positions. */
    String offsetText() {
        int min = gestureOffsets.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        int max = gestureOffsets.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return signed(min) + (min == max ? "" : ".." + signed(max)) + "/64 beat";
    }

    private static String signed(int value) { return value > 0 ? "+" + value : Integer.toString(value); }

    FineNudge(ControllerHost host, CursorTrack track, PinnableCursorClip coarse, Consumer<String> diagnostic) {
        this.host = host;
        this.diagnostic = diagnostic;
        this.coarse = coarse;
        fine = track.createLauncherCursorClip("FIRE_FINE", "Fire fine timing", WINDOW, 1);
        fine.setStepSize(STEP_BEATS);
        fine.exists().markInterested();
        fine.getTrack().position().markInterested();
        fine.clipLauncherSlot().sceneIndex().markInterested();
        fine.getLoopStart().markInterested();
        fine.getLoopLength().markInterested();
        fine.addNoteStepObserver(note -> notesDirty = true);
        coarse.getLoopLength().addValueObserver(value -> refresh());
        coarse.getLoopStart().addValueObserver(value -> refresh());
        coarse.clipLauncherSlot().sceneIndex().addValueObserver(value -> refresh());
        coarse.getTrack().position().addValueObserver(value -> refresh());
    }

    PinnableCursorClip clip() { return fine; }

    void focus(int note) {
        pitch = note;
        refresh();
    }

    private void refresh() {
        resetSelection();
        settled = false;
        positioned = false;
        notesDirty = true;
        long ticket = ++generation;
        if (pitch < 0 || pitch > 127) return;
        fine.scrollToKey(pitch);
        fine.scrollToStep((int) Math.round(coarse.getLoopStart().get() / STEP_BEATS));
        host.scheduleTask(() -> { if (ticket == generation) { settled = true; positioned = true; } }, 100);
    }

    private boolean sameClip() {
        return positioned && coarse.exists().get() && fine.exists().get()
                && coarse.getTrack().position().get() == fine.getTrack().position().get()
                && coarse.clipLauncherSlot().sceneIndex().get() == fine.clipLauncherSlot().sceneIndex().get()
                && Math.abs(coarse.getLoopLength().get() - fine.getLoopLength().get()) < 1e-8
                && Math.abs(coarse.getLoopStart().get() - fine.getLoopStart().get()) < 1e-8;
    }

    boolean mapsGrid(double resolution) {
        double grid = resolution / STEP_BEATS;
        double length = coarse.getLoopLength().get() / STEP_BEATS;
        return sameClip() && grid >= 1 && aligned(grid) && length >= grid && length <= WINDOW
                && aligned(length / grid) && aligned(coarse.getLoopStart().get() / resolution);
    }

    private static boolean aligned(double value) { return Math.abs(value - Math.rint(value)) < 1e-6; }

    static int nearestSlot(int fineStep, int grid, int loopSteps) {
        return Math.floorMod((int) Math.floor((double) fineStep / grid + 0.5), loopSteps / grid);
    }

    static boolean withinLimit(int fineStep, int direction, int grid) {
        int anchor = (int) Math.floor((double) fineStep / grid + 0.5) * grid;
        int offset = fineStep - anchor;
        int next = offset + Integer.signum(direction);
        // Existing notes outside the limit may return toward their anchor, never farther away.
        return Math.abs(next) <= Math.floor(grid * 0.4) || Math.abs(next) < Math.abs(offset);
    }

    int logicalStep(int fineStep, double resolution, int pageOffset) {
        int grid = (int) Math.round(resolution / STEP_BEATS);
        int length = (int) Math.round(coarse.getLoopLength().get() / STEP_BEATS);
        return nearestSlot(fineStep, grid, length)
                + (int) Math.round(coarse.getLoopStart().get() / resolution) - pageOffset;
    }

    /** Null means unsupported/unsettled; an empty list means an actually empty page. */
    List<NoteStep> pageNotes(double resolution, int pageOffset) {
        if (!mapsGrid(resolution)) return null;
        boolean rebuild = notesDirty || mappedResolution != resolution || mappedPage != pageOffset;
        if (notesDirty) {
            List<NoteStep> notes = new ArrayList<>();
            int length = (int) Math.round(coarse.getLoopLength().get() / STEP_BEATS);
            for (int channel = 0; channel < 16; channel++) for (int x = 0; x < length; x++) {
                NoteStep note = fine.getStep(channel, x, 0);
                if (note.state() == NoteStep.State.NoteOn) notes.add(note);
            }
            observedNotes = notes;
            notesDirty = false;
        }
        if (rebuild) {
            List<NoteStep> notes = new ArrayList<>();
            for (NoteStep note : observedNotes) {
                int slot = logicalStep(note.x(), resolution, pageOffset);
                if (slot >= 0 && slot < 32) notes.add(new LogicalNoteStep(note, slot));
            }
            mappedNotes = notes;
            mappedResolution = resolution;
            mappedPage = pageOffset;
        }
        return mappedNotes;
    }

    private int anchorStep(int slot, double resolution, int pageOffset) {
        return (int) Math.round(((pageOffset + slot) * resolution - coarse.getLoopStart().get()) / STEP_BEATS);
    }

    void setStep(int channel, int slot, int velocity, double duration, double resolution, int pageOffset) {
        if (!mapsGrid(resolution)) { coarse.setStep(channel, slot, 0, velocity, duration); return; }
        clearStep(channel, slot, resolution, pageOffset);
        fine.setStep(channel, anchorStep(slot, resolution, pageOffset), 0, velocity, duration);
        notesDirty = true;
    }

    void clearStep(int channel, int slot, double resolution, int pageOffset) {
        List<NoteStep> notes = pageNotes(resolution, pageOffset);
        if (notes == null) { coarse.clearStep(channel, slot, 0); return; }
        for (NoteStep note : notes) if (note.channel() == channel && note.x() == slot) {
            fine.clearStep(channel, ((LogicalNoteStep) note).source.x(), 0);
        }
        // Clear a just-created grid note even before its NoteOn observer arrives.
        fine.clearStep(channel, anchorStep(slot, resolution, pageOffset), 0);
        notesDirty = true;
    }

    boolean wasLimited() { return limited; }

    int move(int direction, boolean heldOnly, IntPredicate include, double resolution) {
        limited = false;
        if (!settled || !sameClip()) {
            diagnostic.accept("NUDGE_NOT_READY settled=" + settled + " pitch=" + pitch);
            return -1;
        }
        if (!mapsGrid(resolution)) {
            host.showPopupNotification("Fine nudge needs an aligned grid and loop of at most 64 beats");
            return -1;
        }
        int grid = (int) Math.round(resolution / STEP_BEATS);
        int steps = (int) Math.round(coarse.getLoopLength().get() / STEP_BEATS);
        boolean capture = heldOnly && heldTargets == null;
        if (!heldOnly) heldTargets = null;
        else if (capture) heldTargets = new HashMap<>();
        int moved = 0;
        int observed = 0;
        for (int channel = 0; channel < 16; channel++) {
            Set<Integer> occupied = new HashSet<>();
            for (int x = 0; x < steps; x++) {
                if (fine.getStep(channel, x, 0).state() == NoteStep.State.NoteOn) occupied.add(x);
            }
            observed += occupied.size();
            Set<Integer> selected = heldOnly ? heldTargets.computeIfAbsent(channel, key -> new HashSet<>()) : null;
            if (capture) for (int x : occupied) if (include.test(x)) selected.add(x);
            IntPredicate included = heldOnly ? selected::contains : include;
            for (int x : occupied) if (included.test(x)) gestureOffsets.putIfAbsent(channel * WINDOW + x, 0);
            for (int x : occupied) if (included.test(x) && !withinLimit(x, direction, grid)) limited = true;
            for (Move move : plan(occupied, steps, direction,
                    x -> included.test(x) && withinLimit(x, direction, grid))) {
                fine.moveStep(channel, move.from(), 0, move.to() - move.from(), 0);
                int offset = gestureOffsets.remove(channel * WINDOW + move.from());
                gestureOffsets.put(channel * WINDOW + move.to(), offset + Integer.signum(direction));
                moved++;
                if (heldOnly) {
                    selected.remove(move.from());
                    selected.add(move.to());
                }
            }
        }
        notesDirty = true;
        diagnostic.accept("NUDGE pitch=" + pitch + " direction=" + direction + " observed=" + observed + " moved=" + moved);
        // Avoid applying another move against pre-edit observation data.
        settled = false;
        long ticket = ++generation;
        host.scheduleTask(() -> { if (ticket == generation) settled = true; }, 50);
        return moved;
    }

    record Move(int from, int to) {}

    /** Move into empty destinations first, including across the loop seam. Never overwrite a note. */
    static List<Move> plan(Set<Integer> occupied, int length, int direction, IntPredicate include) {
        if (length < 1 || direction == 0) return List.of();
        int delta = Integer.signum(direction);
        Set<Integer> remaining = new TreeSet<Integer>(delta > 0 ? Comparator.reverseOrder() : Comparator.naturalOrder());
        Set<Integer> blocked = new HashSet<>(occupied);
        for (int step : occupied) if (step >= 0 && step < length && include.test(step)) remaining.add(step);
        List<Move> moves = new ArrayList<>();
        boolean progress;
        do {
            progress = false;
            for (int source : new ArrayList<>(remaining)) {
                int destination = Math.floorMod(source + delta, length);
                if (blocked.contains(destination)) continue;
                moves.add(new Move(source, destination));
                remaining.remove(source);
                blocked.remove(source);
                blocked.add(destination);
                progress = true;
            }
        } while (progress);
        return moves;
    }
}
