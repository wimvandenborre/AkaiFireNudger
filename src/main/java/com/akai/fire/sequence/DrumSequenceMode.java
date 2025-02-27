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
import com.bitwig.extension.controller.api.*;
import com.bitwig.extension.controller.api.NoteStep.State;
import com.bitwig.extensions.framework.Layer;
import com.bitwig.extensions.framework.values.BooleanValueObject;
import com.bitwig.extensions.framework.values.StepViewPosition;

import java.util.*;
import java.util.stream.Collectors;

public class DrumSequenceMode extends Layer {

    private final ControllerHost host;

    private final IntSetValue heldSteps = new IntSetValue();
    private final Set<Integer> addedSteps = new HashSet<>();
    private final Set<Integer> modifiedSteps = new HashSet<>();
    private final HashMap<Integer, NoteStep> expectedNoteChanges = new HashMap<>();
    // Maintain fractional offsets for held notes.
    private final Map<NoteStep, Double> fractionalOffsets = new HashMap<>();
    private final Map<Integer, Map<Integer, Integer>> currentNotesInClip = new HashMap<>();

    private final NoteStep[] assignments = new NoteStep[32];
    private final double originalStepSize = 1.0 / 32.0;

    private final OledDisplay oled;

    private final Layer mainLayer;
    private final Layer shiftLayer;
    private Layer currentLayer;
    private final Layer muteLayer;
    private final Layer soloLayer;
    private final SequencEncoderHandler encoderLayer;

    private final CursorTrack cursorTrack;
    private final PinnableCursorClip cursorClip;
    private  Clip bigCursorClip;

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
    private NoteStep copyNote = null;
    private int blinkState;


    public DrumSequenceMode(final AkaiFireDrumSeqExtension driver) {

        super(driver.getLayers(), "DRUM_SEQUENCE_LAYER");
        host = driver.getHost();
        oled = driver.getOled();
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
        cursorClip = cursorTrack.createLauncherCursorClip("SQClip", "SQClip", 32, 1);
        bigCursorClip = host.createLauncherCursorClip( 16*8*2*2,128);
        bigCursorClip.setStepSize(1.0 / 64.0);
        bigCursorClip.addStepDataObserver(this::observingNotes);
        bigCursorClip.scrollToKey(0);



        cursorClip.addNoteStepObserver(this::handleNoteStep);
        cursorClip.playingStep().addValueObserver(this::handlePlayingStep);
        cursorClip.getLoopLength().addValueObserver(clipLength -> {
            if (markIgnoreOrigLen) {
                markIgnoreOrigLen = false;
            }
        });
        cursorClip.isPinned().markInterested();

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
    }


    private void observingNotes(int x, int y, int state) {


        host.println("Observing note: x=" + x + ", y=" + y + ", stat=" + state);
        // Get or create the inner map for step x.
        Map<Integer, Integer> stepNotes = currentNotesInClip.get(x);
        if (stepNotes == null) {
            stepNotes = new HashMap<>();
            currentNotesInClip.put(x, stepNotes);
        }
        if (state == State.Empty.ordinal()) {
            stepNotes.remove(y);
            host.println("Removed note at x=" + x + ", y=" + y);
        } else {
            stepNotes.put(y, state);
            host.println("Stored note at x=" + x + ", y=" + y + ", stat=" + state);
        }
    }


    private void initModeButtons(final AkaiFireDrumSeqExtension driver) {
        final MultiStateHardwareLight[] stateLights = driver.getStateLights();
        bindEditButton(driver.getButton(NoteAssign.MUTE_1), "Select", selectHeld, stateLights[0], muteMode,
                muteActionsTaken);
        bindEditButton(driver.getButton(NoteAssign.MUTE_2), "Last Step", fixedLengthHeld, stateLights[1], soloMode,
                soloActionsTaken);
        bindEditButton(driver.getButton(NoteAssign.MUTE_3), "Copy", copyHeld, stateLights[2], null, null);
        bindEditButton(driver.getButton(NoteAssign.MUTE_4), "Delete/Reset", deleteHeld, stateLights[3], null, null);
        final BiColorButton deleteButton = driver.getButton(NoteAssign.MUTE_4);
        deleteButton.bind(mainLayer, deleteHeld, BiColorLightState.GREEN_FULL, BiColorLightState.OFF);
    }

