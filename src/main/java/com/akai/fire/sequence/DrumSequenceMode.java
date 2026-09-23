package com.akai.fire.sequence;

import com.akai.fire.AkaiFireDrumSeqExtension;
import com.akai.fire.NoteAssign;
import com.akai.fire.control.BiColorButton;
import com.akai.fire.control.RgbButton;
import com.akai.fire.control.TouchEncoder;
import com.akai.fire.display.DisplayInfo;
import com.akai.fire.display.OledDisplay;
import com.akai.fire.display.OledDisplay.TextJustification;
import com.akai.fire.lights.BiColorLightState;
import com.akai.fire.lights.RgbLigthState;
import com.akai.fire.utils.PatternButtons;
import com.bitwig.extension.controller.api.*;
import com.bitwig.extension.controller.api.NoteStep.State;
import com.bitwig.extensions.framework.Layer;
import com.bitwig.extensions.framework.values.BooleanValueObject;
import com.bitwig.extensions.framework.values.StepViewPosition;

import java.util.*;
import java.util.stream.Collectors;

public class DrumSequenceMode extends Layer {

    private final ControllerHost host;
    private Application app;

    private final IntSetValue heldSteps = new IntSetValue();
    private final StepHoldGesture stepHoldGesture = new StepHoldGesture();
    private final Set<Integer> addedSteps = new HashSet<>();
    private final Set<Integer> modifiedSteps = new HashSet<>();
    private final HashMap<Integer, NoteSnapshot> expectedNoteChanges = new HashMap<>();

    private final NoteStep[] assignments = new NoteStep[32];

    private final OledDisplay oled;

    private final Layer mainLayer;
    private final Layer shiftLayer;
    private Layer currentLayer;
    private final Layer muteLayer;
    private final Layer soloLayer;
    private final SequencEncoderHandler encoderLayer;

    private final CursorTrack cursorTrack;
    private final PinnableCursorClip cursorClip;
    private final CursorTrack editTrack;
    private final MulticlipTarget multiclip;
    private final FineNudge fineNudge;

//    private Clip cursorClipLauncher;

    private final StepViewPosition positionHandler;
    private final ResolutionHander resolutionHandler;
    private final SeqClipHandler clipHandler;
    private final RecurrenceEditor recurrenceEditor;
    private final PadHandler padHandler;
    private final PadRowMuteHandler padRowMuteHandler;

    private final BooleanValueObject muteMode = new BooleanValueObject();
    private final BooleanValueObject soloMode = new BooleanValueObject();
    private final BooleanValueObject selectHeld = new BooleanValueObject();
    private final BooleanValueObject copyHeld = new BooleanValueObject();
    private final BooleanValueObject deleteHeld = new BooleanValueObject();
    private final BooleanValueObject fixedLengthHeld = new BooleanValueObject();
    private final BooleanValueObject shiftActive = new BooleanValueObject();
    private final BooleanValueObject altActive = new BooleanValueObject();
    private final BooleanValueObject clipLaunchModeQuant = new BooleanValueObject();
    private final BooleanValueObject lengthDisplay = new BooleanValueObject();

    private final BooleanValueObject muteActionsTaken = new BooleanValueObject();
    private final BooleanValueObject soloActionsTaken = new BooleanValueObject();

    private int playingStep;
    // This defines the length
    private final double gatePercent = 0.48;
    private boolean markIgnoreOrigLen = false;
    private final AccentHandler accentHandler;
    private NoteAction pendingAction;
    private NoteSnapshot copyNote = null;
    private int blinkState;

    private CursorRemoteControlsPage activeRemoteControlsPage;
    private EuclideanPattern euclideanPattern;
    private final EuclideanRotations euclideanRotations;
    private int euclideanSteps;
    private int euclideanOffset;
    private double euclideanResolution;


