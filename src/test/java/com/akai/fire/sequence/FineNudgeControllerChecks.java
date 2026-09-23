package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.*;
import java.lang.reflect.*;
import java.util.*;

/** Exercise actual fine-cursor calls, not just the move planner. */
public final class FineNudgeControllerChecks {
    static final class ClipApi extends MulticlipTargetChecks.Api {
        final Set<String> notes = new HashSet<>();
        final Set<String> hidden = new HashSet<>();
        final Set<String> edited = new HashSet<>();
        int pitch;
        ClipApi() { super("fine"); }
        String key(int channel, int step) { return channel + ":" + step; }
        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "scrollToKey": pitch = (int) args[0]; return null;
                case "getStep":
                    int channel = (int) args[0], step = (int) args[1];
                    return Proxy.newProxyInstance(NoteStep.class.getClassLoader(), new Class<?>[]{NoteStep.class},
                            (p, op, values) -> {
                                if (op.getName().equals("state")) return notes.contains(key(channel, step)) && !hidden.contains(key(channel, step))
                                        ? NoteStep.State.NoteOn : NoteStep.State.Empty;
                                if (op.getName().equals("x")) return step;
                                if (op.getName().equals("channel")) return channel;
                                if (op.getName().equals("setVelocity")) { edited.add(key(channel, step)); return null; }
                                throw new AssertionError(op);
                            });
                case "setStep": notes.add(key((int) args[0], (int) args[1])); return null;
                case "clearStep": notes.remove(key((int) args[0], (int) args[1])); return null;
                case "moveStep":
                    int ch = (int) args[0], from = (int) args[1], delta = (int) args[3];
                    check((int) args[2] == 0, "selected pitch uses relative row zero");
                    check(notes.remove(key(ch, from)), "source exists");
                    check(notes.add(key(ch, from + delta)), "no collision");
                    return null;
                default: return super.invoke(proxy, method, args);
            }
        }
    }
    public static void main(String[] args) {
        MulticlipTargetChecks.tasks.clear();
        ClipApi fine = new ClipApi();
        MulticlipTargetChecks.Api coarse = new MulticlipTargetChecks.Api("coarse");
        for (MulticlipTargetChecks.Api clip : List.of(coarse, fine)) {
            MulticlipTargetChecks.setClip(clip, 2, 1, true);
            clip.node("getLoopStart").value = 4.0;
            clip.node("getLoopLength").value = 1.0;
        }
        ControllerHost host = new MulticlipTargetChecks.Api("host").proxy(ControllerHost.class);
        CursorTrack track = (CursorTrack) Proxy.newProxyInstance(CursorTrack.class.getClassLoader(),
                new Class<?>[]{CursorTrack.class}, (p, method, values) -> fine.proxy(PinnableCursorClip.class));
        FineNudge nudge = new FineNudge(host, track, coarse.proxy(PinnableCursorClip.class), text -> {});
        nudge.focus(38);
        MulticlipTargetChecks.drain();
        fine.notes.add("2:0");
        fine.notes.add("5:16");
        check(nudge.move(-1, true, x -> x == 0, 0.25) == 1, "held note moves on its original MIDI channel");
        check(fine.pitch == 38 && fine.notes.contains("2:63") && fine.notes.contains("5:16"), "pitch and loop wrap");
        MulticlipTargetChecks.drain();
        check(nudge.move(-1, true, x -> x == 0, 0.25) == 1 && fine.notes.contains("2:62"),
                "held note stays selected after crossing a grid cell and loop boundary");
        check(nudge.offsetText().equals("-2/64 beat"), "held cumulative offset across loop seam");
        MulticlipTargetChecks.drain();
        nudge.resetSelection();
        check(nudge.move(1, false, x -> true, 0.25) == 2, "whole lane across MIDI channels");
        MulticlipTargetChecks.drain();
        check(nudge.offsetText().equals("+1/64 beat"), "new whole-loop hold resets offset");
        check(nudge.move(-1, false, x -> true, 0.25) == 2 && nudge.offsetText().equals("0/64 beat"),
                "opposite nudge returns gesture offset to zero");
        MulticlipTargetChecks.drain();
        fine.notes.clear();
        fine.notes.add("2:0");
        fine.notes.add("2:1");
        fine.notes.add("2:16");
        nudge.resetSelection();
        check(nudge.move(1, true, x -> x != 1, 0.25) == 1 && nudge.offsetText().equals("0..+1/64 beat"),
                "partially blocked selection displays actual offset range");
        MulticlipTargetChecks.drain();
        fine.node("getTrack").node("position").value = 3;
        Set<String> before = Set.copyOf(fine.notes);
        check(nudge.move(1, false, x -> true, 0.25) == -1 && fine.notes.equals(before), "mismatched clip blocked safely");
        System.out.println("Fine cursor checks passed: actual moves, pitch/channel isolation, held-note tracking, loop wrap, display offsets and stale-target guard.");
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
