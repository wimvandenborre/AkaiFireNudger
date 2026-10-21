package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Oikontrol-style direct-child lanes; the rack cursor stays on the parent group. */
final class MulticlipTarget {
    static final int LANES = 16;
    static final int FIRST_NOTE = 36;
    private final ControllerHost host;
    private final CursorTrack group;
    private final CursorTrack editor;
    private final TrackBank children;
    private final TrackBank fixedTracks;
    private int hardPinTrack;
    private boolean manualSelection = true;
    private Runnable afterAcquire;
    private SceneSelectionSettings sceneSettings;
    private final PlayingSceneScanner sceneScanner;
    private boolean discovering;
    private static final int SCENE_PAGE = PlayingSceneScanner.PAGE_SIZE;
    private final PinnableCursorClip clip;
    private final PinnableCursorClip fine;
    private final Runnable invalidate;
    private final Runnable onReady;
    private final Runnable onGroup;
    private final Consumer<String> feedback;
    private final Consumer<String> diagnostic;
    private final IntConsumer onLane;
    private long selectionGeneration;
    private long generation;
    private boolean active;
    private boolean groupReady;
    private boolean ready;
    private boolean targeting;
    private boolean targetOpened;
    private boolean expectContent;
    private final ClipSceneSeek coarseSeek = new ClipSceneSeek();
    private final ClipSceneSeek fineSeek = new ClipSceneSeek();
    private boolean previousPin;
    private int groupPosition = -1;
    private int lane = -1;
    private int scene;
    private Runnable pending;
    private final java.util.Map<Integer, Long> scenePlayOrder = new java.util.HashMap<>();
    private long playOrder;
    private final boolean[] childPlaying=new boolean[LANES];
    private java.util.function.BiConsumer<Integer,Boolean> activity=(lane,playing)->{};
    private Runnable auditionInvalidated = () -> {};
    private java.util.function.BiConsumer<Integer, PlayingNote[]> inputNotes = (lane, notes) -> {};

    void observeInputNotes(java.util.function.BiConsumer<Integer, PlayingNote[]> observer) { inputNotes = observer; }

    void onAuditionInvalidated(Runnable callback) { auditionInvalidated = callback; }

    void observeActivity(java.util.function.BiConsumer<Integer,Boolean> callback) {
        activity=callback;publishActivity();
    }
    private void publishActivity() {
        for(int i=0;i<LANES;i++)activity.accept(i,active&&eligible(i)&&childPlaying[i]);
    }