    public DrumSequenceMode(final AkaiFireDrumSeqExtension driver) {

        super(driver.getLayers(), "DRUM_SEQUENCE_LAYER");
        host = driver.getHost();
        oled = driver.getOled();
        app = host.createApplication();
        app.recordQuantizationGrid().markInterested();

        SettableEnumValue secondRowFuncPref = driver.getSecondRowFuncPref();
        mainLayer = new Layer(getLayers(), getName() + "_MAIN");
        shiftLayer = new Layer(getLayers(), getName() + "_SHIFT");
        muteLayer = new Layer(getLayers(), getName() + "_MUTE");
        soloLayer = new Layer(getLayers(), getName() + "_SOLO");

        currentLayer = mainLayer;
        accentHandler = new AccentHandler(this);
        resolutionHandler = new ResolutionHander(this);

        cursorTrack = driver.getViewControl().getCursorTrack();
        cursorTrack.name().markInterested();
        cursorTrack.isPinned().markInterested();
        final SettableEnumValue clipSource = host.getPreferences().getEnumSetting(
                "Clip source (reload extension)", "Sequencer",
                new String[]{"Selected track", "Group child tracks"}, "Selected track");
        clipSource.markInterested();
        final boolean childClips = clipSource.get().equals("Group child tracks");
        driver.getDiagnosticLog().log("CLIP_SOURCE mode=" + clipSource.get() + " childClips=" + childClips);
        editTrack = childClips
                ? host.createCursorTrack("FIRE_CHILD_CLIP", "Fire child clip", 0, 16, false)
                : cursorTrack;
        cursorClip = editTrack.createLauncherCursorClip("SQClip", "SQClip", 32, 1);
        fineNudge = new FineNudge(host, editTrack, cursorClip, driver.getDiagnosticLog()::log);
        multiclip = childClips ? new MulticlipTarget(host, cursorTrack, editTrack, cursorClip,
                fineNudge.clip(), this::clearClipContext, this::positionReady, () -> prepareMulticlipPads(driver),
                this::syncMulticlipLane, message -> oled.paramInfo("Multiclip", message),
                driver.getDiagnosticLog()::log) : null;

        cursorClip.addNoteStepObserver(this::handleNoteStep);
        fineNudge.clip().addNoteStepObserver(this::handleFineNoteStep);
        cursorClip.playingStep().addValueObserver(this::handlePlayingStep);
        cursorClip.getLoopLength().addValueObserver(clipLength -> {
            if (markIgnoreOrigLen) {
                markIgnoreOrigLen = false;
            }
        });
        cursorClip.isPinned().markInterested();
        cursorClip.exists().markInterested();
        cursorClip.exists().addValueObserver(exists -> resetEuclideanPattern());
        cursorClip.clipLauncherSlot().sceneIndex().addValueObserver(index -> resetEuclideanPattern());
        cursorClip.getTrack().position().addValueObserver(index -> resetEuclideanPattern());
        cursorClip.getLoopStart().addValueObserver(start -> resetEuclideanPattern());
        cursorClip.getLoopLength().addValueObserver(length -> resetEuclideanPattern());

        positionHandler = new StepViewPosition(cursorClip, 32, "AKAI");

        padHandler = new PadHandler(driver, this, mainLayer, muteLayer, soloLayer);

        if (secondRowFuncPref.get().equals("Mute-Row")) {
            padRowMuteHandler = new PadRowMuteHandler(driver, this, mainLayer);
            clipHandler = null;
        } else {
            clipHandler = new SeqClipHandler(driver, this, mainLayer);
            padRowMuteHandler = null;
        }
        recurrenceEditor = new RecurrenceEditor(driver, this);

        initSequenceSection(driver);
        initModeButtons(driver);
        initButtonBehaviour(driver);
        encoderLayer = new SequencEncoderHandler(this, driver, padHandler);

        muteMode.addValueObserver(active -> {
            if (active) {
                muteLayer.activate();
            } else {
                muteLayer.deactivate();
            }
        });

        soloMode.addValueObserver(active -> {
            if (active) {
                soloLayer.activate();
            } else {
                soloLayer.deactivate();
            }
        });
        copyHeld.addValueObserver(held -> {
            if (!held && copyNote != null) {
                copyNote = null;
            }
        });

        final TouchEncoder mainEncoder = driver.getMainEncoder();
        mainEncoder.setStepSize(0.4);
        mainEncoder.bindEncoder(mainLayer, this::handleMainEncoder);
        mainEncoder.bindTouched(mainLayer, this::handeMainEncoderPress);

        accentHandler.initPreferences(host.getPreferences());

        // Register rotation settings after the other controls.
        euclideanRotations = new EuclideanRotations(host.getDocumentState());
    }


    private void syncMulticlipLane(int note) {
        padHandler.syncMulticlipLane(note);
    }

    private void prepareMulticlipPads(AkaiFireDrumSeqExtension driver) {
        driver.getViewControl().getDrumPadBank().scrollPosition().set(36);
    }

    private void positionReady() {
        positionHandler.setPage(0);
        fineNudge.focus(multiclip.midiNote());
    }

    int noteChannel() { return multiclip == null ? 0 : multiclip.midiChannel(); }

    void clearNoteRow() {
        for (int channel = 0; channel < 16; channel++) cursorClip.clearStepsAtY(channel, 0);
    }

    void setLogicalStep(int channel, int step, int velocity, double duration) {
        fineNudge.setStep(channel, step, velocity, duration, getGridResolution(), positionHandler.getStepOffset());
    }

    private void clearLogicalStep(int channel, int step) {
        fineNudge.clearStep(channel, step, getGridResolution(), positionHandler.getStepOffset());
    }

    private void refreshStep(int step) {
        List<NoteStep> mapped = fineNudge.pageNotes(getGridResolution(), positionHandler.getStepOffset());
        if (mapped != null) {
            NoteStep chosen = mapped.stream().filter(note -> note.x() == step).findFirst().orElse(null);
            if (chosen == null) {
                // Retain length shading, but don't light a second pad for an early onset.
                for (int channel = 0; channel < 16; channel++) {
                    NoteStep sustain = cursorClip.getStep(channel, step, 0);
                    if (sustain.state() == State.NoteSustain) { chosen = sustain; break; }
                }
            }
            assignments[step] = chosen;
            return;
        }
        NoteStep chosen = cursorClip.getStep(noteChannel(), step, 0);
        for (int channel = 0; channel < 16; channel++) {
            NoteStep candidate = cursorClip.getStep(channel, step, 0);
            if (candidate.state() == State.NoteOn) { chosen = candidate; break; }
            if (candidate.state() == State.NoteSustain) chosen = candidate;
        }
        assignments[step] = chosen;
    }

