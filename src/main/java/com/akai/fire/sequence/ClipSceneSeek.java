package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.PinnableCursorClip;
import java.util.function.Consumer;

/** Navigates the actual clip cursor when selecting a launcher slot did not move it. */
final class ClipSceneSeek {
    private String outstandingFrom;
    private int direction;

    void reset() { outstandingFrom = null; direction = 0; }

    void advance(PinnableCursorClip clip, int track, int scene, Consumer<String> diagnostic) {
        boolean exists = clip.exists().get();
        int currentTrack = clip.getTrack().position().get();
        int currentScene = clip.clipLauncherSlot().sceneIndex().get();
        if (exists && (currentTrack != track || currentScene == scene)) return;
        String observed = currentTrack + ":" + currentScene + ":" + exists;
        // Bitwig acknowledges navigation asynchronously. Sending another move from the
        // same observed state would overshoot when both commands eventually execute.
        if (observed.equals(outstandingFrom)) return;
        int nextDirection = exists && currentScene >= 0 ? Integer.compare(scene, currentScene) : 0;
        // If the requested clip vanished, navigation can skip past it. Do not bounce
        // between neighboring clips; the target coordinator will time out safely.
        if (direction != 0 && nextDirection != 0 && nextDirection != direction) return;
        direction = nextDirection;
        outstandingFrom = observed;
        clip.isPinned().set(true);
        String action;
        if (!exists || currentScene < 0) {
            clip.selectFirst();
            action = "first";
        } else if (currentScene < scene) {
            clip.selectNext();
            action = "next";
        } else {
            clip.selectPrevious();
            action = "previous";
        }
        diagnostic.accept("MULTICLIP_SEEK track=" + track + " from=" + currentScene
                + " to=" + scene + " action=" + action);
    }
}