    MulticlipTarget(ControllerHost host, CursorTrack group, CursorTrack editor,
                   PinnableCursorClip clip, PinnableCursorClip fine, Runnable invalidate,
                   Runnable onReady, Runnable onGroup, IntConsumer onLane,
                   Consumer<String> feedback, Consumer<String> diagnostic) {
        this.host = host;
        this.group = group;
        this.editor = editor;
        this.clip = clip;
        this.fine = fine;
        this.invalidate = invalidate;
        this.onReady = onReady;
        this.onGroup = onGroup;
        this.feedback = feedback;
        this.diagnostic = diagnostic;
        this.onLane = onLane;
        fixedTracks = host.createTrackBank(128, 0, 0, false);
        for (int i = 0; i < 128; i++) {
            Track track = fixedTracks.getItemAt(i);
            track.exists().markInterested();
            track.isGroup().markInterested();
            track.position().markInterested();
            track.name().markInterested();
        }
        group.exists().markInterested();
        group.isGroup().markInterested();
        group.position().markInterested();
        group.isPinned().markInterested();
        editor.position().markInterested();
        editor.isPinned().markInterested();
        // Exclude group master and effect tracks from the positional drum lanes.
        children = group.createMainTrackBank(LANES, 0, SCENE_PAGE, false);
        children.sceneBank().itemCount().markInterested();
        children.sceneBank().scrollPosition().markInterested();
        editor.clipLauncherSlotBank().scrollPosition().markInterested();
        children.scrollPosition().markInterested();
        children.scrollPosition().set(0);
        for (int i = 0; i < LANES; i++) {
            Track track = children.getItemAt(i);
            track.exists().markInterested();
            track.canHoldNoteData().markInterested();
            track.isGroup().markInterested();
            track.position().markInterested();
            track.name().markInterested();
            final int activityLane=i;
            track.playingNotes().addValueObserver(notes->{
                childPlaying[activityLane]=notes.length>0;publishActivity();
                inputNotes.accept(activityLane, notes);
            });
            track.exists().addValueObserver(exists->{if(!exists)childPlaying[activityLane]=false;publishActivity();});
            track.position().addValueObserver(position->{childPlaying[activityLane]=false;publishActivity();});
            for (int j = 0; j < SCENE_PAGE; j++) {
                ClipLauncherSlot slot = track.clipLauncherSlotBank().getItemAt(j);
                slot.hasContent().markInterested();
                slot.sceneIndex().markInterested();
                slot.color().markInterested();
                slot.isSelected().markInterested();
                slot.isPlaying().markInterested();
                final int childIndex = i;
                final int sceneIndex = j;
                slot.isSelected().addValueObserver(selected -> observeSelection(childIndex, sceneIndex, selected));
                slot.isPlaying().addValueObserver(playing -> {
                    if (playing && active && eligible(childIndex)) {
                        scenePlayOrder.put(slot.sceneIndex().get(), ++playOrder);
                    }
                });
            }
        }
        sceneScanner = new PlayingSceneScanner(host, group, diagnostic);
        for (PinnableCursorClip view : new PinnableCursorClip[]{clip, fine}) {
            view.exists().markInterested();
            view.isPinned().markInterested();
            view.getTrack().position().markInterested();
            view.clipLauncherSlot().sceneIndex().markInterested();
        }
    }

    void initPreferences(Preferences preferences) {
        preferences.getNumberSetting("Hard pin group track (0 = selected group)", "Sequencer",
                0, 128, 1, "", 1).addRawValueObserver(value -> configureHardPin((int) Math.round(value)));
        preferences.getEnumSetting("Child clip selection", "Sequencer",
                new String[]{"Manual (Metronome)", "Follow editor selection"}, "Manual (Metronome)")
                .addValueObserver(value -> configureManualSelection(value.equals("Manual (Metronome)")));
    }

    void bindSceneSettings(SceneSelectionSettings settings) {
        sceneSettings = settings;
        settings.bind(this::selectScene, this::followPlayingScene);
    }

    /** Explicit selection only; changing the number field alone does not move clips. */
    void selectScene(int sceneNumber) {
        if (!active) { feedback.accept("Enter drum mode first"); return; }
        if (!eligible(lane)) {
            if (hardPinTrack > 0) acquireGroup(() -> selectScene(sceneNumber));
            else feedback.accept("Select group, press STOP");
            return;
        }
        int count = sceneCount();
        if (count == 0) { feedback.accept("No launcher scenes"); return; }
        if (sceneNumber < 1 || sceneNumber > count) {
            feedback.accept("Scene must be 1–" + count); return;
        }
        scene = sceneNumber - 1;
        diagnostic.accept("MULTICLIP_MANUAL_SCENE lane=" + lane + " scene=" + scene);
        retarget(null);
    }

    private int sceneCount() { return Math.max(0, children.sceneBank().itemCount().get()); }

    void configureHardPin(int trackNumber) {
        if (hardPinTrack == trackNumber) return;
        hardPinTrack = trackNumber;
        groupPosition = -1;
        if (active) acquireGroup();
    }

    void configureManualSelection(boolean manual) {
        if (manualSelection == manual) return;
        manualSelection = manual;
        selectionGeneration++;
        if (active && ready()) {
            editor.isPinned().set(manual);
            clip.isPinned().set(manual);
            fine.isPinned().set(manual);
        }
    }