    void clearClipContext() {
        resetEuclideanPattern();
        if (recurrenceEditor != null) recurrenceEditor.cancel();
        Arrays.fill(assignments, null);
        heldSteps.stream().toList().forEach(heldSteps::remove);
        stepHoldGesture.clear();
        fineNudge.resetSelection();
        addedSteps.clear();
        modifiedSteps.clear();
        expectedNoteChanges.clear();
        copyNote = null;
        playingStep = -1;
    }

    boolean clipReady() {
        return multiclip == null || multiclip.ready();
    }

    boolean noteEditsReady() { return clipReady() && fineNudge.editsReady(getGridResolution()); }

    CursorTrack getEditTrack() { return editTrack; }
    MulticlipTarget getMulticlip() { return multiclip; }

    void focusNote(final int note) {
        if (multiclip != null) multiclip.selectNote(note);
        cursorClip.scrollToKey(note);
        fineNudge.focus(note);
    }

    private void initModeButtons(final AkaiFireDrumSeqExtension driver) {
        final MultiStateHardwareLight[] stateLights = driver.getStateLights();
        bindEditButton(driver.getButton(NoteAssign.MUTE_1), "Select", selectHeld, stateLights[0], muteMode,
                muteActionsTaken);
        if (padRowMuteHandler != null) {
            bindSecondRowModeButton(driver.getButton(NoteAssign.MUTE_2), stateLights[1]);
        } else {
            bindEditButton(driver.getButton(NoteAssign.MUTE_2), "Last Step", fixedLengthHeld, stateLights[1], soloMode,
                    soloActionsTaken);
        }
        bindEditButton(driver.getButton(NoteAssign.MUTE_3), "Copy", copyHeld, stateLights[2], null, null);
        bindEditButton(driver.getButton(NoteAssign.MUTE_4), "Delete/Reset", deleteHeld, stateLights[3], null, null);
        final BiColorButton deleteButton = driver.getButton(NoteAssign.MUTE_4);
        deleteButton.bind(mainLayer, deleteHeld, BiColorLightState.GREEN_FULL, BiColorLightState.OFF);
    }

    private void bindSecondRowModeButton(final BiColorButton button, final MultiStateHardwareLight stateLight) {
        button.bindPressRelease(mainLayer, padRowMuteHandler::handleModeButton);
        mainLayer.bindLightState(this::getSecondRowModeLightState, stateLight);
    }

    private BiColorLightState getSecondRowModeLightState() {
        return padRowMuteHandler.isSoloMode()
                ? BiColorLightState.RECTANGLE_GREEN_FULL
                : BiColorLightState.RECTANGLE_RED_FULL;
    }

    private void toggleRecordQuantization(boolean pressed) {
        if (!pressed) {
            return; // Only execute on press, not on release
        }

        SettableEnumValue quantizationSetting = app.recordQuantizationGrid();
        String currentSetting = quantizationSetting.get();

        // Toggle between "OFF" and "1/16"
        if ("OFF".equals(currentSetting)) {
            quantizationSetting.set("1/16");
            oled.paramInfo("Quantization", "Set to 1/16");
        } else {
            quantizationSetting.set("OFF");
            oled.paramInfo("Quantization", "Disabled");
        }
    }

    private BiColorLightState getQuantizationLightState() {
        SettableEnumValue quantizationSetting = app.recordQuantizationGrid();

        if (quantizationSetting == null) {
            return BiColorLightState.AMBER_HALF; // Default to "OFF" state
        }

        String currentValue = quantizationSetting.get();

        return "OFF".equals(currentValue) ? BiColorLightState.AMBER_HALF : BiColorLightState.AMBER_FULL;
    }



    private void initButtonBehaviour(final AkaiFireDrumSeqExtension driver) {

        final BiColorButton accentButton = driver.getButton(NoteAssign.STEP_SEQ); // TODO combine with encoder
        accentButton.bindPressed(mainLayer, accentHandler::handlePressed, accentHandler::getLightState);

        final BiColorButton shiftButton = driver.getButton(NoteAssign.SHIFT);
        shiftButton.bind(mainLayer, shiftActive, BiColorLightState.GREEN_HALF, BiColorLightState.OFF);

        final BiColorButton altButton = driver.getButton(NoteAssign.ALT);
        altButton.bind(mainLayer, altActive, BiColorLightState.GREEN_HALF, BiColorLightState.OFF);
        altActive.addValueObserver(active -> fineNudge.resetSelection());

        final BiColorButton quantizeButton = driver.getButton(NoteAssign.NOTE);
        quantizeButton.bindPressed(mainLayer, this::toggleRecordQuantization, this::getQuantizationLightState);


//        final BiColorButton clipLaunchModeButton = driver.getButton(NoteAssign.NOTE);
//        clipLaunchModeButton.bindToggle(mainLayer, clipLaunchModeQuant, BiColorLightState.AMBER_FULL,
//                BiColorLightState.AMBER_HALF, oled,
//                new DisplayInfo().addLine("Clip Legato", 2, 0, TextJustification.CENTER)//
//                        .addLine(() -> clipLaunchModeQuant.get() ? "with quant" : "immediate", 2, 3,
//                                TextJustification.CENTER)//
//                        .create());

//        final BiColorButton retrigButton = driver.getButton(NoteAssign.DRUM);
//        retrigButton.bind(mainLayer, this::retrigger, BiColorLightState.AMBER_FULL, BiColorLightState.AMBER_HALF);

        final BiColorButton pinButton = driver.getButton(NoteAssign.STOP);
        pinButton.bindPressed(mainLayer, this::handleClipPinning, this::getPinnedState);

        final BiColorButton resolutionButton = driver.getButton(NoteAssign.PERFORM);
        resolutionButton.bindPressed(mainLayer, resolutionHandler::handlePressed, resolutionHandler::getLightState);

        final BiColorButton shiftLeftButton = driver.getButton(NoteAssign.BANK_L);
        shiftLeftButton.bindPressed(mainLayer, p -> {
            if (!p) return;
            if (shiftActive.get()) {
                // If shift is held, perform the undo action.
                resetEuclideanPattern();
                getApplication().undo();
            } else {
                // Otherwise, perform the move pattern action.
                movePattern(p, -1);
            }
        }, BiColorLightState.HALF, BiColorLightState.OFF);

        final BiColorButton shiftRightButton = driver.getButton(NoteAssign.BANK_R);
        shiftRightButton.bindPressed(mainLayer, p -> {
            if (!p) return;
            if (shiftActive.get()) {
                // If shift is held, perform the redo action.
                resetEuclideanPattern();
                getApplication().redo();
            } else {
                // Otherwise, perform the move pattern action.
                movePattern(p, 1);
            }
        }, BiColorLightState.HALF, BiColorLightState.OFF);

        //This is used for a centralized location for pattern buttons
        bindPatternButtons(driver);

    }

