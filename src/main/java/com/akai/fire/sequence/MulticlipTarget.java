package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.*;
import java.util.function.Consumer;

/** Oikontrol-style direct-child lanes; the rack cursor stays on the parent group. */
final class MulticlipTarget {
    static final int LANES = 16;
    static final int FIRST_NOTE = 36;
    private final ControllerHost host;
    private final CursorTrack group;
    private final CursorTrack editor;
    private final TrackBank children;
    private final PinnableCursorClip clip;
    private final PinnableCursorClip fine;
    private final Runnable invalidate;
    private final Runnable onReady;
    private final Runnable onGroup;
    private final Consumer<String> feedback;
    private long generation;
    private boolean active;
    private boolean groupReady;
    private boolean ready;
    private boolean targeting;
    private boolean expectContent;
    private boolean previousPin;
    private int groupPosition = -1;
    private int lane = -1;
    private int scene;
    private Runnable pending;

    MulticlipTarget(ControllerHost host, CursorTrack group, CursorTrack editor,
                   PinnableCursorClip clip, PinnableCursorClip fine, Runnable invalidate,
                   Runnable onReady, Runnable onGroup, Consumer<String> feedback) {
        this.host = host;
        this.group = group;
        this.editor = editor;
        this.clip = clip;
        this.fine = fine;
        this.invalidate = invalidate;
        this.onReady = onReady;
        this.onGroup = onGroup;
        this.feedback = feedback;
        group.exists().markInterested();
        group.isGroup().markInterested();
        group.position().markInterested();
        group.isPinned().markInterested();
        editor.position().markInterested();
        editor.isPinned().markInterested();
        children = group.createTrackBank(LANES, 0, 16, false);
        children.scrollPosition().markInterested();
        children.scrollPosition().set(0);
        for (int i = 0; i < LANES; i++) {
            Track track = children.getItemAt(i);
            track.exists().markInterested();
            track.canHoldNoteData().markInterested();
            track.isGroup().markInterested();
            track.position().markInterested();
            track.name().markInterested();
            for (int j = 0; j < 16; j++) {
                ClipLauncherSlot slot = track.clipLauncherSlotBank().getItemAt(j);
                slot.hasContent().markInterested();
                slot.sceneIndex().markInterested();
                slot.color().markInterested();
            }
        }
        for (PinnableCursorClip view : new PinnableCursorClip[]{clip, fine}) {
            view.exists().markInterested();
            view.isPinned().markInterested();
            view.getTrack().position().markInterested();
            view.clipLauncherSlot().sceneIndex().markInterested();
        }
    }

    static int laneForNote(int note) {
        return note >= FIRST_NOTE && note < FIRST_NOTE + LANES ? note - FIRST_NOTE : -1;
    }

    void acquireGroup() {
        if (!active) previousPin = group.isPinned().get();
        active = true;
        groupReady = false;
        cancel();
        group.isPinned().set(false);
        long ticket = generation;
        host.scheduleTask(() -> seekGroup(ticket, 0), 50);
    }

    private void seekGroup(long ticket, int attempt) {
        if (!active || ticket != generation) return;
        if (group.exists().get() && group.isGroup().get()) {
            group.isPinned().set(true);
            children.scrollPosition().set(0);
            host.scheduleTask(() -> {
                if (ticket != generation || !active) return;
                if (!group.exists().get() || !group.isGroup().get() || !group.isPinned().get()) {
                    fail("Select group, press STOP");
                    return;
                }
                groupPosition = group.position().get();
                groupReady = true;
                if (lane < 0) lane = 0;
                onGroup.run();
                retarget(null);
            }, 100);
        } else if (attempt < 20) {
            if (group.exists().get()) group.selectParent();
            host.scheduleTask(() -> seekGroup(ticket, attempt + 1), 50);
        } else {
            fail("Select group, press STOP");
        }
    }

    void deactivate() {
        active = false;
        groupReady = false;
        cancel();
        group.isPinned().set(previousPin);
    }

    private boolean eligible(int index) {
        if (!groupReady || !group.exists().get() || !group.isGroup().get()
                || group.position().get() != groupPosition || index < 0 || index >= LANES) return false;
        Track track = children.getItemAt(index);
        return track.exists().get() && track.canHoldNoteData().get() && !track.isGroup().get();
    }

    boolean ready() {
        return ready && eligible(lane) && matches();
    }