    private void initButtonBehaviour(final AkaiFireDrumSeqExtension driver) {

        final BiColorButton accentButton = driver.getButton(NoteAssign.STEP_SEQ); // TODO combine with encoder
        accentButton.bindPressed(mainLayer, accentHandler::handlePressed, accentHandler::getLightState);

        final BiColorButton shiftButton = driver.getButton(NoteAssign.SHIFT);
        shiftButton.bind(mainLayer, shiftActive, BiColorLightState.GREEN_HALF, BiColorLightState.OFF);

        final BiColorButton clipLaunchModeButton = driver.getButton(NoteAssign.NOTE);
        clipLaunchModeButton.bindToggle(mainLayer, clipLaunchModeQuant, BiColorLightState.AMBER_FULL,
                BiColorLightState.AMBER_HALF, oled,
                new DisplayInfo().addLine("Clip Legato", 2, 0, TextJustification.CENTER)//
                        .addLine(() -> clipLaunchModeQuant.get() ? "with quant" : "immediate", 2, 3,
                                TextJustification.CENTER)//
                        .create());

        final BiColorButton retrigButton = driver.getButton(NoteAssign.DRUM);
        retrigButton.bind(mainLayer, this::retrigger, BiColorLightState.AMBER_FULL, BiColorLightState.AMBER_HALF);

        final BiColorButton pinButton = driver.getButton(NoteAssign.ALT);
        pinButton.bindPressed(mainLayer, this::handleClipPinning, this::getPinnedState);

        final BiColorButton resolutionButton = driver.getButton(NoteAssign.PERFORM);
        resolutionButton.bindPressed(mainLayer, resolutionHandler::handlePressed, resolutionHandler::getLightState);

        final BiColorButton shiftLeftButton = driver.getButton(NoteAssign.BANK_L);
        shiftLeftButton.bindPressed(mainLayer, p -> movePattern(p, -1), BiColorLightState.HALF, BiColorLightState.OFF);
        final BiColorButton shiftRightButton = driver.getButton(NoteAssign.BANK_R);
        shiftRightButton.bindPressed(mainLayer, p -> movePattern(p, 1), BiColorLightState.HALF, BiColorLightState.OFF);
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
        final NoteStep note = assignments[index];
        if (!pressed) {
            heldSteps.remove(index);
            if (copyHeld.get() || fixedLengthHeld.get()) {
                // do nothing
            } else if (note != null && note.state() == State.NoteOn && !addedSteps.contains(index)) {
                if (!modifiedSteps.contains(index)) {
                    cursorClip.toggleStep(index, 0, accentHandler.getCurrenVel());
                } else {
                    modifiedSteps.remove(index);
                }
            }
            addedSteps.remove(index);
        } else {
            heldSteps.add(index);
            if (fixedLengthHeld.get()) {
                stepActionFixedLength(index);
            } else if (copyHeld.get()) {
                handleNoteCopyAction(index, note);
            } else {
                if (note == null || note.state() == State.Empty || note.state() == State.NoteSustain) {
                    cursorClip.setStep(index, 0, accentHandler.getCurrenVel(),
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
            expectedNoteChanges.put(index, copyNote);
            cursorClip.setStep(index, 0, vel, duration);
        } else if (note != null && note.state() == State.NoteOn) {
            copyNote = note;
        }
    }

    private RgbLigthState stepState(final int index) {
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

    // Declare a field to hold the original (normal) step size.
    // private final double originalStepSize = 1.0 / 16.0; // one grid step = 1/16 beat (a 64th note)

    // Modify your movePattern method to choose between whole and fractional shifting:
    private void movePattern(final boolean pressed, final int dir) {
        if (pressed) {
            return;
        }
        if (!getHeldNotes().isEmpty()) {
            movePatternFractional(bigCursorClip, dir);
        } else {
            movePatternWhole(dir);
        }
    }

    // Existing whole-step shifting (unchanged):
    private void movePatternWhole(final int dir) {
        final List<NoteStep> notes = getOnNotes();
        final int availableSteps = positionHandler.getAvailableSteps();
        cursorClip.clearStepsAtY(0, 0);

        for (final NoteStep noteStep : notes) {
            int pos = noteStep.x() + dir;
            if (pos < 0) {
                pos = availableSteps - 1;
            } else if (pos >= availableSteps) {
                pos = 0;
            }
            if (!shiftActive.get()) {
                expectedNoteChanges.put(pos, noteStep);
            }
            cursorClip.setStep(pos, 0, (int) Math.round(noteStep.velocity() * 127), noteStep.duration());
        }
    }


    /**
     * Returns the allowed lower bound in the fine grid for a given normal note (1–32).
     * For normal note n, we define:
     *     lowerBound = n * 16 - 7
     */
    private int getAllowedLowerBound(int normalNote) {
        return normalNote * 16 - 7;
    }

    /**
     * Returns the allowed upper bound in the fine grid for a given normal note (1–32).
     * For normal note n, we define:
     *     upperBound = n * 16 + 8
     * (Clamped to 511 since our fine grid goes from 0 to 511.)
     */
    private int getAllowedUpperBound(int normalNote) {
        return Math.min(511, normalNote * 16 + 8);
    }

    /**
     * Maps a fine-grid coordinate (0–511) to its corresponding normal note (pad) number (1–32)
     * using our desired mapping.
     * The allowed range for normal note n is from getAllowedLowerBound(n) to getAllowedUpperBound(n).
     * If the fine coordinate is outside the overall range, returns -1.
     */
    private int mapFineToNormal(int fine) {
        int overallLower = getAllowedLowerBound(1);    // For normal note 1, lower bound = 16 - 7 = 9.
        int overallUpper = getAllowedUpperBound(32);   // For normal note 32, ideally = 32*16+8 = 520, clamped to 511.
        if (fine < overallLower || fine > overallUpper) {
            return -1;
        }
        // Using the formula: normalNote = floor((fine + 7) / 16)
        int normalNote = (fine + 7) / 16;
        // Clamp to valid range
        if (normalNote < 1) {
            normalNote = 1;
        }
        if (normalNote > 32) {
            normalNote = 32;
        }
        return normalNote;
    }


    /**
     * Nudges (moves) notes in the fine grid while keeping them within the allowed
     * range for their corresponding normal note. The allowed range for a given normal note _n_
     * is from getAllowedLowerBound(n) to getAllowedUpperBound(n). We only process notes
     * that have a state of 2 (in our filtered inner map) and only if their normal note is held.
     */

    private void movePatternFractional(Clip clip, int dir) {
        // Iterate over a copy of the currentNotesInClip keys (fine-grid coordinates: 0–511)
        for (Integer fineX : new ArrayList<>(currentNotesInClip.keySet())) {
            Map<Integer, Integer> stepNotes = currentNotesInClip.get(fineX);
            if (stepNotes == null)
                continue;

            host.println("Fine x = " + fineX + " with stepNotes: " + stepNotes);

            // Filter inner map: only keep entries where stat == 2.
            List<Integer> filteredY = stepNotes.entrySet().stream()
                    .filter(entry -> entry.getValue() == 2)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            if (filteredY.isEmpty()) {
                host.println("No stat==2 for fine x = " + fineX);
                continue;
            }

            // Map the current fine coordinate to its normal note (1–32)
            int normalNote = mapFineToNormal(fineX);
            if (normalNote == -1) {
                host.println("Fine x = " + fineX + " falls in the gap; skipping.");
                continue;
            }

            // (Optional) You could check if this normal note is held; if your heldSteps are numbers 1–32:
            // if (!heldSteps.contains(normalNote)) { ... }

            // Determine the allowed fine coordinate range for this normal note.
            int lowerBound = getAllowedLowerBound(normalNote);
            int upperBound = getAllowedUpperBound(normalNote);

            // Compute the tentative new fine coordinate by applying the nudge.
            int tentativeFine = fineX + dir;
            // Clamp the new fine coordinate to remain within the allowed block.
            int newFineX = Math.max(lowerBound, Math.min(tentativeFine, upperBound));
            int delta = newFineX - fineX;

            host.println("For normal note " + normalNote + " (allowed fine range "
                    + lowerBound + "-" + upperBound + "): moving note from fine "
                    + fineX + " to " + newFineX + " (delta " + delta + ")");

            // Move each filtered note by the computed delta.
            for (Integer y : filteredY) {
                try {
                    clip.moveStep(fineX, 36, delta, 0);
                } catch (Exception e) {
                    host.errorln("Error moving note at fineX " + fineX + " y = " + y + ": " + e.getMessage());
                }
            }
        }
    }


    private BiColorLightState getPinnedState() {
        return cursorTrack.isPinned().get() ? BiColorLightState.HALF : BiColorLightState.OFF;
    }

    private void handleClipPinning(final boolean pressed) {
        if (pressed) {
            cursorTrack.isPinned().toggle();
            oled.paramInfo((cursorTrack.isPinned().get() ? "UNPIN" : "PIN") + " Track", "TR:" + cursorTrack.name().get());
        } else {
            oled.clearScreenDelayed();
        }
    }

    private void handleMainEncoder(final int inc) {
        if (accentHandler.isHolding()) {
            accentHandler.handleMainEncoder(inc);
        } else if (resolutionHandler.isHolding()) {
            resolutionHandler.handleMainEncoder(inc);
        } else {
            padHandler.handleMainEncoder(inc);
        }
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
        return heldSteps.stream()
                // Only use indices within 0 to 31.
                .filter(idx -> idx >= 0 && idx <= 31)
                .map(idx -> assignments[idx])
                .filter(ns -> ns != null && ns.state() == State.NoteOn)
                .collect(Collectors.toList());
    }

    List<NoteStep> getOnNotes() {
        return Arrays.stream(assignments)
                .filter(ns -> ns != null && ns.state() == State.NoteOn)
                .collect(Collectors.toList());
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
       int jaja =  noteStep.x();
        final int newStep = noteStep.x();

        assignments[newStep] = noteStep;
        if (expectedNoteChanges.containsKey(newStep)) {
            final NoteStep previousStep = expectedNoteChanges.get(newStep);
            expectedNoteChanges.remove(newStep);
            applyValues(noteStep, previousStep);
        }
    }

    private void applyValues(final NoteStep dest, final NoteStep src) {
        // TODO: this is a bug, somewhere the chance is lost
        dest.setChance(1); // src.chance()
        dest.setTimbre(src.timbre());
        dest.setPressure(src.pressure());
        dest.setRepeatCount(src.repeatCount());
        dest.setRepeatVelocityCurve(src.repeatVelocityCurve());
        dest.setPan(src.pan());
        dest.setRepeatVelocityEnd(src.repeatVelocityEnd());
        dest.setRecurrence(src.recurrenceLength(), src.recurrenceMask());
        dest.setOccurrence(src.occurrence());
    }

    private void handlePlayingStep(final int playingStep) {
        if (playingStep == -1) {
            this.playingStep = -1;
        }
        this.playingStep = playingStep - positionHandler.getStepOffset();
    }

    @Override
    protected void onActivate() {
        currentLayer = mainLayer;
        mainLayer.activate();
        encoderLayer.activate();
        padHandler.applyScale();
    }

    @Override
    protected void onDeactivate() {
        currentLayer.deactivate();
        shiftLayer.deactivate();
        encoderLayer.deactivate();
        padHandler.disableNotePlaying();
    }

    public void retrigger() {
        cursorClip.launch();
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

    public void registerExpectedNoteChange(final int x, final NoteStep noteStep) {
        expectedNoteChanges.put(noteStep.x(), noteStep);
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
}