    //TODO DOGGY
    private void initSequenceSection(final AkaiFireDrumSeqExtension driver) {
        final RgbButton[] rgbButtons = driver.getRgbButtons();
        for (int i = 0; i < 32; i++) {
            final RgbButton button = rgbButtons[i + 32];
            final int index = i;
            button.bindPressed(mainLayer, p -> handleSeqSelection(index, p), () -> stepState(index));
        }
    }

    private void handleSeqSelection(final int index, final boolean pressed) {
        if (!clipReady()) return;
        fineNudge.logPad(index, pressed, getGridResolution(), positionHandler.getStepOffset());
        if (multiclip != null && !cursorClip.exists().get()) {
            if (pressed && !copyHeld.get() && !fixedLengthHeld.get()) {
                int velocity = accentHandler.velocityForNewStep(index);
                double duration = positionHandler.getGridResolution() * gatePercent;
                multiclip.createClip(() -> setLogicalStep(noteChannel(), index, velocity, duration));
            }
            return;
        }
        // An in-flight move is not an empty pad. Ignore new presses until confirmed.
        if (pressed && cursorClip.exists().get() && !fineNudge.editsReady(getGridResolution())) {
            oled.paramInfo("Step sync", "Waiting for note update");
            oled.clearScreenDelayed();
            return;
        }
        fineNudge.resetSelection();
        refreshStep(index);
        final NoteStep note = assignments[index];
        if (!pressed && heldSteps.stream().noneMatch(step -> step == index)) return;
        if (!pressed) {
            boolean wasTap = stepHoldGesture.releaseIsTap(index);
            heldSteps.remove(index);
            if (copyHeld.get() || fixedLengthHeld.get()) {
                // do nothing
            } else if (note != null && note.state() == State.NoteOn && !addedSteps.contains(index)) {
                if (wasTap && !modifiedSteps.contains(index)) {
                    registerManualEuclideanStep(index, false);
                    clearLogicalStep(note.channel(), index);
                } else {
                    modifiedSteps.remove(index);
                }
            }
            addedSteps.remove(index);
            modifiedSteps.remove(index);
        } else {
            heldSteps.add(index);
            stepHoldGesture.press(index);
            if (fixedLengthHeld.get()) {
                stepActionFixedLength(index);
            } else if (copyHeld.get()) {
                handleNoteCopyAction(index, note);
            } else {
                if (note == null || note.state() == State.Empty || note.state() == State.NoteSustain) {
                    registerManualEuclideanStep(index, true);
                    setLogicalStep(noteChannel(), index, accentHandler.velocityForNewStep(index),
                            positionHandler.getGridResolution() * gatePercent);
                    addedSteps.add(index);
                }
            }
        }
    }

    private void handleNoteCopyAction(final int index, final NoteStep note) {
        if (copyNote != null) {
            if (index == copyNote.x()) {
                return;
            }
            final int vel = (int) Math.round(copyNote.velocity() * 127);
            final double duration = copyNote.duration();
            expectedNoteChanges.put(noteChannel() * 32 + index, copyNote);
            registerManualEuclideanStep(index, true);
            setLogicalStep(noteChannel(), index, vel, duration);
        } else if (note != null && note.state() == State.NoteOn) {
            copyNote = NoteSnapshot.capture(note);
        }
    }

    private RgbLigthState stepState(final int index) {
        if (!clipReady()) return RgbLigthState.OFF;
        refreshStep(index);
        final int steps = positionHandler.getAvailableSteps();
        if (index < steps) {
            final State state = assignments[index] == null ? State.Empty : assignments[index].state();

            if (state == State.Empty) {
                return emptyNoteState(index);
            } else if (state == State.NoteSustain) {
                if (lengthDisplay.get()) {
                    if (index == playingStep) {
                        return padHandler.getCurrentPadColor().getBrightend();
                    }
                    return padHandler.getCurrentPadColor().getVeryDimmed();
                }
                return emptyNoteState(index);
            }

            if (copyNote != null && copyNote.x() == index) {
                if (blinkState % 4 < 2) {
                    return RgbLigthState.GRAY_1;
                }
                return padHandler.getCurrentPadColor();
            }
            if (index == playingStep) {
                return padHandler.getCurrentPadColor().getBrightend();
            }
            return padHandler.getCurrentPadColor();

        }
        return RgbLigthState.OFF;
    }

