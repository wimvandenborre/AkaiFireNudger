package com.akai.fire.sequence;

import java.util.*;

public final class FineNudgeChecks {
    public static void main(String[] args) {
        check(FineNudge.plan(Set.of(7, 0), 8, 1, x -> true).equals(
                List.of(new FineNudge.Move(0, 1), new FineNudge.Move(7, 0))), "wrap ordering");
        check(FineNudge.plan(Set.of(0, 7), 8, -1, x -> true).equals(
                List.of(new FineNudge.Move(7, 6), new FineNudge.Move(0, 7))), "reverse wrap ordering");
        check(FineNudge.plan(Set.of(1, 2), 8, 1, x -> x == 1).isEmpty(), "unselected collision");
        check(FineNudge.plan(Set.of(0, 1), 2, 1, x -> true).isEmpty(), "full loop cannot overwrite");
        check(FineNudge.plan(Set.of(32), 64, -1, x -> x / 16 == 2).equals(
                List.of(new FineNudge.Move(32, 31))), "held-step selection");
        for (int length = 1; length <= 8; length++) {
            for (int mask = 0; mask < 1 << length; mask++) {
                Set<Integer> notes = new HashSet<>();
                for (int step = 0; step < length; step++) if ((mask & 1 << step) != 0) notes.add(step);
                for (int direction : new int[]{-1, 1}) {
                    Set<Integer> result = new HashSet<>(notes);
                    Set<Integer> moved = new HashSet<>();
                    for (FineNudge.Move move : FineNudge.plan(notes, length, direction, x -> true)) {
                        check(result.remove(move.from()), "source missing");
                        check(result.add(move.to()), "note overwritten");
                        check(moved.add(move.from()), "source moved twice");
                        check(move.to() == Math.floorMod(move.from() + direction, length), "wrong delta");
                    }
                    check(result.size() == notes.size(), "note loss");
                    if (notes.size() < length) {
                        Set<Integer> expected = new HashSet<>();
                        for (int note : notes) expected.add(Math.floorMod(note + direction, length));
                        check(result.equals(expected), "whole lane rotation");
                    }
                }
            }
        }
        System.out.println("Fine nudge checks passed: held filtering, collisions, both loop seams, exhaustive short loops.");
    }
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
}