    static int laneForNote(int note) {
        return note >= FIRST_NOTE && note < FIRST_NOTE + LANES ? note - FIRST_NOTE : -1;
    }

    void acquireGroup() {
        acquireGroup(null);
    }

    private void acquireGroup(Runnable after) {
        afterAcquire = after;
        // STOP must not release an established hard pin or discard the editing scene.
        if (active && hardPinTrack > 0 && eligible(lane)
                && fixedTrack().exists().get() && fixedTrack().isGroup().get()
                && group.position().get() == fixedTrack().position().get()) {
            group.isPinned().set(true);
            if (after != null) { afterAcquire = null; after.run(); }
            return;
        }
        if (!active) previousPin = group.isPinned().get();
        auditionInvalidated.run();
        active = true;
        groupReady = false;
        publishActivity();
        scenePlayOrder.clear();
        playOrder = 0;
        cancel();
        group.isPinned().set(hardPinTrack > 0);
        diagnostic.accept("MULTICLIP_ACQUIRE group=" + group.position().get());
        long ticket = generation;
        host.scheduleTask(() -> {
            if (hardPinTrack > 0) seekFixedGroup(ticket, 0);
            else seekGroup(ticket, 0);
        }, 50);
    }

    private Track fixedTrack() { return fixedTracks.getItemAt(hardPinTrack - 1); }