    private RgbLigthState emptyNoteState(final int index) {
        if (index == playingStep) {
            return RgbLigthState.WHITE;
        }
        if (index / 4 % 2 == 0) {
            return RgbLigthState.GRAY_1;
        } else {
            return RgbLigthState.GRAY_2;
        }
    }

    private void movePattern(final boolean pressed, final int dir) {
        if (!pressed || !clipReady()) return;
        final Set<Integer> held = heldSteps.stream().collect(Collectors.toSet());
        if (!held.isEmpty() || isAltHeld()) {
            // Fine timing keeps logical slots fixed, so generated notes retain Euclidean ownership.
            // Only pad insertion/deletion/copy transfers a slot to manual ownership.
            modifiedSteps.addAll(held);
            int moved = fineNudge.move(dir, !held.isEmpty(), fineStep -> held.isEmpty() || held.contains(
                    fineNudge.logicalStep(fineStep, getGridResolution(), positionHandler.getStepOffset())),
                    getGridResolution());
            if (moved >= 0) {
                oled.paramInfo(held.isEmpty() ? "Nudge loop" : "Nudge held", fineNudge.offsetText(),
                        fineNudge.wasLimited() ? "40% step limit" : moved > 0 ? "This hold | " + moved + " notes moved" : "No movable notes");
            } else {
                oled.paramInfo("Fine nudge", "Clip not ready / unsupported");
            }
            oled.clearScreenDelayed();
        } else {
            movePatternWhole(dir);
        }
    }

    // Rotate the visible page, leaving notes on other pages intact.
    private void movePatternWhole(final int dir) {
        if (!fineNudge.editsReady(getGridResolution())) return;
        resetEuclideanPattern();
        final List<NoteSnapshot> notes = getOnNotes().stream().map(NoteSnapshot::capture).toList();
        final int availableSteps = Math.min(32, positionHandler.getAvailableSteps());
        if (availableSteps < 1) return;
        for (NoteSnapshot note : notes) clearLogicalStep(note.channel(), note.x());

        for (final NoteSnapshot noteStep : notes) {
            int pos = noteStep.x() + dir;
            if (pos < 0) {
                pos = availableSteps - 1;
            } else if (pos >= availableSteps) {
                pos = 0;
            }
            if (!shiftActive.get()) {
                expectedNoteChanges.put(noteStep.channel() * 32 + pos, noteStep);
            }
            setLogicalStep(noteStep.channel(), pos, (int) Math.round(noteStep.velocity() * 127), noteStep.duration());
        }
    }


    private BiColorLightState getPinnedState() {
        return cursorTrack.isPinned().get() ? BiColorLightState.HALF : BiColorLightState.OFF;
    }

    private void handleClipPinning(final boolean pressed) {
        if (pressed) {
            if (multiclip != null) {
                multiclip.acquireGroup();
                return;
            }
            cursorTrack.isPinned().toggle();
            oled.paramInfo((cursorTrack.isPinned().get() ? "UNPIN" : "PIN") + " Track", "TR:" + cursorTrack.name().get());
        } else {
            oled.clearScreenDelayed();
        }
    }

    String velocityGrooveContext() {
        return cursorClip.getTrack().position().get() + ":" + cursorClip.clipLauncherSlot().sceneIndex().get()
                + ":" + cursorClip.exists().get() + ":" + padHandler.getSelectedNote()
                + ":" + positionHandler.getStepOffset() + ":" + getGridResolution()
                + ":" + cursorClip.getLoopStart().get() + ":" + cursorClip.getLoopLength().get();
    }

    void applyVelocityGroove(final VelocityGroove groove) {
        if (!clipReady() || !cursorClip.exists().get() || !fineNudge.editsReady(getGridResolution())) return;
        registerModifiedSteps(getHeldNotes());
        int steps = Math.min(32, positionHandler.getAvailableSteps());
        List<NoteStep> notes = getOnNotes();
        Set<Integer> present = new HashSet<>();
        for (NoteStep note : notes) {
            int key = note.channel() * 32 + note.x();
            present.add(key);
            note.setVelocity(groove.apply(key, positionHandler.getStepOffset() + note.x(), note.velocity()));
        }
        for (int channel = 0; channel < 16; channel++) for (int step = 0; step < steps; step++) {
            int key = channel * 32 + step;
            if (!present.contains(key)) groove.remove(key);
        }
    }

    private void handleMainEncoder(final int inc) {
        if (isShiftHeld()) {
            handleEuclideanEncoder(inc);
        } else if (accentHandler.isHolding()) {
            accentHandler.handleMainEncoder(inc);
        } else if (resolutionHandler.isHolding()) {
            resolutionHandler.handleMainEncoder(inc);
        } else {
            padHandler.handleMainEncoder(inc);
        }
    }

