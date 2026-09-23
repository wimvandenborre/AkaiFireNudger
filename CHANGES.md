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

### Multi-clip drums on a group rack

Select **Sequencer → Clip source (reload extension) → Group child tracks** in
FireNudger's preferences, then reload the extension. **Selected track** remains
the default and retains the existing single-clip workflow.

Put the Drum Machine on a group track and route its direct child note tracks
into that group instrument. Select the group (or one of its children) before
loading the extension. It finds and pins the parent group for drum-pad controls;
a separate cursor edits child clips. Tracks and routing must already exist.
The first 16 direct-child positions map to notes **36–51** and new notes use
MIDI channels **1–16**, respectively, matching Oikontrol. Audio tracks and nested
groups are not editable lanes; they still occupy their child position. Pad
positions beyond note 51 have no child mapping.

The existing layout and controls remain: first row selects the group drum pads,
last two rows edit the selected child's 32-step page. Pad mute/solo, macros,
accent, repeat, Euclidean pulses/rotation, velocity groove, note properties,
copy and clear continue to operate in their existing roles. Each child clip has
its own loop length. Press a step in an empty child slot to create a four-beat
clip and insert that step. Edits wait for both clip cursors to settle; changing
lanes cancels deferred edits. Copies snapshot note properties before moving the
cursor, retaining velocity, duration, chance, recurrence and repeat settings.

With **Functionalities → Second Row → ClipLaunch-Row** (reload after changing):

- Clip pads select/launch or create a clip on the selected child, retaining the
  scene index when switching drum pads. The row lights show that child's clips.
- **Alt + clip pad** launches existing clips in that scene across eligible
  children and creates four-beat clips in empty child slots. Newly created
  empty clips are not automatically launched; press again to launch them.
- Copy, Delete (clear), Shift + Delete (remove), and Shift (cycle color) act on
  the selected child, as in the single-track workflow.
- In child-track mode, **STOP** reacquires the group from the current editor
  selection. Select a different group in Bitwig and press STOP to switch racks.
  In single-track mode STOP retains its track-pin toggle.

### Fine nudging