    private ClipLauncherSlot slot(int index, int sceneIndex) {
        return children.getItemAt(index).clipLauncherSlotBank().getItemAt(sceneIndex);
    }

    void selectNote(int note) {
        int next = laneForNote(note);
        if (lane == next && (ready() || targeting)) return;
        lane = next;
        if (groupReady) retarget(null);
    }

    void whenReady(Runnable action) {
        if (ready()) action.run();
        else pending = action;
    }

    private void cancel() {
        generation++;
        ready = false;
        targeting = false;
        pending = null;
        invalidate.run();
    }

    private void retarget(Runnable action) {
        retarget(action, false);
    }

    private void retarget(Runnable action, boolean creating) {
        cancel();
        expectContent = creating;
        pending = action;
        if (!eligible(lane)) {
            feedback.accept("No child for this pad");
            return;
        }
        targeting = true;
        editor.isPinned().set(false);
        clip.isPinned().set(false);
        fine.isPinned().set(false);
        editor.selectChannel(children.getItemAt(lane));
        slot(lane, scene).select();
        awaitTarget(generation, 0);
    }

    private boolean matches() {
        int position = children.getItemAt(lane).position().get();
        boolean content = slot(lane, scene).hasContent().get();
        int absoluteScene = slot(lane, scene).sceneIndex().get();
        return (!expectContent || content) && editor.position().get() == position
                && clip.exists().get() == content && fine.exists().get() == content
                && (!content || (clip.getTrack().position().get() == position
                && fine.getTrack().position().get() == position
                && clip.clipLauncherSlot().sceneIndex().get() == absoluteScene
                && fine.clipLauncherSlot().sceneIndex().get() == absoluteScene));
    }

    private void awaitTarget(long ticket, int attempt) {
        host.scheduleTask(() -> {
            if (!active || ticket != generation) return;
            if (!eligible(lane)) { fail("Child track unavailable"); return; }
            if (!matches()) {
                if (attempt < 20) awaitTarget(ticket, attempt + 1);
                else fail("Clip unavailable; select again");
                return;
            }
            clip.scrollToKey(FIRST_NOTE + lane);
            fine.scrollToKey(FIRST_NOTE + lane);
            onReady.run();
            host.scheduleTask(() -> {
                if (ticket != generation || !eligible(lane) || !matches()) return;
                ready = true;
                targeting = false;
                clip.isPinned().set(true);
                fine.isPinned().set(true);
                // Read the fresh grid only after both cursors and key windows have settled.
                Runnable action = pending;
                pending = null;
                if (action != null) action.run();
                feedback.accept(children.getItemAt(lane).name().get() + " / " + (scene + 1));
            }, 50);
        }, 50);
    }

    private void fail(String message) {
        cancel();
        feedback.accept(message);
    }

    /** Plain scene actions target this lane; ALT launches/creates the group's child scene. */
    void scene(int index, boolean copy, boolean delete, boolean shift, boolean allChildren) {
        if (!eligible(lane) || index < 0 || index >= 16) return;
        ClipLauncherSlot target = slot(lane, index);
        if (copy) {
            if (scene != index && slot(lane, scene).hasContent().get())
                target.replaceInsertionPoint().copySlotsOrScenes(slot(lane, scene));
        } else if (delete && shift) {
            target.deleteObject();
            if (index == scene) retarget(null);
        } else if (delete) {
            scene = index;
            retarget(() -> { if (clip.exists().get()) clip.clearSteps(); });
        } else if (shift) {
            target.color().set(SeqClipHandler.getSlotColor(target));
        } else if (allChildren) {
            scene = index;
            for (int child = 0; child < LANES; child++) {
                if (!eligible(child)) continue;
                ClipLauncherSlot childSlot = slot(child, index);
                if (childSlot.hasContent().get()) childSlot.launch();
                else childSlot.createEmptyClip(4);
            }
            retarget(null, true);
        } else {
            scene = index;
            if (target.hasContent().get()) target.launch();
            else target.createEmptyClip(4);
            retarget(null, true);
        }
    }

    int midiNote() { return FIRST_NOTE + lane; }
    int midiChannel() { return Math.max(0, lane); }

    void createClip(Runnable afterCreation) {
        if (!eligible(lane)) return;
        slot(lane, scene).createEmptyClip(4);
        retarget(afterCreation, true);
    }
}
