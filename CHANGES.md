### Akai Fire differences from upstream
- Shift + Pad => cycle and apply color from a predefined color list 
- Shift + Clip => cycle and apply color from a predefined color list

- Shift + doubling the clip length via Last step => duplicate clip content

- Accent value is reflected on the note repeat
- Metronome is mapped to metronome button
- Pin track instead of pin clip

- **Encoder Modes** New mode: User 2 - first 4 macros from the first device in chain
- **Encoder Modes** Shift + Select Mode - enable alternative mode

- **Solo mode** Layer is active until mode changed 
- **Solo mode** Keep clip colors

- **Mute mode** Layer is active until mode changed 
- **Mute mode** Keep clip colors

### Euclidean pulse control

Hold **Shift** and turn the **Select knob** to increase/decrease Euclidean pulses
on the selected drum pad's current sequencer page (up to 32 active steps, at the
current grid resolution). A 16-step page follows the supplied T-1 patterns.
The OLED shows the pulse count and length, for example `5/16`.

Existing notes, including sustained notes, are protected. Turning back to zero
removes only the generated notes. Releasing Shift retains the pulse count so
you can resume adjusting it. Manually adding or copying steps keeps the pulse
count and protects those notes from the generator, even before Bitwig reports
the new notes. Manually deleting a step also keeps the pulse count. Changing
pad/clip/page/grid starts a fresh overlay, treating existing notes as protected.

Regression checks: `mvn -o clean package`, then
`java -cp target/classes:target/test-classes com.akai.fire.sequence.EuclideanPatternChecks`.


### Euclidean rotation

Hold **Shift + Alt** and turn **Select** to rotate the Euclidean starting step.
Set this before adding pulses, or rotate the current generated pattern without
moving manual notes. With 16 steps, rotation +2 and four pulses places hits at
3, 7, 11, 15. Rotation wraps at the active page length.

Drum MIDI notes 36–52 each have their own rotation stored in Bitwig's
project document settings (Euclidean pad rotation). Save the project to retain
these offsets; saving a template provides defaults for future songs. Swapping
Drum Rack devices does not change the offsets. These settings are shared by
racks using the same note positions in that project. Only rotation is persisted;
existing clip notes remain protected when a fresh overlay starts.

Settings regression check (after building):
`java -cp target/classes:target/test-classes:$HOME/.m2/repository/com/bitwig/extension-api/20/extension-api-20.jar com.akai.fire.sequence.EuclideanRotationChecks`.

### Velocity groove

Hold **STEP SEQ**, press **Select** to switch between Groove shape and Groove
amount; turn Select to edit the displayed field. Normal and Accent velocity are
configured in the extension preferences under **General velocity** (defaults
100 and 127, independently adjustable from 1 to 127). Changes also update the
active note-repeat input velocity.
Pressing Select while held does not toggle Accent on release. A plain STEP SEQ
press still toggles Accent. Shift + Select still controls Euclidean pulses.

The four shapes are **Agogo, Timbales, Congas, Bongo**, reconstructed from the
16 bar heights in [Torso's illustrations](https://docs.torsoelectronics.com/t1/parameter-reference/groove/accent-groove/).
These are diagram-derived approximations, not extracted firmware presets.
Each contour repeats every **16 grid steps** (one bar at 1/16 resolution).
This version does not implement T-1's adjustable groove length or interpolation.

Amount (0–100%) applies bipolar variation around each note's original velocity,
clamped to MIDI 1–127. Only note velocities on the selected pad's **current page**
are edited; rests, timing, other pads, and Euclidean ownership stay intact.
New Fire/Euclidean steps inherit the active groove. Amount zero restores the
baseline during the current edit context; manual velocity edits become a new
baseline. Changing pad/clip/page/grid/loop context starts a fresh overlay at 0%.
The resulting note velocities save with the clip; shape/amount and the original
velocity snapshot are temporary, and are not saved across extension reloads.

Check with `java -cp target/classes:target/test-classes com.akai.fire.sequence.VelocityGrooveChecks`.
