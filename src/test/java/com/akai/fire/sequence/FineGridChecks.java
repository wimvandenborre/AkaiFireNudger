package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.IntConsumer;

/** Regression for early adjacent notes sharing a Bitwig cell but retaining separate Fire slots. */
public final class FineGridChecks {
    static final class Fixture {
        final FineNudgeControllerChecks.ClipApi fine = new FineNudgeControllerChecks.ClipApi();
        final MulticlipTargetChecks.Api coarse = new MulticlipTargetChecks.Api("coarse");
        final FineNudge nudge;
        Fixture(double length, String... notes) {
            MulticlipTargetChecks.tasks.clear();
            for (MulticlipTargetChecks.Api clip : List.of(coarse, fine)) {
                MulticlipTargetChecks.setClip(clip, 2, 1, true);
                clip.node("getLoopStart").value = 4.0;
                clip.node("getLoopLength").value = length;
            }
            fine.notes.addAll(List.of(notes));
            ControllerHost host = new MulticlipTargetChecks.Api("host").proxy(ControllerHost.class);
            CursorTrack track = (CursorTrack) Proxy.newProxyInstance(CursorTrack.class.getClassLoader(),
                    new Class<?>[]{CursorTrack.class}, (p, method, args) -> fine.proxy(PinnableCursorClip.class));
            nudge = new FineNudge(host, track, coarse.proxy(PinnableCursorClip.class), text -> {});
            nudge.focus(38);
            MulticlipTargetChecks.drain();
        }
        List<NoteStep> page(int page) { return nudge.pageNotes(0.25, 16 + page * 32); }
        int move(int dir, int slot) {
            int moved = nudge.move(dir, slot >= 0,
                    x -> slot < 0 || nudge.logicalStep(x, 0.25, 16) == slot, 0.25);
            MulticlipTargetChecks.drain();
            return moved;
        }
        boolean[] occupied() {
            boolean[] result = new boolean[4];
            for (NoteStep note : page(0)) result[note.x()] = true;
            return result;
        }
    }

