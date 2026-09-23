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
        NavigableSet<Integer> navigableScenes;
        int navigationDelay;
        void afterTicks(int delay, Runnable action) {
            tasks.add(() -> { if (delay == 0) action.run(); else afterTicks(delay - 1, action); });
        }
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
            if (op.equals("set")) { value = args[0]; calls.add(name + ".set=" + args[0]); return null; }
            if (op.equals("selectChannel")) { calls.add(name + ".selectChannel:" + args[0]); return null; }
            if (op.equals("get") && value != null) return value;
            if (op.equals("toString")) return name;
            if (navigableScenes != null && (op.equals("selectNext") || op.equals("selectPrevious") || op.equals("selectFirst"))) {
                calls.add(name + "." + op);
                int current = (int) node("clipLauncherSlot").node("sceneIndex").value;
                Integer next = op.equals("selectFirst") ? navigableScenes.first()
                        : op.equals("selectNext") ? navigableScenes.higher(current) : navigableScenes.lower(current);
                if (next != null) afterTicks(navigationDelay, () -> {
                    node("clipLauncherSlot").node("sceneIndex").value = next;
                    node("exists").value = true;
                });
                return null;
            }
            Class<?> result = method.getReturnType();
            if (result == void.class) { calls.add(name + "." + op); return null; }
            if (result == boolean.class) return false;
            if (result == int.class) return 0;
            if (result == double.class) return 0.0;
            if (result == String.class) return "";
            if (op.equals("getItemAt")) result = (name.endsWith("createMainTrackBank") || name.endsWith("createTrackBank")) ? Track.class : ClipLauncherSlot.class;
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
        target.configureManualSelection(false); // legacy follow-editor mode
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
        check(Boolean.FALSE.equals(editor.node("isPinned").value)
                && Boolean.FALSE.equals(clip.node("isPinned").value)
                && Boolean.FALSE.equals(fine.node("isPinned").value),
                "settled child cursors keep following selection, as in Oiko");
        check(Boolean.TRUE.equals(group.node("isPinned").value),
                "following child selection must not unpin the rack");
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
        Api playingScene = group.node("createMainTrackBank").node("getItemAt0")
                .node("clipLauncherSlotBank").node("getItemAt6");
        Api emptyScene = group.node("createMainTrackBank").node("getItemAt1")
                .node("clipLauncherSlotBank").node("getItemAt6");
        emptyScene.node("hasContent").value = false;
        playingScene.node("isPlaying").publish(true);
        check(target.ready() && target.midiNote() == 37 && (int) clip.node("clipLauncherSlot").node("sceneIndex").value == 1,
                "scene playback alone leaves Fire selection pinned");
        long launchOrCreate = calls.stream().filter(c -> c.endsWith(".launch") || c.endsWith(".createEmptyClip")).count();
        target.followPlayingScene();
        check(!target.ready() && target.midiNote() == 37, "follow retains lane and invalidates old edits");
        clip.node("isPinned").value = true; // Bitwig has not acknowledged the unpin request yet
        tick();
        check(!calls.contains("group.createMainTrackBank.getItemAt1.clipLauncherSlotBank.getItemAt6.select"),
                "slot selection waits for cursor unpin acknowledgement");
        clip.node("isPinned").value = false;
        tick(); // now unpin and track position are settled
        check(calls.contains("group.createMainTrackBank.getItemAt1.clipLauncherSlotBank.getItemAt6.select"),
                "latest playing scene beats older clip still playing on selected lane");
        check(calls.contains("group.createMainTrackBank.getItemAt1.clipLauncherSlotBank.getItemAt6.showInEditor"),
                "follow explicitly opens selected slot in editor");
        setClip(clip, 12, 6, false);
        setClip(fine, 12, 6, false);
        drain();
        check(target.ready(), "follow can select empty child slot in playing scene");
        check(calls.stream().filter(c -> c.endsWith(".launch") || c.endsWith(".createEmptyClip")).count() == launchOrCreate,
                "follow never launches or creates clips");
        target.selectNote(36);
        editor.node("position").value = 11;
        setClip(clip, 11, 6, true);
        setClip(fine, 11, 6, true);
        drain();
        check(target.ready(), "switching drums retains followed scene");
        playingScene.node("isPlaying").publish(false);
        selected.node("isPlaying").publish(false);
        int previousCalls = calls.size();
        target.followPlayingScene();
        check(target.ready() && calls.size() == previousCalls, "no playing clip leaves selection untouched");
        // Reproduce the real failure: select()/showInEditor()/selectSlot() do nothing,
        // while the clip cursor's own navigation works and skips empty scenes.
        clip.navigableScenes = new TreeSet<>(List.of(1, 4, 6));
        fine.navigableScenes = new TreeSet<>(List.of(1, 4, 6));
        clip.navigationDelay = 2;
        fine.navigationDelay = 5;
        Api earlierScene = group.node("createMainTrackBank").node("getItemAt0")
                .node("clipLauncherSlotBank").node("getItemAt1");
        earlierScene.node("isPlaying").publish(true);
        long previousMoves = calls.stream().filter(c -> c.endsWith(".selectPrevious")).count();
        target.followPlayingScene();
        target.whenReady(() -> edited[0]++);
        int beforeEdits = edited[0];
        tick(); tick();
        check(!target.ready() && edited[0] == beforeEdits, "cursor navigation blocks edits on the previous scene");
        drain();
        check(target.ready() && edited[0] == beforeEdits + 1,
                "direct cursor navigation recovers when editor selection is ignored");
        check((int) clip.node("clipLauncherSlot").node("sceneIndex").value == 1
                && (int) fine.node("clipLauncherSlot").node("sceneIndex").value == 1,
                "both independently delayed cursors reach the exact requested scene");
        check(calls.stream().filter(c -> c.endsWith(".selectPrevious")).count() == previousMoves + 4,
                "no repeated moves while acknowledgement is delayed; empty scenes are skipped");
        long nextMoves = calls.stream().filter(c -> c.endsWith(".selectNext")).count();
        playingScene.node("isPlaying").publish(true);
        target.followPlayingScene();
        drain();
        check(target.ready() && (int) clip.node("clipLauncherSlot").node("sceneIndex").value == 6
                && (int) fine.node("clipLauncherSlot").node("sceneIndex").value == 6,
                "refresh navigates forward to newly playing scene without selecting in the editor");
        check(calls.stream().filter(c -> c.endsWith(".selectNext")).count() == nextMoves + 4,
                "forward navigation also waits for acknowledgement");
        check(Boolean.TRUE.equals(group.node("isPinned").value), "scene refresh keeps rack pinned");
        check(calls.stream().filter(c -> c.endsWith(".launch") || c.endsWith(".createEmptyClip")).count() == launchOrCreate,
                "direct refresh never launches or creates clips");
        // A deleted/unreachable target must never expose either neighboring clip for editing.
        clip.navigableScenes = new TreeSet<>(List.of(1, 6));
        fine.navigableScenes = new TreeSet<>(List.of(1, 6));
        next.node("isPlaying").publish(true); // scene 4, absent from actual cursor navigation
        long movesBeforeMissing = calls.stream().filter(c -> c.endsWith(".selectPrevious") || c.endsWith(".selectNext")).count();
        target.followPlayingScene();
        beforeEdits = edited[0];
        target.whenReady(() -> edited[0]++);
        drain();
        check(!target.ready() && edited[0] == beforeEdits, "unreachable clip times out without editing neighbors");
        check(calls.stream().filter(c -> c.endsWith(".selectPrevious") || c.endsWith(".selectNext")).count() == movesBeforeMissing + 2,
                "navigation stops if missing target is skipped, without bouncing between neighbors");
        target.deactivate();
        previousCalls = calls.size();
        target.followPlayingScene();
        check(calls.size() == previousCalls, "inactive group cannot be retargeted");
        // Fixed group pin + manual scene capture, independent of editor selection.
        target.configureManualSelection(true);
        target.configureHardPin(1);
        Api fixed = host.node("createTrackBank").node("getItemAt0");
        fixed.node("exists").value = true;
        fixed.node("isGroup").value = true;
        fixed.node("position").value = 10;
        clip.navigableScenes = new TreeSet<>(List.of(1, 4, 6));
        fine.navigableScenes = new TreeSet<>(List.of(1, 4, 6));
        editor.node("position").value = 12;
        setClip(clip, 12, 1, true);
        setClip(fine, 12, 1, true);
        selected.node("isSelected").publish(true);
        previousCalls = calls.size();
        target.acquireGroup();
        drain();
        check(target.ready() && target.midiNote() == 37, "fixed group opens initially selected child");
        check(calls.subList(previousCalls, calls.size()).contains("group.selectChannel:host.createTrackBank.getItemAt0"),
                "hard pin selects configured project track directly");
        check(calls.subList(previousCalls, calls.size()).stream().noneMatch(c -> c.equals("group.isPinned.set=false")),
                "acquiring fixed group never releases rack pin");
        check(Boolean.TRUE.equals(editor.node("isPinned").value)
                && Boolean.TRUE.equals(clip.node("isPinned").value) && Boolean.TRUE.equals(fine.node("isPinned").value),
                "manual selection pins editing track and both clip cursors");
        next.node("isSelected").publish(true);
        next.node("isPlaying").publish(true);
        drain();
        check(target.ready() && target.midiNote() == 37
                && (int) clip.node("clipLauncherSlot").node("sceneIndex").value == 1,
                "scene launch and editor selection cannot change manual scene or lane");
        previousCalls = calls.size();
        target.acquireGroup(); // STOP
        drain();
        check(target.ready() && target.midiNote() == 37
                && calls.subList(previousCalls, calls.size()).stream().noneMatch(c -> c.equals("group.isPinned.set=false") || c.startsWith("editor.selectChannel")),
                "STOP preserves the fixed rack pin and captured clips");
        target.followPlayingScene(); // no STOP required
        drain();
        check(target.ready() && (int) clip.node("clipLauncherSlot").node("sceneIndex").value == 4,
                "Metronome manually captures playing scene without STOP");
        earlierScene.node("isPlaying").publish(true);
        selected.node("isSelected").publish(true);
        drain();
        check(target.ready() && (int) clip.node("clipLauncherSlot").node("sceneIndex").value == 4,
                "subsequent scene and editor selection leave captured scene unchanged");
        target.selectNote(36);
        editor.node("position").value = 11;
        setClip(clip, 11, 4, true);
        setClip(fine, 11, 4, true);
        drain();
        check(target.ready(), "manual lane switch retains captured scene");
        target.deactivate();
        check(Boolean.TRUE.equals(group.node("isPinned").value), "hard pin survives leaving drum mode");
        // Invalid configured track must never fall back to the current editor group.
        target.configureHardPin(2);
        target.acquireGroup();
        drain();
        check(!target.ready(), "missing hard-pin target cannot silently use another group");
        Api recoveredGroup = host.node("createTrackBank").node("getItemAt1");
        recoveredGroup.node("exists").value = true;
        recoveredGroup.node("isGroup").value = true;
        recoveredGroup.node("position").value = 20;
        group.node("position").value = 20;
        target.followPlayingScene();
        drain();
        check(target.ready() && (int) clip.node("clipLauncherSlot").node("sceneIndex").value == 1,
                "Metronome can acquire configured group and capture scene without a preceding STOP");
        target.deactivate();
        System.out.println("Multiclip checks passed: direct scene navigation, hard group pin, manual scene capture, stale edits, missing targets.");
    }
}