    /** Commit the current overlay; subsequent turns protect all notes now present. */
    void resetEuclideanPattern() {
        euclideanPattern = null;
    }

    private void registerManualEuclideanStep(final int index, final boolean present) {
        if (euclideanPattern == null) {
            return;
        }
        if (euclideanSteps != Math.min(assignments.length, positionHandler.getAvailableSteps())
                || euclideanOffset != positionHandler.getStepOffset()
                || euclideanResolution != getGridResolution()) {
            resetEuclideanPattern();
            return;
        }
        euclideanPattern.manualStep(index, present);
    }

    private void handleEuclideanEncoder(final int inc) {
        if (!clipReady()) return;
        final int steps = Math.min(assignments.length, positionHandler.getAvailableSteps());
        final int note = padHandler.getSelectedNote();
        final boolean rotating = isAltHeld();
        if (note < 0 || note >= 128) {
            oled.paramInfo("Euclidean", "Select a pad");
            oled.clearScreenDelayed();
            return;
        }
        if (rotating && !EuclideanRotations.supports(note)) {
            oled.paramInfo("Rotation", "Notes 36-52 only");
            oled.clearScreenDelayed();
            return;
        }
        if (rotating) {
            // Rotation can be prepared before creating a clip or adding any pulses.
            euclideanRotations.turn(note, inc, steps > 0 ? steps : assignments.length);
        }
        if (!cursorClip.exists().get() || steps < 1) {
            if (rotating) {
                oled.paramInfo("Rotation", "+" + euclideanRotations.get(note), getPadInfo());
                oled.clearScreenDelayed();
                return;
            }
            oled.paramInfo("Euclidean", "Select a pad + clip");
            oled.clearScreenDelayed();
            return;
        }
        if (!fineNudge.editsReady(getGridResolution())) return;
        final int offset = positionHandler.getStepOffset();
        final double resolution = getGridResolution();
        final boolean[] occupied = new boolean[steps];
        boolean mappedGrid = fineNudge.mapsGrid(resolution);
        for (int step = 0; step < steps; step++) {
            // Protect sustained notes and notes on every MIDI channel as well.
            for (int channel = 0; channel < 16; channel++) {
                State state = cursorClip.getStep(channel, step, 0).state();
                if (state == State.NoteSustain || (!mappedGrid && state == State.NoteOn)) {
                    occupied[step] = true;
                    break;
                }
            }
        }
        for (NoteStep logical : getOnNotes()) {
            if (logical.x() >= 0 && logical.x() < steps) occupied[logical.x()] = true;
        }
        if (euclideanPattern == null || euclideanSteps != steps
                || euclideanOffset != offset || euclideanResolution != resolution) {
            euclideanPattern = new EuclideanPattern(occupied);
            euclideanSteps = steps;
            euclideanOffset = offset;
            euclideanResolution = resolution;
        }
        registerModifiedSteps(getHeldNotes());
        final int rotation = Math.floorMod(euclideanRotations.get(note), steps);
        euclideanPattern.rotate(rotation, occupied,
                step -> setLogicalStep(noteChannel(), step, accentHandler.velocityForNewStep(step), resolution * gatePercent),
                step -> clearLogicalStep(noteChannel(), step));
        if (!rotating) {
            euclideanPattern.turn(inc, occupied,
                    step -> setLogicalStep(noteChannel(), step, accentHandler.velocityForNewStep(step), resolution * gatePercent),
                    step -> clearLogicalStep(noteChannel(), step));
        }
        if (rotating) {
            oled.paramInfo("Rotation", "+" + rotation, getPadInfo());
        } else {
            oled.paramInfo("Euclidean", euclideanPattern.getPulses() + "/" + steps + " R+" + rotation, getPadInfo());
        }
        oled.clearScreenDelayed();
    }

    private void handeMainEncoderPress(final boolean press) {
        if (accentHandler.isHolding()) {
            accentHandler.handeMainEncoderPress(press);
        } else if (resolutionHandler.isHolding()) {
            resolutionHandler.handeMainEncoderPress(press);
        }
    }

    public BooleanValueObject getShiftActive() {
        return shiftActive;
    }

    public BooleanValueObject getDeleteHeld() {
        return deleteHeld;
    }

    public void notifyBlink(final int blinkTicks) {
        blinkState = blinkTicks;
        if (clipHandler != null)
        clipHandler.notifyBlink(blinkTicks);
    }

    public OledDisplay getOled() {
        return oled;
    }

