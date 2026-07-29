package com.akai.fire.sequence;

import com.akai.fire.AkaiFireDrumSeqExtension;
import com.akai.fire.ColorLookup;
import com.akai.fire.DiagnosticLog;
import com.akai.fire.control.RgbButton;
import com.akai.fire.lights.RgbLigthState;
import com.bitwig.extension.api.Color;
import com.bitwig.extension.controller.api.ClipLauncherSlot;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.DrumPad;
import com.bitwig.extension.controller.api.DrumPadBank;
import com.bitwig.extensions.framework.Layer;
import com.bitwig.extensions.framework.values.BooleanValueObject;

import java.util.HashSet;
import java.util.Set;

public class PadRowMuteHandler {

    private final DrumSequenceMode parent;
    private final DrumPadBank padBank;
    private final RgbLigthState[] slotColors = new RgbLigthState[16];
    private final BooleanValueObject soloMode = new BooleanValueObject(false);
    private final ControllerHost host;
    private final DiagnosticLog diagnosticLog;
    private final DrumPad[] drumPads = new DrumPad[16];
    private final boolean[] muteStatesBeforeSolo = new boolean[16];
    private final Set<Integer> manuallySoloedPads = new HashSet<>();

    public PadRowMuteHandler(final AkaiFireDrumSeqExtension driver, final DrumSequenceMode parent, final Layer muteLayer) {
        this.parent = parent;
        host = driver.getHost();
        diagnosticLog = driver.getDiagnosticLog();
        padBank = driver.getViewControl().getDrumPadBank();
        final RgbButton[] rgbButtons = driver.getRgbButtons();

        // Initialize slotColors[0] to the red color ("#d92e24")
        slotColors[0] = ColorLookup.getColor(Color.fromHex("#d92e24"));
        slotColors[1] = ColorLookup.getColor(Color.fromHex("#3ebb62"));

        // Using the first 16 buttons for mute handling (i.e. the second row: indices 16-31)
        for (int i = 0; i < 16; i++) {
            final int padIndex = i;
            final RgbButton button = rgbButtons[i + 16];
            final DrumPad drumPad = padBank.getItemAt(i);
            drumPads[i] = drumPad;
            drumPad.mute().markInterested();
            drumPad.solo().markInterested();
            drumPad.isMutedBySolo().markInterested();
            drumPad.mute().addValueObserver(value ->
                    diagnosticLog.log("PAD_STATE index=" + padIndex + " mute=" + value));
            drumPad.solo().addValueObserver(value ->
                    diagnosticLog.log("PAD_STATE index=" + padIndex + " solo=" + value));
            drumPad.isMutedBySolo().addValueObserver(value ->
                    diagnosticLog.log("PAD_STATE index=" + padIndex + " mutedBySolo=" + value));

            // The second row defaults to mute and can be latched into solo mode.
            button.bind(muteLayer, () -> {
                logPadState("PAD_COMMAND_BEFORE", padIndex, drumPad);
                if (soloMode.get()) {
                    toggleManualSolo(padIndex);
                    diagnosticLog.log("PAD_COMMAND index=" + padIndex + " action=TOGGLE_MANUAL_SOLO");
                    parent.notifySoloAction();
                } else {
                    drumPad.mute().toggle();
                    diagnosticLog.log("PAD_COMMAND index=" + padIndex + " action=TOGGLE_MUTE");
                    parent.notifyMuteAction();
                }
                host.scheduleTask(() -> logPadState("PAD_COMMAND_AFTER_100MS", padIndex, drumPad), 100);
            });

            // Active mutes are red; active solos are green.
            button.bindLight(muteLayer, () -> {
                if (soloMode.get()) {
                    return manuallySoloedPads.contains(padIndex) ? slotColors[1] : RgbLigthState.OFF;
                }
                return drumPad.mute().get() ? slotColors[0] : RgbLigthState.OFF;
            });
        }
    }

    public void handleModeButton(final boolean pressed) {
        diagnosticLog.log("MODE_BUTTON_EVENT pressed=" + pressed + " current=" + getModeName());
        if (!pressed) {
            return;
        }
        final String before = getModeName();
        soloMode.toggle();
        diagnosticLog.log("MODE_BUTTON before=" + before + " after=" + getModeName());
    }

    public boolean isSoloMode() {
        return soloMode.get();
    }

    private String getModeName() {
        return soloMode.get() ? "SOLO" : "MUTE";
    }

    private void toggleManualSolo(final int padIndex) {
        if (manuallySoloedPads.isEmpty()) {
            for (int i = 0; i < drumPads.length; i++) {
                muteStatesBeforeSolo[i] = drumPads[i].mute().get();
            }
            diagnosticLog.log("MANUAL_SOLO capturedMuteState");
        }

        if (!manuallySoloedPads.add(padIndex)) {
            manuallySoloedPads.remove(padIndex);
        }

        if (manuallySoloedPads.isEmpty()) {
            for (int i = 0; i < drumPads.length; i++) {
                drumPads[i].mute().set(muteStatesBeforeSolo[i]);
            }
            diagnosticLog.log("MANUAL_SOLO cleared restoredMuteState");
            return;
        }

        for (int i = 0; i < drumPads.length; i++) {
            drumPads[i].mute().set(!manuallySoloedPads.contains(i));
        }
        diagnosticLog.log("MANUAL_SOLO activePads=" + manuallySoloedPads);
    }

    private void logPadState(final String event, final int padIndex, final DrumPad drumPad) {
        diagnosticLog.log(event + " index=" + padIndex + " mode=" + getModeName()
                + " mute=" + drumPad.mute().get()
                + " solo=" + drumPad.solo().get()
                + " mutedBySolo=" + drumPad.isMutedBySolo().get()
                + " manualSolo=" + manuallySoloedPads.contains(padIndex));
    }

    private Color getSlotColor(ClipLauncherSlot slot) {
        Color[] colors = {
                Color.fromHex("#d92e24"), // red
                Color.fromHex("#ff5706"), // orange
                Color.fromHex("#44c8ff"), // dark blue
                Color.fromHex("#0099d9"), // light blue
                Color.fromHex("#009d47"), // dark green
                Color.fromHex("#3ebb62"), // light green
                Color.fromHex("#d99d10"), // yellow
                Color.fromHex("#c9c9c9"), // white
                Color.fromHex("#5761c6"), // dark purple
                Color.fromHex("#bc76f0"), // light purple
        };
        Color currentColor = slot.color().get();

        int colorIndex = 0;
        for (int i = 0; i < colors.length; i++) {
            if (colors[i].toHex().equals(currentColor.toHex())) {
                colorIndex = i + 1;
            }
        }
        if (colorIndex == colors.length) {
            colorIndex = 0;
        }
        return colors[colorIndex];
    }
}