    private void seekFixedGroup(long ticket, int attempt) {
        if (!active || ticket != generation) return;
        Track target = fixedTrack();
        if (target.exists().get() && target.isGroup().get()) {
            group.isPinned().set(true);
            if (attempt == 0 || group.position().get() != target.position().get() || !group.isGroup().get()) {
                group.selectChannel(target);
            } else {
                seekGroup(ticket, 0);
                return;
            }
        }
        if (attempt < 20) host.scheduleTask(() -> seekFixedGroup(ticket, attempt + 1), 50);
        else fail("Track " + hardPinTrack + " must be a group");
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
                boolean keepScene = manualSelection && groupPosition == group.position().get();
                groupPosition = group.position().get();
                groupReady = true;
                publishActivity();
                if (!keepScene || !eligible(lane)) chooseInitialClip();
                diagnostic.accept("MULTICLIP_GROUP position=" + groupPosition + " lane=" + lane + " scene=" + scene);
                onGroup.run();
                Runnable requested = afterAcquire;
                afterAcquire = null;
                if (requested != null) {
                    if (eligible(lane)) requested.run();
                    else fail("No note children in group");
                } else retarget(null);
            }, 100);
        } else if (attempt < 20) {
            if (group.exists().get()) group.selectParent();
            host.scheduleTask(() -> seekGroup(ticket, attempt + 1), 50);
        } else {
            fail("Select group, press STOP");
        }
    }

    private void chooseInitialClip() {
        // Prefer the user's selected content, then a playing clip, then existing content.
        // Do not force the first (often empty) scene in Mute-Row mode.
        for (int priority = 0; priority < 3; priority++) {
            for (int child = 0; child < LANES; child++) {
                if (!eligible(child)) continue;
                for (int index = 0; index < SCENE_PAGE; index++) {
                    ClipLauncherSlot candidate = localSlot(child, index);
                    if (candidate.hasContent().get() && (priority == 2
                            || (priority == 0 && candidate.isSelected().get())
                            || (priority == 1 && candidate.isPlaying().get()))) {
                        lane = child;
                        scene = candidate.sceneIndex().get();
                        return;
                    }
                }
            }
        }
        for (int child = 0; child < LANES; child++) {
            if (eligible(child)) { lane = child; scene = 0; return; }
        }
        lane = -1;
    }

    private void observeSelection(int child, int index, boolean selected) {
        int absoluteScene = localSlot(child, index).sceneIndex().get();
        if (manualSelection || !active || targeting || discovering || !selected || !eligible(child)
                || (child == lane && absoluteScene == scene)) return;
        long ticket = ++selectionGeneration;
        host.scheduleTask(() -> {
            if (ticket != selectionGeneration || !active || !eligible(child)
                    || localSlot(child, index).sceneIndex().get() != absoluteScene
                    || !localSlot(child, index).isSelected().get()) return;
            lane = child;
            scene = absoluteScene;
            diagnostic.accept("MULTICLIP_SELECTED lane=" + lane + " scene=" + scene);
            retarget(null);
        }, 50);
    }

    void deactivate() {
        active = false;
        groupReady = false;
        publishActivity();
        cancel();
        group.isPinned().set(hardPinTrack > 0 || previousPin);
    }

    private boolean eligible(int index) {
        if (!groupReady || !group.exists().get() || !group.isGroup().get()
                || group.position().get() != groupPosition || index < 0 || index >= LANES) return false;
        Track track = children.getItemAt(index);
        return track.exists().get() && track.canHoldNoteData().get() && !track.isGroup().get();
    }

    boolean auditionReady() {
        return active && groupReady && group.exists().get() && group.isGroup().get()
                && group.position().get() == groupPosition;
    }

    boolean ready() {
        return ready && !discovering && eligible(lane) && matches();
    }

    record GrooveChild(int lane, Track track, int position, int scene) {}

    String grooveContext() {
        return active && groupReady && group.exists().get() && group.position().get()==groupPosition
                && !discovering && sceneVisible() ? groupPosition+":"+scene : "";
    }

    java.util.List<GrooveChild> grooveChildren() {
        var result=new java.util.ArrayList<GrooveChild>();
        if(grooveContext().isEmpty())return result;
        for(int i=0;i<LANES;i++)if(eligible(i)&&slot(i,scene).hasContent().get())
            result.add(new GrooveChild(i,children.getItemAt(i),children.getItemAt(i).position().get(),slot(i,scene).sceneIndex().get()));
        return java.util.List.copyOf(result);
    }

    private ClipLauncherSlot localSlot(int lane, int index) {
        return children.getItemAt(lane).clipLauncherSlotBank().getItemAt(index);
    }

    private ClipLauncherSlot slot(int index, int absoluteScene) {
        return localSlot(index, absoluteScene - children.sceneBank().scrollPosition().get());
    }

    private boolean sceneVisible() {
        int relative = scene - children.sceneBank().scrollPosition().get();
        if (relative < 0 || relative >= SCENE_PAGE || lane < 0 || lane >= LANES) return false;
        // Position is acknowledged before relying on content or selection in a reused slot proxy.
        for (int child = 0; child < LANES; child++) {
            if (eligible(child) && localSlot(child, relative).sceneIndex().get() != scene) return false;
        }
        return true;
    }

    void selectNote(int note) {
        int next = laneForNote(note);
        if (lane == next && (ready() || targeting)) return;
        lane = next;
        if (groupReady) retarget(null);
    }

    /** Selection only: search the whole scene list, preserving the current drum lane. */
    void followPlayingScene() {
        if (!active || !eligible(lane)) {
            if (active && hardPinTrack > 0) {
                acquireGroup(this::followPlayingScene);
                return;
            }
            feedback.accept("Select group, press STOP");
            return;
        }
        int count = sceneCount();
        if (count <= SCENE_PAGE && children.sceneBank().scrollPosition().get() == 0) {
            int found = -1;
            long newest = -1;
            for (int offset = 0; offset < LANES; offset++) {
                int child = (lane + offset) % LANES;
                if (!eligible(child)) continue;
                for (int index = 0; index < count; index++) {
                    ClipLauncherSlot candidate = localSlot(child, index);
                    int absolute = candidate.sceneIndex().get();
                    long order = scenePlayOrder.getOrDefault(absolute, 0L);
                    if (candidate.hasContent().get() && candidate.isPlaying().get() && order > newest) {
                        found = absolute; newest = order;
                    }
                }
            }
            if (found < 0) { feedback.accept("No playing child clip"); return; }
            captureScene(found);
            return;
        }
        // Discovery uses its own bank. Editing remains blocked until the chosen scene settles.
        cancel();
        discovering = true;
        long ticket = generation;
        int originalScene = scene;
        int[] positions = new int[LANES];
        for (int child = 0; child < LANES; child++) {
            positions[child] = eligible(child) ? children.getItemAt(child).position().get() : -1;
        }
        java.util.List<PlayingSceneScanner.Candidate> candidates = new java.util.ArrayList<>();
        sceneScanner.scan(count, positions, candidates::add, () -> {
            if (!active || ticket != generation) return;
            discovering = false;
            PlayingSceneScanner.Candidate best = null;
            for (var candidate : candidates) {
                if (best == null || newer(candidate, best)) best = candidate;
            }
            if (best == null) {
                scene = originalScene;
                retarget(null);
                feedback.accept("No playing child clip");
            } else captureScene(best.scene());
        }, message -> {
            if (!active || ticket != generation) return;
            discovering = false;
            scene = originalScene;
            retarget(null);
            feedback.accept(message);
        });
    }

    private boolean newer(PlayingSceneScanner.Candidate candidate, PlayingSceneScanner.Candidate best) {
        long order = scenePlayOrder.getOrDefault(candidate.scene(), 0L);
        long bestOrder = scenePlayOrder.getOrDefault(best.scene(), 0L);
        if (order != bestOrder) return order > bestOrder;
        int distance = (candidate.lane() - lane + LANES) % LANES;
        int bestDistance = (best.lane() - lane + LANES) % LANES;
        return distance < bestDistance || (distance == bestDistance && candidate.scene() < best.scene());
    }

    private void captureScene(int absoluteScene) {
        scene = absoluteScene;
        diagnostic.accept("MULTICLIP_FOLLOW_PLAYING lane=" + lane + " scene=" + scene);
        retarget(null);
    }

    void whenReady(Runnable action) {
        if (ready()) action.run();
        else pending = action;
    }

    private void cancel() {
        generation++;
        sceneScanner.cancel();
        discovering = false;
        selectionGeneration++;
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
        targetOpened = false;
        coarseSeek.reset();
        fineSeek.reset();
        if (!sceneVisible()) {
            children.sceneBank().scrollPosition().set((scene / SCENE_PAGE) * SCENE_PAGE);
            awaitSceneWindow(generation, 0);
            return;
        }
        openTarget();
    }

    private void awaitSceneWindow(long ticket, int attempt) {
        host.scheduleTask(() -> {
            if (!active || ticket != generation) return;
            if (!eligible(lane)) { fail("Child track unavailable"); return; }
            if (sceneVisible()) {
                // Allow reused slot properties one more observation cycle to settle.
                host.scheduleTask(() -> {
                    if (active && ticket == generation && sceneVisible()) openTarget();
                    else if (active && ticket == generation) fail("Scene changed; select again");
                }, 50);
            } else if (attempt < 40) awaitSceneWindow(ticket, attempt + 1);
            else fail("Scene unavailable; select again");
        }, 50);
    }

    private void openTarget() {
        editor.clipLauncherSlotBank().scrollPosition().set(children.sceneBank().scrollPosition().get());
        diagnostic.accept("MULTICLIP_TARGET lane=" + lane + " scene=" + scene
                + " child=" + children.getItemAt(lane).name().get()
                + " position=" + children.getItemAt(lane).position().get()
                + " content=" + slot(lane, scene).hasContent().get());
        onLane.accept(FIRST_NOTE + lane);
        editor.isPinned().set(false);
        clip.isPinned().set(false);
        fine.isPinned().set(false);
        editor.selectChannel(children.getItemAt(lane));
        awaitTarget(generation, 0);
    }

    private boolean matches() {
        if (!sceneVisible()) return false;
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
            if (!targetOpened || !matches()) {
                int nextAttempt = attempt + 1;
                if (!sceneVisible()) { fail("Scene changed; select again"); return; }
                if (!targetOpened) {
                    // Give the editor selection one chance after the track settles.
                    if (!editor.isPinned().get() && !clip.isPinned().get() && !fine.isPinned().get()
                            && editor.position().get() == children.getItemAt(lane).position().get()) {
                        editor.selectSlot(slot(lane, scene).sceneIndex().get());
                        slot(lane, scene).select();
                        slot(lane, scene).showInEditor();
                        targetOpened = true;
                    }
                } else if (slot(lane, scene).hasContent().get()
                        && editor.position().get() == children.getItemAt(lane).position().get()) {
                    // UI selection can be ignored by Bitwig. Navigate the actual cursors;
                    // cursor navigation skips empty slots, so verify the reported scene
                    // after each move rather than counting selectNext calls as scenes.
                    int absoluteScene = slot(lane, scene).sceneIndex().get();
                    int position = children.getItemAt(lane).position().get();
                    boolean coarseProgress = coarseSeek.advance(clip, position, absoluteScene, diagnostic);
                    boolean fineProgress = fineSeek.advance(fine, position, absoluteScene, diagnostic);
                    if (coarseProgress || fineProgress) nextAttempt = 0;
                }
                if (nextAttempt < 40) awaitTarget(ticket, nextAttempt);
                else {
                    diagnostic.accept("MULTICLIP_TIMEOUT editor=" + editor.position().get()
                            + " coarseTrack=" + clip.getTrack().position().get()
                            + " fineTrack=" + fine.getTrack().position().get()
                            + " coarseScene=" + clip.clipLauncherSlot().sceneIndex().get()
                            + " fineScene=" + fine.clipLauncherSlot().sceneIndex().get()
                            + " coarseExists=" + clip.exists().get() + " fineExists=" + fine.exists().get()
                            + " editorPinned=" + editor.isPinned().get()
                            + " coarsePinned=" + clip.isPinned().get() + " finePinned=" + fine.isPinned().get()
                            + " targetOpened=" + targetOpened + " slotSelected=" + slot(lane, scene).isSelected().get());
                    fail("Clip unavailable; select again");
                }
                return;
            }
            clip.scrollToKey(FIRST_NOTE + lane);
            fine.scrollToKey(FIRST_NOTE + lane);
            onReady.run();
            host.scheduleTask(() -> {
                if (ticket != generation || !active) return;
                if (!eligible(lane)) { fail("Child track unavailable"); return; }
                if (!matches()) {
                    if (attempt < 40) awaitTarget(ticket, attempt + 1);
                    else fail("Clip unavailable; select again");
                    return;
                }
                ready = true;
                targeting = false;
                if (sceneSettings != null) sceneSettings.selected(scene);
                if (manualSelection) {
                    editor.isPinned().set(true);
                    clip.isPinned().set(true);
                    fine.isPinned().set(true);
                }
                // Direct navigation may pin the clips to protect the requested scene
                // from unrelated editor focus changes. Retargeting explicitly unpins them.
                // Read the fresh grid only after both cursors and key windows have settled.
                Runnable action = pending;
                pending = null;
                if (action != null) action.run();
                diagnostic.accept("MULTICLIP_READY lane=" + lane + " scene=" + scene + " exists=" + clip.exists().get());
                // Normal child selection already updates the pad display through onLane.
            }, 50);
        }, 50);
    }

    private void fail(String message) {
        cancel();
        diagnostic.accept("MULTICLIP_ERROR " + message);
        feedback.accept(message);
    }

    /** Plain scene actions target this lane; ALT launches/creates the group's child scene. */
    void scene(int index, boolean copy, boolean delete, boolean shift, boolean allChildren) {
        if (!eligible(lane) || discovering || !sceneVisible() || index < 0 || index >= SCENE_PAGE) return;
        index += children.sceneBank().scrollPosition().get();
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
        if (!eligible(lane) || discovering || !sceneVisible()) return;
        slot(lane, scene).createEmptyClip(4);
        retarget(afterCreation, true);
    }
}