    private void bindEditButton(final BiColorButton button, final String name, final BooleanValueObject value,
                                final MultiStateHardwareLight stateLight, final BooleanValueObject altValue,
                                final BooleanValueObject altActionHappenedFlag) {
        if (altValue == null) {
            final FunctionInfo info1 = FunctionInfo.INFO1.get(button.getNoteAssign());
            button.bind(mainLayer, value, BiColorLightState.GREEN_FULL, BiColorLightState.OFF);
            mainLayer.bindLightState(() -> BiColorLightState.AMBER_HALF, stateLight);
            value.addValueObserver(active -> handleEditValueChanged(button, active, info1));
            mainLayer.bindLightState(
                    () -> button.isPressed() ? BiColorLightState.AMBER_FULL : BiColorLightState.AMBER_HALF, stateLight);
        } else {
            final BooleanValueObject alternateFunctionActive = new BooleanValueObject();
            final FunctionInfo info1 = FunctionInfo.INFO1.get(button.getNoteAssign());
            value.addValueObserver(active -> handleEditValueChanged(button, active, info1));
            final FunctionInfo info2 = FunctionInfo.INFO2.get(button.getNoteAssign());
            altValue.addValueObserver(active -> handleEditValueChanged(button, active, info2));
            button.bindPressed(mainLayer,
                    pressed -> handleModeButtonWithAlternatePressed(value, altValue, alternateFunctionActive,
                            altActionHappenedFlag, info1, info2, pressed),  //
                    () -> button.isPressed() ? BiColorLightState.GREEN_FULL : BiColorLightState.OFF);
            mainLayer.bindLightState(() -> {
                final boolean active = button.isPressed() && !getShiftActive().get();
                if (alternateFunctionActive.get()) {
                    return active ? BiColorLightState.RED_FULL : BiColorLightState.RED_HALF;
                }
                return active ? BiColorLightState.AMBER_FULL : BiColorLightState.AMBER_HALF;
            }, stateLight);
        }
    }

    private void handleModeButtonWithAlternatePressed(final BooleanValueObject mainValue,
                                                      final BooleanValueObject altValue,
                                                      final BooleanValueObject alternateFunctionActive,
                                                      final BooleanValueObject actionTakenFlag,
                                                      final FunctionInfo info1, final FunctionInfo info2,
                                                      final Boolean pressed) {
        if (pressed) {
            if (getShiftActive().get()) {
                alternateFunctionActive.set(!alternateFunctionActive.get());
            } else {
                alternateFunctionActive.set(false);
            }
            boolean isAlternateFunctionActive = alternateFunctionActive.get();

            mainValue.set(!alternateFunctionActive.get());
            altValue.set(alternateFunctionActive.get());

            actionTakenFlag.set(true);
            oled.functionInfo(
                    getPadInfo(),
                    isAlternateFunctionActive ? info2.getName(false) : info1.getName(false),
                    isAlternateFunctionActive ? info2.getDetail() : info1.getDetail()
            );
        }

        if (!pressed) {
            mainValue.set(false);
            if (!alternateFunctionActive.get()) {
                altValue.set(false);
            }

            actionTakenFlag.set(false);
            oled.clearScreenDelayed();
        }
    }

    public String getPadInfo() {
        return padHandler.getPadInfo();
    }

    private void handleEditValueChanged(final BiColorButton button, final boolean active, final FunctionInfo info) {
        if (active) {
            if (padHandler.notePlayingEnabled()) {
                padHandler.disableNotePlaying();
            }
            oled.functionInfo(getPadInfo(), info.getName(shiftActive.get()), info.getDetail());
        } else {
            oled.clearScreenDelayed();
            if (padHandler.notePlayingEnabled()) {
                padHandler.applyScale();
            }
        }
    }

    double getGridResolution() {
        return positionHandler.getGridResolution();
    }

    String getDetails(final List<NoteStep> heldNotes) {
        return getPadInfo() + " <" + heldNotes.size() + ">";
    }

    public void registerModifiedSteps(final List<NoteStep> notes) {
        notes.forEach(s -> modifiedSteps.add(s.x()));
    }

    List<NoteStep> getHeldNotes() {
        if (!clipReady() || !fineNudge.editsReady(getGridResolution())) return List.of();
        final Set<Integer> held = heldSteps.stream().collect(Collectors.toSet());
        return getOnNotes().stream().filter(note -> held.contains(note.x())).toList();
    }

    List<NoteStep> getOnNotes() {
        if (!clipReady() || !fineNudge.editsReady(getGridResolution())) return List.of();
        List<NoteStep> mapped = fineNudge.pageNotes(getGridResolution(), positionHandler.getStepOffset());
        if (mapped != null) return mapped;
        List<NoteStep> notes = new ArrayList<>();
        for (int step = 0; step < Math.min(32, positionHandler.getAvailableSteps()); step++) {
            for (int channel = 0; channel < 16; channel++) {
                NoteStep note = cursorClip.getStep(channel, step, 0);
                if (note.state() == State.NoteOn) notes.add(note);
            }
        }
        return notes;
    }

    public void registerPendingAction(final NoteAction action) {
        pendingAction = action;
    }

    public NoteAction getPendingAction() {
        return pendingAction;
    }

    public void clearPendingAction() {
        pendingAction = null;
    }

    private void stepActionFixedLength(final int index) {
        final double newLen = positionHandler.lengthWithLastStep(index);

        if (shiftActive.get()) {
            // NOTE: duplicate content when doubling the size of clip
            double curLen = cursorClip.getLoopLength().get();
            while (newLen % curLen == 0 && newLen > curLen) {
                curLen = curLen * 2;
                cursorClip.duplicateContent();
            }
        }

        adjustMode(newLen);
        cursorClip.getLoopLength().set(newLen);
    }

    private void adjustMode(final double clipLength) {
        final int notes = (int) (clipLength / 0.25);
        adjustMode(notes);
    }

    private void adjustMode(final int notes) {
        if (notes % 8 == 0) {
            cursorClip.launchMode().set("default");
        } else if (clipLaunchModeQuant.get()) {
            cursorClip.launchMode().set("synced");
        } else {
            cursorClip.launchMode().set("from_start");
        }
    }

