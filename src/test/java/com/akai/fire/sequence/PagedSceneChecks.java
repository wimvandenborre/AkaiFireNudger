package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.*;
import java.util.*;
import static com.akai.fire.sequence.MulticlipTargetChecks.*;

/** More than 16 scenes, delayed/clamped banks, long cursor journeys and stale requests. */
public final class PagedSceneChecks {
    static int visibleScene;
    static final List<Integer> visibleScrolls = new ArrayList<>();
    static final class Window {
        final Api bank;
        final int count;
        int position;
        final Map<Integer, Set<Integer>> playing;
        Window(Api bank, int count, Map<Integer, Set<Integer>> playing) {
            this.bank = bank; this.count = count; this.playing = playing;
            bank.node("sceneBank").node("itemCount").value = count;
            bank.node("sceneBank").node("scrollPosition").value = 0;
            // SceneBank commands trigger Bitwig's Follow Controller GUI behavior.
            // Clip slot banks share that scene window but do not trigger GUI follow.
            bank.node("sceneBank").node("scrollPosition").onSet = value -> request((int) value, true);
            for (int lane = 0; lane < MulticlipTarget.LANES; lane++) {
                bank.node("getItemAt" + lane).node("clipLauncherSlotBank").node("scrollPosition")
                        .onSet = value -> request((int) value, false);
            }
            render();
        }
        void request(int requested, boolean followInGui) {
            int next = Math.min(requested, Math.max(0, count - PlayingSceneScanner.PAGE_SIZE));
            if (followInGui) { visibleScene = next; visibleScrolls.add(next); }
            // Requested positions are not acknowledged immediately by Bitwig.
            bank.node("sceneBank").node("scrollPosition").value = position;
            if (next != position) bank.afterTicks(2, () -> { position = next; render(); });
        }
        void render() {
            bank.node("sceneBank").node("scrollPosition").value = position;
            for (int lane = 0; lane < MulticlipTarget.LANES; lane++) {
                Api child = bank.node("getItemAt" + lane);
                child.node("exists").value = lane < 2;
                child.node("canHoldNoteData").value = true;
                child.node("isGroup").value = false;
                child.node("position").value = 11 + lane;
                child.node("name").value = "Drum " + lane;
                child.node("clipLauncherSlotBank").node("scrollPosition").value = position;
                for (int index = 0; index < PlayingSceneScanner.PAGE_SIZE; index++) {
                    int absolute = position + index;
                    Api slot = child.node("clipLauncherSlotBank").node("getItemAt" + index);
                    slot.node("sceneIndex").value = absolute;
                    slot.node("hasContent").value = lane < 2 && absolute < count && !(lane == 1 && absolute == 66);
                    slot.node("isPlaying").value = playing.getOrDefault(lane, Set.of()).contains(absolute);
                    slot.node("isSelected").value = lane == 1 && absolute == 0;
                }
            }
        }
    }
    static void finish() {
        int n = 0;
        while (!tasks.isEmpty()) { tick(); check(++n < 10000, "paged navigation must terminate"); }
    }
    static long writes() {
        return calls.stream().filter(c -> c.endsWith(".launch") || c.endsWith(".createEmptyClip")
                || c.endsWith(".deleteObject") || c.endsWith(".clearSteps") || c.contains("copySlotsOrScenes")).count();
    }
    public static void main(String[] args) {
        tasks.clear(); calls.clear(); visibleScrolls.clear(); visibleScene = 0;
        Api host = new Api("host"), group = new Api("group"), editor = new Api("editor");
        Api coarse = new Api("coarse"), fine = new Api("fine");
        group.node("exists").value = true; group.node("isGroup").value = true;
        group.node("position").value = 10; group.node("isPinned").value = false;
        Map<Integer, Set<Integer>> playing = new HashMap<>();
        playing.put(0, Set.of(64));
        Window editWindow = new Window(group.node("createMainTrackBank"), 67, playing);
        Window discovery = new Window(group.node("discoveryBank"), 67, playing);
        editor.node("position").value = 12;
        setClip(coarse, 12, 0, true); setClip(fine, 12, 0, true);
        coarse.navigableScenes = new TreeSet<>();
        for (int scene = 0; scene < 66; scene++) coarse.navigableScenes.add(scene);
        fine.navigableScenes = new TreeSet<>(coarse.navigableScenes);
        coarse.navigationDelay = 1; fine.navigationDelay = 3;
        List<String> messages = new ArrayList<>();
        MulticlipTarget target = new MulticlipTarget(host.proxy(ControllerHost.class), group.proxy(CursorTrack.class),
                editor.proxy(CursorTrack.class), coarse.proxy(PinnableCursorClip.class), fine.proxy(PinnableCursorClip.class),
                () -> {}, () -> {}, () -> {}, ignored -> {}, messages::add, ignored -> {});
        target.acquireGroup(); finish();
        check(target.ready() && target.midiNote() == 37, "startup selects the user's populated child slot");
        target.followPlayingScene();
        check(!target.ready(), "capture blocks editing while later scene pages are inspected");
        while (discovery.position == 0) tick();
        check(editWindow.position == 0, "discovery cannot move the editing slot bank");
        finish();
        check(target.ready() && target.grooveContext().equals("10:64") && target.midiNote() == 37,
                "Metronome captures scene 65 beyond the first 16 and retains the selected drum");
        check(editWindow.position == 51, "last scene window handles host clamping rather than assuming a page multiple");
        check((int) coarse.node("clipLauncherSlot").node("sceneIndex").value == 64
                        && (int) fine.node("clipLauncherSlot").node("sceneIndex").value == 64,
                "both delayed cursors can navigate more than 40 populated clips without premature timeout");
        check(target.grooveChildren().stream().allMatch(c -> c.scene() == 64), "groove children use absolute scene indices after paging");
        check(editor.node("clipLauncherSlotBank").node("scrollPosition").value.equals(51), "clip pad lights and actions share the captured page");
        target.selectScene(33); finish();
        check(target.ready() && target.grooveContext().equals("10:32"), "manual scene number is one-based and reaches later pages");
        int previous = calls.size(); target.selectScene(68); target.selectScene(0);
        check(target.ready() && calls.size() == previous, "invalid scene numbers preserve the current selection");
        target.selectScene(67);
        while (calls.stream().noneMatch(c -> c.equals("editor.selectSlot"))) tick();
        // Simulate the host selecting an empty slot; cursor navigation skips empties.
        setClip(coarse, 12, 66, false); setClip(fine, 12, 66, false);
        finish();
        check(target.ready() && target.grooveContext().equals("10:66"), "manual selection can capture an empty slot without creating a clip");
        target.followPlayingScene();
        target.selectScene(5); finish();
        check(target.ready() && target.grooveContext().equals("10:4"), "manual selection cancels an in-flight playing-scene scan");
        // Real regression: the playing scene is near the top, but discovery scans to the bottom.
        playing.put(0, Set.of(4)); discovery.render(); editWindow.render();
        int previousScrolls = visibleScrolls.size();
        target.followPlayingScene();
        while (!tasks.isEmpty()) {
            tick();
            check(visibleScene == 0, "Metronome must not scroll the visible launcher while scanning later pages");
        }
        check(target.ready() && target.grooveContext().equals("10:4") && discovery.position == 51,
                "full discovery still reaches the last page while capturing the correct early scene");
        check(visibleScrolls.size() == previousScrolls, "discovery must not issue GUI-following scene-bank commands");
        playing.clear(); discovery.render(); editWindow.render();
        target.followPlayingScene(); finish();
        check(target.ready() && target.grooveContext().equals("10:4"), "no playing clip restores the previous editing scene after scanning");
        check(writes() == 0, "capture and manual scene selection never launch, create, delete or rewrite clips");
        target.followPlayingScene(); target.deactivate(); finish();
        check(!target.ready(), "deactivation cancels pending scene-bank callbacks");
        check(messages.stream().noneMatch(m -> m.contains("unavailable") || m.contains("changed") || m.contains("Cannot")),
                "delayed bank and cursor observations settle without routing errors: " + messages);
        System.out.println("Paged scene checks passed: 67 scenes, private discovery without GUI scrolling, clamped/delayed pages, long navigation, manual selection, empty slots, cancellation and no clip writes.");
    }
}