The Oikontrol fine-cursor approach replaces the old hard-coded note-36 nudge.
Both clip sources support **held step(s) + Bank left/right** for fine nudging,
and **Alt + Bank left/right** with no held steps for the selected drum's entire
loop. One press moves **1/64 beat** (the source code's timing unit), preserving
note properties and MIDI channels. The selected drum pitch, current grid/page,
and nonzero loop start are respected. Notes wrap within the loop; moves that
would overwrite an unmoved note are skipped. A completely filled fine grid is
left unchanged. Fine nudging supports loops up to 64 beats with loop start and
length aligned to that fine grid; other loops show a notification.

Bank left/right without held steps or Alt still rotates the visible page by
one grid step. Notes on other pages remain intact. Shift + Bank retains
Undo/Redo, once per press. Nudges execute on arrow press, so releasing the step
and arrow in either order preserves the note. A short step tap still toggles it;
a hold of 250 ms or longer preserves it even without an edit. Repeated nudges
keep following held notes across grid cells and the loop boundary.

The OLED shows the signed cumulative movement during the current hold, e.g.
`-3/64 beat` for earlier or `+2/64 beat` for later, for both held steps and
Alt + arrows across the selected drum's loop. This is relative to the beginning
of the gesture, not an absolute grid offset. Changing the held selection,
releasing/pressing Alt, or changing clip/pitch resets the measurement. If a
collision prevents only some notes from moving, the display shows their offset
range. Unavailable cursors and zero-move operations show feedback too.

Run all regression checks and produce `target/FireNudger.bwextension` with
`./scripts/check-sequencer.sh` (Java 17 and cached Maven dependencies required).
The multi-clip implementation and fine-nudge approach were adapted from
Oiko Audio's Oikontrol v2.23.0; its MIT copyright notices are retained in LICENSE.

Hardware acceptance check: use a group with at least two note children and a
shared Drum Machine, edit different scenes and loop lengths, copy between pads,
try held-step and Alt lane nudges on notes 36 and 37, and switch lanes while
holding a step. Verify group macros/mute/solo, Euclidean controls, groove, and
the default single-track mode in Bitwig. Automated checks simulate cursor
settling and note operations; they do not replace this live controller check.

### Group clip selection follow-up

Group mode now finds a selected child clip at startup, falling back to a playing
clip and then the first populated child slot. It no longer always opens empty
Scene 1. Clicking a different child clip in Bitwig updates the Fire's lane and
scene, including when the second row is configured as Mute-Row. Fire lane
selection is independent of which drum pad Bitwig's device editor has selected.
Group acquisition, target selection and cursor readiness are recorded in
`FireNudger.log` for runtime troubleshooting.

Verified against the running project after installation: the extension found
Group 1's Kick Midi child, selected S2 in Scene 2, and reported both cursors ready.

Group child banks exclude the group master, keeping child lane and drum-pad mappings aligned.

### Safe extension installation

Build and check with `./scripts/check-sequencer.sh`, then install with
`python3 scripts/install-extension.py`. The checks run against the packaged JAR.
The installer validates the archive and replaces it atomically so Bitwig's open
archive is never truncated. Reload the Fire controller extension after installing.
The old direct-copy Maven install hook was removed; `mvn install` no longer
writes into Bitwig's Extensions folder.

If an earlier direct-copy update caused `NoClassDefFoundError` with an
`EOFException`, install atomically and reload the extension. If Bitwig retains
the failed class loader, save the project and restart Bitwig.

### Fixed step identity and bounded microtiming

Fine-cursor note onsets now map to the nearest Fire grid slot. An early note
stays on its original pad, including across page and loop boundaries. Pad
selection, velocity/recurrence edits, copy, deletion and Euclidean occupancy use
that same mapping. Insertion and deletion use fine-cursor coordinates so an
early neighbour in the same Bitwig coarse cell survives.

Held-step and Alt whole-loop nudges are bounded to **±40% of the current grid
step** around that slot, rounded down to the available 1/64-beat increments.
At the default 1/16-note grid this permits six clicks either way (37.5%).
Releasing and re-holding cannot reset the limit. The OLED reports `40% step
limit` when reached. Pre-existing offsets outside the limit can move back toward
the nearest slot, but cannot move farther out. The display's movement amount
still measures the current gesture.

Mapping requires a loop of at most 64 beats, with loop start and length aligned
to the current grid and grid size aligned to the fine cursor. Unsupported loops
retain the coarse editing view; fine nudging reports that the grid is unsupported.
Regression checks cover adjacent early notes, expression/deletion isolation,
loop/page boundaries, both nudge scopes and Euclidean pulse changes preserving
original note timing.

### Stable note index during asynchronous updates

The Fire now keeps an explicit logical-note index, with immutable fine
coordinates and pad occupancy separate from Bitwig's mutable `NoteStep.state()`.
All fine-grid operations read this index. Moving a note immediately updates its
indexed position; partial/stale observer frames cannot make its pad empty or
create a second editable identity. A new step press waits while a move is pending.
Velocity/Euclidean edits also wait for confirmation.

The index confirms both the removed source and occupied destination before
accepting further edits. A rejected/unconfirmed edit resynchronizes from actual
fine notes after one second. Context changes discard pending identities.
`STEP_MAP`, `STEP_INPUT`, and `NUDGE_RESYNC` diagnostics expose the actual
fine-note-to-pad mapping for live troubleshooting. Regression tests now include
partial move observations, mutable old note proxies, and release/reverse nudges
without duplicate notes. Live hardware verification is still required.

### Verifiable runtime build

The extension version and startup notification now show `0.82-step-index-1`;
`EXTENSION_INIT` logs the same identifier. A controller restart was observed
running without the newly installed mapping diagnostics, so installation now
instructs a full Bitwig restart after saving the project. Confirm the identifier
before judging the new note-index behavior. Atomic replacement keeps the old
loaded archive intact; it does not force Bitwig to create a new class loader.