    private void handleNoteStep(final NoteStep noteStep) {
        if (!clipReady() || noteStep.x() < 0 || noteStep.x() >= assignments.length) return;
        final int newStep = noteStep.x();

        refreshStep(newStep);
        final int key = noteStep.channel() * 32 + newStep;
        if (!fineNudge.mapsGrid(getGridResolution()) && noteStep.state() == State.NoteOn && expectedNoteChanges.containsKey(key)) {
            final NoteSnapshot previousStep = expectedNoteChanges.get(key);
            expectedNoteChanges.remove(key);
            previousStep.applyTo(noteStep);
        }
    }

    private void handleFineNoteStep(NoteStep note) {
        if (!clipReady() || !fineNudge.mapsGrid(getGridResolution()) || note.state() != State.NoteOn) return;
        int slot = fineNudge.logicalStep(note.x(), getGridResolution(), positionHandler.getStepOffset());
        if (slot < 0 || slot >= 32) return;
        // Pending copies are created exactly on the grid, not on an adjacent early note.
        double beat = cursorClip.getLoopStart().get() + note.x() * FineNudge.STEP_BEATS;
        if (Math.abs(beat / getGridResolution() - (positionHandler.getStepOffset() + slot)) > 1e-6) return;
        NoteSnapshot pending = expectedNoteChanges.remove(note.channel() * 32 + slot);
        if (pending != null) pending.applyTo(note);
    }

    private void handlePlayingStep(final int playingStep) {
        if (playingStep == -1) {
            this.playingStep = -1;
        }
        this.playingStep = playingStep - positionHandler.getStepOffset();
    }

    @Override
    protected void onActivate() {
        if (multiclip != null) multiclip.acquireGroup();
        currentLayer = mainLayer;
        mainLayer.activate();
        encoderLayer.activate();
        padHandler.applyScale();
    }

    @Override
    protected void onDeactivate() {
        if (multiclip != null) multiclip.deactivate();
        resetEuclideanPattern();
        currentLayer.deactivate();
        shiftLayer.deactivate();
        encoderLayer.deactivate();
        padHandler.disableNotePlaying();
    }

    public void retrigger() {
        if (clipReady()) cursorClip.launch();
    }

    public StepViewPosition getPositionHandler() {
        return positionHandler;
    }

    PinnableCursorClip getCursorClip() {
        return cursorClip;
    }

    boolean isShiftHeld() {
        return shiftActive.get();
    }

    boolean isAltHeld() {
        return altActive.get();
    }


    boolean isCopyHeld() {
        return copyHeld.get();
    }

    boolean isDeleteHeld() {
        return deleteHeld.get();
    }

    boolean isSelectHeld() {
        return selectHeld.get();
    }

    public void exitRecurrenceEdit() {
        recurrenceEditor.exitRecurrenceEdit();
    }

    public void enterRecurrenceEdit(final List<NoteStep> notes) {
        recurrenceEditor.enterRecurrenceEdit(notes);
    }

    public void updateRecurrencLength(final int length) {
        recurrenceEditor.updateLength(length);
    }

    public IntSetValue getHeldSteps() {
        return heldSteps;
    }

    public boolean isPadBeingHeld() {
        return padHandler.isPadBeingHeld();
    }

    public void registerExpectedNoteChange(final int x, final NoteSnapshot noteStep) {
        expectedNoteChanges.put(noteChannel() * 32 + x, noteStep);
    }

    public BooleanValueObject getLengthDisplay() {
        return lengthDisplay;
    }

    public void notifyMuteAction() {
        muteActionsTaken.set(true);
    }

    public void notifySoloAction() {
        soloActionsTaken.set(true);
    }

    public AccentHandler getAccentHandler() {
        return accentHandler;
    }

    public PadHandler getPadHandler() {
        return padHandler;
    }

    public Application getApplication() {return app; }

    public void setActiveRemoteControlsPage(final CursorRemoteControlsPage remoteControlsPage) {
     this.activeRemoteControlsPage = remoteControlsPage;
    }

    public CursorRemoteControlsPage getActiveRemoteControlsPage() {
        return activeRemoteControlsPage;
    }

    private void bindPatternButtons(AkaiFireDrumSeqExtension driver) {
        // Get the shared PatternButtons instance (make sure it’s created during init)
        PatternButtons patternButtons = driver.getPatternButtons();
        if (patternButtons == null) {
            host.println("PatternButtons is null in DrumSequenceMode.bindPatternButtons()");
            return;
        }
        // Bind a unified callback for the UP button:
        patternButtons.setUpCallback(pressed -> {
            if (pressed) {
                if (altActive.get()) {
                    // When Alt is held, scroll pads
                    padHandler.scrollForward(true);
                } else {
                    // Otherwise, toggle the encoder shift mode
                    encoderLayer.toggleShiftForCurrentMode();
                }
            }
        }, () -> BiColorLightState.HALF);

        // Bind a unified callback for the DOWN button:
        patternButtons.setDownCallback(pressed -> {
            if (pressed) {
                if (altActive.get()) {
                    // When Alt is held, scroll pads backward
                    padHandler.scrollBackward(true);
                } else {
                    // Otherwise, toggle the encoder shift mode
                    encoderLayer.toggleShiftForCurrentMode();
                }
            }
        }, () -> BiColorLightState.HALF);
    }


}