    public static void main(String[] args) {
        Fixture async = new Fixture(1, "2:0", "2:16");
        async.fine.delayMoves = true;
        check(async.nudge.move(-1, true, x -> async.nudge.logicalStep(x, 0.25, 16) == 1, 0.25) == 1,
                "begin delayed left nudge");
        check(slots(async.page(0)).equals(Set.of(0, 1)) && async.page(0).stream().allMatch(n -> n.state() == NoteStep.State.NoteOn),
                "partial Bitwig frame cannot extinguish held pad");
        check(!async.nudge.editsReady(0.25), "new press cannot create duplicate during pending nudge");
        async.fine.hidden.clear();
        async.fine.delayMoves = false;
        MulticlipTargetChecks.drain();
        check(async.nudge.editsReady(0.25), "acknowledged move enables next gesture");
        async.nudge.resetSelection();
        check(async.move(1, 1) == 1 && async.fine.notes.equals(Set.of("2:0", "2:16")),
                "release and reverse nudge returns same note without a split");

        Fixture f = new Fixture(1, "2:0", "2:16");
        check(f.move(-1, 1) == 1, "second adjacent note moves earlier");
        check(slots(f.page(0)).equals(Set.of(0, 1)), "both adjacent pads stay lit");
        NoteStep second = f.page(0).stream().filter(n -> n.x() == 1).findFirst().orElseThrow();
        second.setVelocity(0.5);
        check(f.fine.edited.equals(Set.of("2:15")), "held velocity edit targets early note, not neighbour");
        f.nudge.clearStep(2, 0, 0.25, 16);
        check(f.fine.notes.equals(Set.of("2:15")), "delete preceding pad preserves early neighbour");
        f.nudge.setStep(2, 0, 100, 0.125, 0.25, 16);
        check(f.fine.notes.equals(Set.of("2:0", "2:15")), "adding preceding pad preserves early neighbour");
        for (int i = 0; i < 20; i++) {
            f.nudge.resetSelection(); // releasing and re-holding must never bypass the cap
            f.move(-1, 1);
        }
        check(f.fine.notes.equals(Set.of("2:0", "2:10")) && f.nudge.wasLimited(), "early cap rounds down to 6/16 steps");
        for (int i = 0; i < 20; i++) { f.nudge.resetSelection(); f.move(1, 0); }
        check(f.fine.notes.equals(Set.of("2:6", "2:10")), "opposing adjacent nudges retain separate slots and gap");
        f.nudge.resetSelection();
        for (int i = 0; i < 30; i++) f.move(1, -1);
        check(f.fine.notes.equals(Set.of("2:6", "2:22")), "Alt whole-loop nudge obeys each note's cap");
        check(slots(f.page(0)).equals(Set.of(0, 1)), "slots remain fixed at both limits");

        Fixture pages = new Fixture(16, "2:511", "5:1023");
        check(slots(pages.page(0)).equals(Set.of(0)), "early loop-start note wraps to first pad");
        check(slots(pages.page(1)).equals(Set.of(0)), "early page-boundary note belongs to next page");
        check(pages.page(1).get(0).channel() == 2, "page boundary selects the correct channel");
        pages.nudge.clearStep(2, 0, 0.25, 48);
        check(pages.fine.notes.equals(Set.of("5:1023")), "page delete uses correct fine coordinate");

        Fixture euclid = new Fixture(1, "2:15", "2:48");
        check(Arrays.equals(euclid.occupied(), new boolean[]{false, true, false, true}), "Euclidean occupancy uses fixed slots without ghost notes");
        EuclideanPattern pattern = new EuclideanPattern(euclid.occupied());
        IntConsumer add = slot -> euclid.nudge.setStep(2, slot, 100, 0.125, 0.25, 16);
        IntConsumer remove = slot -> euclid.nudge.clearStep(2, slot, 0.25, 16);
        pattern.turn(4, euclid.occupied(), add, remove);
        check(euclid.fine.notes.equals(Set.of("2:0", "2:15", "2:32", "2:48")), "Euclidean fill preserves early original timing");
        pattern.rotate(1, euclid.occupied(), add, remove);
        pattern.turn(-4, euclid.occupied(), add, remove);
        check(euclid.fine.notes.equals(Set.of("2:15", "2:48")), "Euclidean return to zero preserves originals");

        generatedNotesRemainOwnedAfterNudging();

        Fixture delayed = new Fixture(1);
        delayed.nudge.setStep(2, 0, 100, 0.125, 0.25, 16);
        delayed.fine.hidden.add("2:0"); // write exists in DAW, NoteOn observation has not arrived
        delayed.nudge.clearStep(2, 0, 0.25, 16);
        check(delayed.fine.notes.isEmpty(), "rapid Euclidean removal clears unobserved generated note");

        // Exhaustively check that every permitted in-range move stays on its original slot.
        for (int grid : new int[]{1, 2, 4, 8, 16, 32, 64}) {
            int limit = (int) Math.floor(grid * 0.4), length = grid * 4;
            for (int slot = 0; slot < 4; slot++) for (int offset = -limit; offset <= limit; offset++) {
                int x = Math.floorMod(slot * grid + offset, length);
                for (int dir : new int[]{-1, 1}) if (FineNudge.withinLimit(x, dir, grid)) {
                    check(FineNudge.nearestSlot(Math.floorMod(x + dir, length), grid, length) == slot,
                            "limited nudge cannot change logical slot");
                }
            }
        }
        check(!FineNudge.withinLimit(7, 1, 16) && FineNudge.withinLimit(7, -1, 16),
                "pre-existing out-of-range note can only move back toward its anchor");
        System.out.println("Fine grid checks passed: fixed pads, edit/delete/create isolation, repeated-hold and Alt caps, page/loop seams, Euclidean original protection.");
    }
    private static void generatedNotesRemainOwnedAfterNudging() {
        for (int direction : new int[]{-1, 1}) for (int target : new int[]{2, -1}) {
            Fixture f = new Fixture(1, "2:16"); // manual original on slot 1
            EuclideanPattern pattern = new EuclideanPattern(f.occupied());
            IntConsumer add = slot -> f.nudge.setStep(2, slot, 100, 0.125, 0.25, 16);
            IntConsumer remove = slot -> f.nudge.clearStep(2, slot, 0.25, 16);
            pattern.turn(4, f.occupied(), add, remove);
            MulticlipTargetChecks.drain();
            check(f.move(direction, target) == (target < 0 ? 4 : 1), "generated held/Alt nudge");
            f.nudge.resetSelection(); // release the gesture
            check(pattern.getPulses() == 4, "nudging retains pulse count and ownership");
            pattern.turn(-3, f.occupied(), add, remove);
            MulticlipTargetChecks.drain();
            check(f.fine.notes.stream().noneMatch(key -> key.equals("2:" + (32 + direction))),
                    "reducing pulse count removes the nudged generated note at its actual position");
            pattern.turn(-1, f.occupied(), add, remove);
            MulticlipTargetChecks.drain();
            String manual = "2:" + (16 + (target < 0 ? direction : 0));
            check(f.fine.notes.equals(Set.of(manual)), "zero pulses removes generated notes only, preserving manual timing");
            pattern.turn(4, f.occupied(), add, remove);
            MulticlipTargetChecks.drain();
            check(f.fine.notes.size() == 4 && f.fine.notes.contains(manual), "pulse count can increase again without duplicate leftovers");
        }
    }

    static Set<Integer> slots(List<NoteStep> notes) {
        Set<Integer> result = new HashSet<>();
        for (NoteStep note : notes) result.add(note.x());
        return result;
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
