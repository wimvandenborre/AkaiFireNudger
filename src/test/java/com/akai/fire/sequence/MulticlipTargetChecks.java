package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.*;
import java.lang.reflect.*;
import java.util.*;

/** Simulates asynchronous Bitwig cursor changes independently of the hardware. */
public final class MulticlipTargetChecks {
    static final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    static final List<String> calls = new ArrayList<>();

    static class Api implements InvocationHandler {
        final String name;
        final Map<String, Api> children = new HashMap<>();
        Object value;
        final List<com.bitwig.extension.callback.BooleanValueChangedCallback> observers = new ArrayList<>();
        void publish(boolean next) { value = next; observers.forEach(observer -> observer.valueChanged(next)); }
        Api(String name) { this.name = name; }
        Api node(String key) { return children.computeIfAbsent(key, k -> new Api(name + "." + k)); }
        <T> T proxy(Class<T> type) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, this));
        }
        public Object invoke(Object proxy, Method method, Object[] args) {
            String op = method.getName();
            if (op.equals("scheduleTask")) { tasks.add((Runnable) args[0]); return null; }
            if (op.equals("addValueObserver") && args[0] instanceof com.bitwig.extension.callback.BooleanValueChangedCallback observer) { observers.add(observer); return null; }
            if (op.equals("set")) { value = args[0]; return null; }
            if (op.equals("get") && value != null) return value;
            if (op.equals("toString")) return name;
            Class<?> result = method.getReturnType();
            if (result == void.class) { calls.add(name + "." + op); return null; }
            if (result == boolean.class) return false;
            if (result == int.class) return 0;
            if (result == double.class) return 0.0;
            if (result == String.class) return "";
            if (op.equals("getItemAt")) result = name.endsWith("createMainTrackBank") ? Track.class : ClipLauncherSlot.class;
            if (result.isInterface()) return node(op + (op.equals("getItemAt") ? args[0] : "")).proxy(result);
            return null;
        }
    }
    static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
    static void tick() { check(!tasks.isEmpty(), "expected scheduled task"); tasks.remove().run(); }
    static void drain() { int count = 0; while (!tasks.isEmpty()) { tick(); check(count++ < 100, "runaway retry"); } }
    static void setClip(Api clip, int track, int scene, boolean exists) {
        clip.node("exists").value = exists;
        clip.node("getTrack").node("position").value = track;
        clip.node("clipLauncherSlot").node("sceneIndex").value = scene;
    }

    public static void main(String[] args) {
        check(MulticlipTarget.laneForNote(35) == -1 && MulticlipTarget.laneForNote(52) == -1, "range");
        check(MulticlipTarget.laneForNote(36) == 0 && MulticlipTarget.laneForNote(51) == 15, "mapping");
        Api host = new Api("host"), group = new Api("group"), editor = new Api("editor");
        Api clip = new Api("coarse"), fine = new Api("fine");
        group.node("exists").value = true;
        group.node("isGroup").value = true;
        group.node("position").value = 10;
        group.node("isPinned").value = false;
        for (int lane = 0; lane < 16; lane++) {
            Api child = group.node("createMainTrackBank").node("getItemAt" + lane);
            child.node("exists").value = lane < 2;
            child.node("canHoldNoteData").value = true;
            child.node("position").value = 11 + lane;
            for (int scene = 0; scene < 16; scene++) {
                Api slot = child.node("clipLauncherSlotBank").node("getItemAt" + scene);
                slot.node("hasContent").value = true;
                slot.node("sceneIndex").value = scene;
            }
        }
        int[] ready = {0}, cleared = {0}, edited = {0};
        MulticlipTarget target = new MulticlipTarget(host.proxy(ControllerHost.class),
                group.proxy(CursorTrack.class), editor.proxy(CursorTrack.class),
                clip.proxy(PinnableCursorClip.class), fine.proxy(PinnableCursorClip.class),
                () -> cleared[0]++, () -> ready[0]++, () -> {}, note -> {}, text -> {}, text -> {});
        target.selectNote(36);
        target.acquireGroup();
        tick(); tick();
        check(Boolean.TRUE.equals(group.node("isPinned").value), "rack must stay pinned");
        tick();
        check(!target.ready(), "must wait for cursors");
        editor.node("position").value = 11;
        setClip(clip, 11, 0, true);
        setClip(fine, 12, 0, true);
        tick();
        check(!target.ready(), "fine cursor must match track too");
        setClip(fine, 11, 0, true);
        drain();
        check(target.ready() && ready[0] == 1, "first lane settled");
        target.selectNote(37);
        target.whenReady(() -> edited[0]++);
        check(!target.ready(), "lane change blocks stale edits");
        target.selectNote(36);
        drain();
        check(target.ready() && edited[0] == 0, "obsolete deferred edit cancelled");
        target.scene(3, false, true, false, false);
        tick();
        check(!target.ready(), "clear must wait for scene");
        check(calls.stream().noneMatch(c -> c.equals("coarse.clearSteps")), "wrong clip not cleared");
        setClip(clip, 11, 3, true);
        setClip(fine, 11, 3, true);
        drain();
        check(calls.stream().filter(c -> c.equals("coarse.clearSteps")).count() == 1, "target clip cleared once");
        // A new clip must exist before the deferred first note can be inserted.
        Api empty = group.node("createMainTrackBank").node("getItemAt0")
                .node("clipLauncherSlotBank").node("getItemAt3");
        empty.node("hasContent").value = false;
        setClip(clip, 11, 3, false);
        setClip(fine, 11, 3, false);
        target.createClip(() -> edited[0]++);
        tick();
        check(edited[0] == 0 && !target.ready(), "empty cursor is not a created clip");
        empty.node("hasContent").value = true;
        setClip(clip, 11, 3, true);
        setClip(fine, 11, 3, true);
        drain();
        check(edited[0] == 1, "first note inserted only after clip creation settles");
        int launches = (int) calls.stream().filter(c -> c.endsWith(".launch")).count();
        target.scene(3, false, false, false, true);
        drain();
        check(calls.stream().filter(c -> c.endsWith(".launch")).count() == launches + 2,
                "group scene launches only existing eligible children");
        group.node("position").value = 20;
        check(!target.ready(), "group change prevents edits through old cursor");
        group.node("position").value = 10;
        target.selectNote(51);
        check(!target.ready(), "missing child not editable");
        target.deactivate();
        drain();
        check(Boolean.FALSE.equals(group.node("isPinned").value), "restore initial pin");
        check(!target.ready() && cleared[0] >= 4, "deactivate invalidates cached edits");
        Api selected = group.node("createMainTrackBank").node("getItemAt1")
                .node("clipLauncherSlotBank").node("getItemAt1");
        selected.node("isSelected").publish(true);
        editor.node("position").value = 12;
        setClip(clip, 12, 1, true);
        setClip(fine, 12, 1, true);
        target.acquireGroup();
        drain();
        check(target.ready() && target.midiNote() == 37, "startup follows selected child scene 2");
        selected.node("isSelected").publish(false);
        Api next = group.node("createMainTrackBank").node("getItemAt0")
                .node("clipLauncherSlotBank").node("getItemAt4");
        next.node("isSelected").publish(true);
        tick();
        check(!target.ready(), "external selection invalidates old cursor");
        editor.node("position").value = 11;
        setClip(clip, 11, 4, true);
        setClip(fine, 11, 4, true);
        drain();
        check(target.ready() && target.midiNote() == 36, "external clip selection changes lane and scene");
        next.node("isSelected").publish(false);
        selected.node("isPlaying").value = true;
        target.acquireGroup();
        editor.node("position").value = 12;
        setClip(clip, 12, 1, true);
        setClip(fine, 12, 1, true);
        drain();
        check(target.ready() && target.midiNote() == 37, "startup follows playing child when none selected");
        System.out.println("Multiclip checks passed: mapping, independent cursors, stale edit cancellation, scene clear, missing lanes, pin restore.");
    }
}
