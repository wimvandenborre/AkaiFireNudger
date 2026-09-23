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
    private final LogicalStepIndex noteIndex = new LogicalStepIndex();
    private long pendingSince;
    private boolean pollScheduled;
    private String lastMapping = "";
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
        this(host,track,coarse,diagnostic,"FIRE_FINE");
    }

    FineNudge(ControllerHost host, CursorTrack track, PinnableCursorClip coarse, Consumer<String> diagnostic, String id) {
        this.host = host;
        this.diagnostic = diagnostic;
        this.coarse = coarse;
        fine = track.createLauncherCursorClip(id, "Fire fine timing", WINDOW, 1);
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
        noteIndex.clear();
        pendingSince = 0;
        pollScheduled = false;
        notesDirty = true;
        long ticket = ++generation;
        if (pitch < 0 || pitch > 127) return;
        fine.scrollToKey(pitch);
        fine.scrollToStep((int) Math.round(coarse.getLoopStart().get() / STEP_BEATS));
        host.scheduleTask(() -> {
            if (ticket == generation) { settled = true; positioned = true; notesDirty = true; }
        }, 100);
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

    /** Refresh from a whole fine-cursor snapshot, rather than trusting mutable cached NoteSteps. */
    private void syncIndex() {
        if (!notesDirty || !sameClip()) return;
        Map<LogicalStepIndex.Address, NoteStep> actual = new LinkedHashMap<>();
        int length = Math.min(WINDOW, (int) Math.round(coarse.getLoopLength().get() / STEP_BEATS));
        for (int channel = 0; channel < 16; channel++) for (int x = 0; x < length; x++) {
            NoteStep note = fine.getStep(channel, x, 0);
            if (note.state() == NoteStep.State.NoteOn) {
                actual.put(new LogicalStepIndex.Address(channel, x), note);
            }
        }
        if (noteIndex.observe(actual)) pendingSince = 0;
        else if (System.nanoTime() / 1_000_000 - pendingSince > 1000) {
            diagnostic.accept("NUDGE_RESYNC actual=" + actual.keySet());
            noteIndex.resync(actual);
            pendingSince = 0;
        }
        notesDirty = false;
        mappedResolution = -1;
    }

    /** Keep projected occupancy visible until Bitwig confirms every changed fine cell. */
    private void changed() {
        if (pendingSince == 0) pendingSince = System.nanoTime() / 1_000_000;
        notesDirty = true;
        mappedResolution = -1;
        if (pollScheduled) return;
        pollScheduled = true;
        long ticket = generation;
        host.scheduleTask(() -> {
            if (ticket != generation) return;
            pollScheduled = false;
            notesDirty = true;
            syncIndex();
            if (noteIndex.pending()) changed();
            else pendingSince = 0;
        }, 30);
    }

    boolean editsReady(double resolution) {
        if (!sameClip()) return false;
        if (!mapsGrid(resolution)) return true; // legacy view for unsupported grid geometry
        syncIndex();
        return !noteIndex.pending();
    }

    /** Null means unsupported/unsettled; an empty list means an actually empty page. */
    List<NoteStep> pageNotes(double resolution, int pageOffset) {
        if (!mapsGrid(resolution)) return null;
        syncIndex();
        if (mappedResolution != resolution || mappedPage != pageOffset) {
            List<NoteStep> notes = new ArrayList<>();
            for (LogicalStepIndex.Note note : noteIndex.notes()) {
                int x = note.address().step();
                int slot = logicalStep(x, resolution, pageOffset);
                if (slot >= 0 && slot < 32) {
                    notes.add(new LogicalNoteStep(note.source(), slot, x, note.address().channel()));
                }
            }
            mappedNotes = List.copyOf(notes);
            mappedResolution = resolution;
            mappedPage = pageOffset;
            String mapping = "pitch=" + pitch + " grid=" + resolution + " page=" + pageOffset + " "
                    + notes.stream().map(n -> n.channel() + ":" + ((LogicalNoteStep) n).fineStep + "->" + n.x()).toList();
            if (!mapping.equals(lastMapping)) { diagnostic.accept("STEP_MAP " + mapping); lastMapping = mapping; }
        }
        return mappedNotes;
    }

    private int anchorStep(int slot, double resolution, int pageOffset) {
        return (int) Math.round(((pageOffset + slot) * resolution - coarse.getLoopStart().get()) / STEP_BEATS);
    }

    void setStep(int channel, int slot, int velocity, double duration, double resolution, int pageOffset) {
        if (!mapsGrid(resolution)) { coarse.setStep(channel, slot, 0, velocity, duration); return; }
        clearStep(channel, slot, resolution, pageOffset);
        int x = anchorStep(slot, resolution, pageOffset);
        fine.setStep(channel, x, 0, velocity, duration);
        noteIndex.put(new LogicalStepIndex.Address(channel, x), fine.getStep(channel, x, 0));
        changed();
    }

    void clearStep(int channel, int slot, double resolution, int pageOffset) {
        List<NoteStep> notes = pageNotes(resolution, pageOffset);
        if (notes == null) { coarse.clearStep(channel, slot, 0); return; }
        for (NoteStep note : notes) if (note.channel() == channel && note.x() == slot) {
            int x = ((LogicalNoteStep) note).fineStep;
            fine.clearStep(channel, x, 0);
            noteIndex.remove(new LogicalStepIndex.Address(channel, x));
        }
        // Clear a just-created grid note even before its NoteOn observer arrives.
        int anchor = anchorStep(slot, resolution, pageOffset);
        fine.clearStep(channel, anchor, 0);
        noteIndex.remove(new LogicalStepIndex.Address(channel, anchor));
        changed();
    }

    void logPad(int slot, boolean pressed, double resolution, int pageOffset) {
        List<NoteStep> notes = pageNotes(resolution, pageOffset);
        diagnostic.accept("STEP_INPUT slot=" + slot + " pressed=" + pressed + " mapped=" + (notes != null)
                + " pending=" + noteIndex.pending() + " occupants=" + (notes == null ? "coarse" :
                notes.stream().filter(n -> n.x() == slot).map(n -> n.channel() + ":" + ((LogicalNoteStep) n).fineStep).toList()));
    }

    boolean wasLimited() { return limited; }

    int move(int direction, boolean heldOnly, IntPredicate include, double resolution) {
        limited = false;
        if (!settled || !editsReady(resolution)) {
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
            for (LogicalStepIndex.Note note : noteIndex.notes()) {
                if (note.address().channel() == channel) occupied.add(note.address().step());
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
                noteIndex.move(new LogicalStepIndex.Address(channel, move.from()),
                        new LogicalStepIndex.Address(channel, move.to()));
                int offset = gestureOffsets.remove(channel * WINDOW + move.from());
                gestureOffsets.put(channel * WINDOW + move.to(), offset + Integer.signum(direction));
                moved++;
                if (heldOnly) {
                    selected.remove(move.from());
                    selected.add(move.to());
                }
            }
        }
        changed();
        diagnostic.accept("NUDGE pitch=" + pitch + " direction=" + direction + " observed=" + observed + " moved=" + moved);
        // Avoid applying another move against pre-edit observation data.
        settled = false;
        long ticket = generation;
        host.scheduleTask(() -> { if (ticket == generation) { settled = true; notesDirty = true; } }, 50);
        return moved;
    }

    int pitch() { return pitch; }

    /** Groove uses the same movement and pending pad-index projection as manual nudge. */
    void moveAbsolute(int channel, int from, int to, double resolution) {
        if (!settled || !mapsGrid(resolution) || !editsReady(resolution))
            throw new IllegalStateException("Fine nudge is not ready");
        int length = (int)Math.round(coarse.getLoopLength().get()/STEP_BEATS);
        if (from < 0 || to < 0 || from >= length || to >= length || from == to
                || fine.getStep(channel, from, 0).state() != NoteStep.State.NoteOn
                || fine.getStep(channel, to, 0).state() == NoteStep.State.NoteOn)
            throw new IllegalStateException("Source/destination changed");
        fine.moveStep(channel, from, 0, to-from, 0);
        noteIndex.move(new LogicalStepIndex.Address(channel, from), new LogicalStepIndex.Address(channel, to));
        resetSelection();
        changed();
    }

    /** A stable lock read supersedes expectations for notes the user has since added/deleted. */
    void acceptExternalSnapshot() {
        noteIndex.clear();pendingSince=0;notesDirty=true;mappedResolution=-1;
        resetSelection();syncIndex();
    }

    /** Mirror a private groove worker's edit into the visible Fire cursor's pending index. */
    void mirrorMove(int channel, int from, int to, double resolution, Runnable write) {
        boolean mapped=mapsGrid(resolution);
        if(mapped)syncIndex();
        var address=new LogicalStepIndex.Address(channel,from);
        boolean present=mapped && noteIndex.notes().stream().anyMatch(n->n.address().equals(address));
        write.run();
        if(present) {
            noteIndex.move(address,new LogicalStepIndex.Address(channel,to));
            resetSelection();changed();
        }
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
