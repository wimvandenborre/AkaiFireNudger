package com.akai.fire.sequence;

import com.akai.fire.lights.BiColorLightState;
import com.bitwig.extension.controller.api.Preferences;
import com.bitwig.extensions.framework.values.BooleanValueObject;

public class AccentHandler {
	private int velStandard = 100;
	private int velAccented = 127;
	private final BooleanValueObject accentActive = new BooleanValueObject();
	private boolean accenButtonHeld = false;
	private boolean modified = false;
	private final DrumSequenceMode parent;
	private int velPointer = 0;
	private VelocityGroove groove = new VelocityGroove();
	private String grooveContext = "";

	public AccentHandler(final DrumSequenceMode drumSequenceMode) {
		this.parent = drumSequenceMode;
	}

    void initPreferences(final Preferences preferences) {
        preferences.getNumberSetting("Normal velocity", "General velocity", 1, 127, 1, "", 100)
                .addRawValueObserver(value -> {
                    velStandard = (int) Math.round(value);
                    updateInputVelocity();
                });
        preferences.getNumberSetting("Accent velocity", "General velocity", 1, 127, 1, "", 127)
                .addRawValueObserver(value -> {
                    velAccented = (int) Math.round(value);
                    updateInputVelocity();
                });
    }

    private void updateInputVelocity() {
        parent.getPadHandler().getNoteRepeaterHandler().setNoteInputVelocity(getCurrenVel());
    }

	public int getCurrenVel() {
		return accentActive.get() ? velAccented : velStandard;
	}

	BiColorLightState getLightState() {
		return accentActive.get() ? BiColorLightState.AMBER_FULL : BiColorLightState.AMBER_HALF;
	}

	public boolean isHolding() {
		return accenButtonHeld;
	}

	void handlePressed(final boolean pressed) {
		if (!pressed) {
			if (!modified) {
				accentActive.toggle();
				this.parent.getPadHandler().getNoteRepeaterHandler().setNoteInputVelocity(this.getCurrenVel());
			}
			parent.getOled().clearScreenDelayed();
			modified = false;
		} else {
			displayAccentInfo();
		}
		accenButtonHeld = pressed;
	}

	private void displayAccentInfo() {
        final VelocityGroove current = currentGroove();
        final String[] labels = {"Groove shape", "Groove amount"};
        final String[] values = {VelocityGroove.NAMES[current.shape()], current.amount() + "%"};
        parent.getOled().paramInfo(labels[velPointer], values[velPointer], "Press Select: next");
    }

    private VelocityGroove currentGroove() {
        String context = parent.velocityGrooveContext();
        if (!context.equals(grooveContext)) {
            grooveContext = context;
            groove = new VelocityGroove();
        }
        return groove;
    }

    int velocityForNewStep(int step) {
        return currentGroove().newNote(step, parent.getPositionHandler().getStepOffset() + step, getCurrenVel());
    }

	void handleMainEncoder(final int inc) {
		if (!accenButtonHeld) {
			return;
		}
        VelocityGroove current = currentGroove();
        if (velPointer == 0) current.turnShape(inc);
        else current.turnAmount(inc);
        parent.applyVelocityGroove(current);
        displayAccentInfo();
		modified = true;
		this.parent.getPadHandler().getNoteRepeaterHandler().setNoteInputVelocity(this.getCurrenVel());
	}

	void handeMainEncoderPress(final boolean pressed) {
		if (!accenButtonHeld || !pressed) {
			return;
		}
		modified = true;
		velPointer = (velPointer + 1) % 2;
		displayAccentInfo();
	}

}
