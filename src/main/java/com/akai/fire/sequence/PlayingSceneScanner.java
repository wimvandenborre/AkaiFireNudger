package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.*;
import java.util.function.Consumer;

/** Private paged discovery: visiting later scenes never moves the editing bank. */
final class PlayingSceneScanner {
    static final int PAGE_SIZE = 16;
    record Candidate(int lane, int scene) {}
    private final ControllerHost host;
    private final TrackBank tracks;
    private final Consumer<String> diagnostic;
    private long generation;
    private int pagingLane;

    PlayingSceneScanner(ControllerHost host, CursorTrack group, Consumer<String> diagnostic) {
        this.host = host;
        this.diagnostic = diagnostic;
        tracks = group.createMainTrackBank(MulticlipTarget.LANES, 0, PAGE_SIZE, false);
        tracks.sceneBank().scrollPosition().markInterested();
        for (int lane = 0; lane < MulticlipTarget.LANES; lane++) {
            tracks.getItemAt(lane).exists().markInterested();
            tracks.getItemAt(lane).position().markInterested();
            for (int index = 0; index < PAGE_SIZE; index++) {
                ClipLauncherSlot slot = slot(lane, index);
                slot.sceneIndex().markInterested();
                slot.hasContent().markInterested();
                slot.isPlaying().markInterested();
            }
        }
    }

    void cancel() { generation++; }

    void scan(int count, int[] positions, Consumer<Candidate> found,
              Runnable complete, Consumer<String> failed) {
        long ticket = ++generation;
        pagingLane = -1;
        for (int lane = 0; lane < positions.length; lane++) {
            if (positions[lane] >= 0) { pagingLane = lane; break; }
        }
        if (count <= 0 || pagingLane < 0) { complete.run(); return; }
        page(ticket, 0, count, positions, found, complete, failed);
    }

    private ClipLauncherSlot slot(int lane, int index) {
        return tracks.getItemAt(lane).clipLauncherSlotBank().getItemAt(index);
    }

    private boolean trackMatches(int lane, int position) {
        Track track = tracks.getItemAt(lane);
        return track.exists().get() && track.position().get() == position;
    }

    private void page(long ticket, int requested, int count, int[] positions,
                      Consumer<Candidate> found, Runnable complete, Consumer<String> failed) {
        // SceneBank scrolling triggers Bitwig's Follow Controller GUI behavior.
        // A child slot bank shares the scene window, without that GUI side effect.
        tracks.getItemAt(pagingLane).clipLauncherSlotBank().scrollPosition().set(requested);
        awaitPage(ticket, requested, count, positions, found, complete, failed, 0);
    }

    private void awaitPage(long ticket, int requested, int count, int[] positions,
                           Consumer<Candidate> found, Runnable complete, Consumer<String> failed, int attempt) {
        host.scheduleTask(() -> {
            if (ticket != generation) return;
            int start = tracks.sceneBank().scrollPosition().get();
            boolean settled = start >= 0 && start <= requested && requested < start + PAGE_SIZE;
            for (int lane = 0; lane < positions.length && settled; lane++) {
                if (positions[lane] < 0) continue;
                if (!trackMatches(lane, positions[lane])) { settled = false; break; }
                for (int index = 0; index < PAGE_SIZE && start + index < count; index++) {
                    if (slot(lane, index).sceneIndex().get() != start + index) { settled = false; break; }
                }
            }
            if (!settled) {
                if (attempt < 40) awaitPage(ticket, requested, count, positions, found, complete, failed, attempt + 1);
                else failed.accept("Scene list changed; capture again");
                return;
            }
            // Scene indices and playback flags may arrive in separate observations.
            host.scheduleTask(() -> {
                if (ticket != generation) return;
                if (tracks.sceneBank().scrollPosition().get() != start) {
                    failed.accept("Scene list changed; capture again"); return;
                }
                for (int lane = 0; lane < positions.length; lane++) {
                    if (positions[lane] < 0) continue;
                    if (!trackMatches(lane, positions[lane])) {
                        failed.accept("Child tracks changed; capture again"); return;
                    }
                    for (int index = 0; index < PAGE_SIZE && start + index < count; index++) {
                        ClipLauncherSlot slot = slot(lane, index);
                        if (slot.sceneIndex().get() != start + index) {
                            failed.accept("Scene list changed; capture again"); return;
                        }
                        if (slot.hasContent().get() && slot.isPlaying().get()) found.accept(new Candidate(lane, start + index));
                    }
                }
                diagnostic.accept("MULTICLIP_SCAN scenes=" + start + ".." + Math.min(count - 1, start + PAGE_SIZE - 1));
                int next = start + PAGE_SIZE;
                if (next >= count) complete.run();
                else if (next <= requested) failed.accept("Cannot advance scene bank");
                else page(ticket, next, count, positions, found, complete, failed);
            }, 50);
        }, 50);
    }
}
