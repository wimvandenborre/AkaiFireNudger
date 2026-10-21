package com.akai.fire.sequence;

import com.akai.fire.lights.BiColorLightState;
import com.bitwig.extension.controller.api.CursorRemoteControlsPage;
import com.bitwig.extensions.framework.values.BooleanValueObject;

public class NoteRepeatHandler {

	private final DrumSequenceMode parent;
	private final BooleanValueObject noteRepeatActive = new BooleanValueObject();
	private boolean repeatButtonHeld = false;
	private double currentArpRate = ARP_RATES[1];
	private static final double[] ARP_RATES = new double[] { 0.125, 0.25, 0.5, 1.0, 1.0 / 12, 1.0 / 6, 1.0 / 3,
			2.0 / 3 };
	private static final String[] GRID_RATES_STR = new String[] { "1/32", "1/16", "1/8", "1/4", //
			"1/32T", "1/16T", "1/8T", "1/4T" };
	private final NoteRepeatInput repeatInput;
	private int selectedArpIndex;

    public NoteRepeatHandler(final NoteRepeatInput input, final DrumSequenceMode drumSequenceMode) {
        parent = drumSequenceMode;
        selectedArpIndex = 1;
        repeatInput = input;
        repeatInput.velocity(parent.getAccentHandler().getCurrenVel());
        repeatInput.rate(currentArpRate);
    }

	BiColorLightState getLightState() {
		return noteRepeatActive.get() ? BiColorLightState.HALF : BiColorLightState.OFF;
	}

	void handlePressed(final boolean pressed) {
		if (pressed) {
			noteRepeatActive.toggle();
			if (noteRepeatActive.get()) {
				parent.getOled().valueInfo("Note Repeat", GRID_RATES_STR[selectedArpIndex]);
			}
		} else {
			parent.getOled().clearScreenDelayed();
		}
		repeatButtonHeld = pressed;
	}

	boolean isHolding() {
		return repeatButtonHeld;
	}

	public BooleanValueObject getNoteRepeatActive() {
		return noteRepeatActive;
	}

	//BUG Pagename is not updated correctly

	void handleMainEncoder(final int inc, final boolean altHeld) {
		if (altHeld) {
			CursorRemoteControlsPage remotePage = parent.getActiveRemoteControlsPage();
			if (remotePage != null) {
				int pageCount = remotePage.pageCount().getAsInt();
				int currentPage = remotePage.selectedPageIndex().get();

				int newPage = currentPage + inc;
				if (newPage >= 0 && newPage < pageCount) {
					remotePage.selectedPageIndex().set(newPage);
					String pageName = remotePage.pageNames().get(newPage);
					parent.getOled().valueInfo("Remote Page", pageName);
				}
			}
		} else {
			// Regular behavior...
			final int newValue = selectedArpIndex + inc;
			if (newValue >= 0 && newValue < ARP_RATES.length) {
				selectedArpIndex = newValue;
				setNoteRateValue(newValue);
			}
		}
	}



	private void setNoteRateValue(final int index) {
		this.selectedArpIndex = index;
		this.currentArpRate = ARP_RATES[index];
		repeatInput.rate(currentArpRate);
		parent.getOled().valueInfo("Note Repeat", GRID_RATES_STR[index]);
	}

    public void activate() { repeatInput.activate(); }

    public void deactivate() { repeatInput.deactivate(); }

    public void setNoteInputVelocity(final int velocity) { repeatInput.velocity(velocity); }
}
