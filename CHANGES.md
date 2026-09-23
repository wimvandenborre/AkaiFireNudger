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

Build `0.82-step-index-2` replaces the startup version popup with a persistent
**About → Loaded build** entry in the controller settings. Its button displays
the actual running build and prints it to the controller console when clicked.
The same console line prints automatically at initialization.

### Euclidean ownership survives fine nudging (0.82-step-index-3)

Held-step and Alt loop nudges no longer reset the active Euclidean overlay.
Generated notes keep their logical-slot ownership and pulse count, so reducing
pulses removes them at their nudged fine positions. Manual notes remain protected,
including when they were nudged together with generated notes. Explicit manual
pad edits still transfer ownership as before. Page rotation and context changes
retain their existing reset behavior.

Regression coverage generates pulses, nudges in both directions with both scopes,
reduces pulses to zero, checks manual-note timing, then increases pulses again.

### Unobstructed Fire display (0.82-step-index-4)

Multiclip lane/scene status and target feedback now go to the Bitwig controller
console instead of drawing an overlay on the Fire OLED.

Build `0.82-step-index-5` refines the display change: normal child selection no
longer draws the persistent Multiclip lane/scene banner. Multiclip error feedback
is restored to the Fire display and clears after 1.5 seconds; it is no longer
redirected to the console.

### Follow playing child scene (0.82-step-index-6)

Shift + Metronome/Pattern selects the latest playing child scene while retaining
the selected drum lane. It selects empty slots without creating clips, cancels
stale edits through the normal cursor retargeting path, and leaves selection
unchanged when no eligible clip is playing. Playback alone still does not move
the edit cursor. The plain button retains launcher automation write. Playing-scene
discovery is limited to the observed 16-scene child bank.

Build `0.82-step-index-7` swaps these gestures: **Metronome/Pattern** follows the
playing child scene; **Shift + Metronome/Pattern** toggles launcher automation
write. Plain presses outside group-child mode show the mode requirement.

Build `0.82-step-index-8` fixes the playing-scene cursor handoff: retargeting waits
for unpinning and child-track selection, then selects and opens the target slot
in Bitwig's editor while waiting for both clip cursors to match. This addresses
live timeouts where the desired scene changed but both cursors remained on the
previous scene. STOP in group mode continues to reacquire the group, not toggle
its pin; the group stays pinned to retain drum-device controls.


Build `0.82-step-index-9` aligns child cursor following with Oiko: the child
track cursor is created with selection following enabled, and both child clip
cursors remain unpinned after retargeting. Only the group rack cursor stays
pinned. Playing-scene detection already found the requested scene; the old
non-following/pinned child cursors could remain on the previous clip. Timeout
logging now includes all three cursor pin states and the target slot selection.
This change needs a full Bitwig restart and hardware verification.

Build `0.82-step-index-10` adds direct MIDI cursor navigation to scene refresh.
Runtime logs confirmed that unpinning and repeatedly selecting/showing the slot
left both cursors on the old scene. Refresh now requests the absolute slot once,
then navigates each actual clip cursor if necessary. Navigation waits for each
observed move, skips empty rows by checking actual scene indices, and blocks
editing until both track/scene identities match. Directly navigated clips are
pinned against unrelated editor changes. Stale callbacks are cancelled on a new
target; unreachable clips time out without editing neighboring clips.
Regression checks simulate ignored editor selection, independently delayed
cursor moves, forward/backward refresh, and a missing target. Live Bitwig/Fire
verification is still required.

Build `0.82-step-index-11` adds **Sequencer → Hard pin group track** (default 1;
0 uses the selected group). Fixed targets use the top-level track list and are
selected directly without releasing the rack pin. STOP preserves an established
hard pin and editing scene. Metronome can acquire the fixed group before capturing
the playing scene, so STOP is no longer a prerequisite.
**Child clip selection → Manual (Metronome)** is the default: the editing track
and both clip cursors are pinned when ready, and editor selection changes are
ignored. Scene launches leave the captured clips alone until another manual
selection. **Follow editor selection** preserves the previous optional behavior.
Checks cover fixed-track acquisition, STOP, manual capture, later scene launches,
editor changes, lane switching, unavailable targets, and Metronome recovery.

The user confirmed Metronome works with build `0.82-step-index-11`.
README now documents both settings and distinguishes the captured editing scene
from launcher playback, with a scene 2 → scene 5 example.


Build `0.83-groove-shapes-1` upgrades compilation/required API to 25, archives the
T-1-inspired velocity groove outside the source roots, and replaces its STEP SEQ
page with Groove Shapes. Eight musical templates and six motion generators use
immutable original positions, rhythmic indexing, explicit phase/base/depth,
quantization, bias, anchor/off-grid protection, and effective-period validation.
A reversible session coordinator tests whole-batch validation, delayed own-write
acknowledgements, conflicts, exact restore, and safe intermediate movement order.

The live adapter uses the existing FineNudge move path and LogicalStepIndex for
selected child drum clips. Explicit drum-cell mode retains 1/64-beat translations,
40% pad limits and existing sub-cell residuals without recreating notes or setting
properties. It requires a single drum pitch per clip and one onset per fine cell;
API 25 cannot prove arbitrary exact note enumeration. Capture/Preview/Apply and
restoration use immutable originals, stable session IDs, full-loop fine-cell
readback and an all-pitch scope check. Selected-note, pre-quantization, playback
writes and linked-clip writes remain disabled. Existing nudge, Euclidean and
hard-pin/manual-scene behavior remains covered by regression checks.


Build `0.83-groove-shapes-2` replaces the long groove menu with **Groove / Amount**.
Turning either automatically updates all nonempty MIDI children in the Fire's
captured scene. The first nonzero amount captures originals; 0% restores them.
Separate per-child sessions share a private FineNudge worker, with full batch
preflight, coalescing and scene guards. The worker never selects editor slots or
launches clips. Background edits mirror into the visible Fire pad index. Changing
drum lanes retains the baseline; changing the captured scene resets Amount.
Added batch and worker suites; 15 suites now cover the build. Real Bitwig testing
is still required for multi-cursor navigation and observer timing.


Build `0.83-groove-shapes-3` fixes invalid MIDI SysEx byte `0xB7` from the groove
page's middle-dot separator. Groove labels now use ASCII, and the shared OLED
text encoder replaces unsupported Unicode/control characters with `?` before
20-character fitting. This also protects track/preset names. Regression checks
validate actual emitted packet bytes, framing and lengths, including Unicode,
surrogate pairs and unchanged ASCII text.


Build `0.83-groove-shapes-4` fixes the runtime blocker confirmed in FireNudger.log:
all groove attempts were rejected because transport was playing. Drum-cell groove
now permits playback through the existing FineNudge movement path; recording,
identity, content and collision checks remain. OLED status wraps across lines and
reports confirmed note movement counts, including zero changes at fine resolution.
Adapter and group regression tests cover playback and restoration while playing.


Build `0.83-groove-shapes-5` adds **Sequencer → Groove lock**, default On.
Note additions/removals from pads, Euclidean edits and stable editor observations
retain the groove. Surviving notes keep their original timing; new notes acquire
individual baselines; deleted notes never return on zero/reset. Known Fire edits
cancel obsolete queued moves before reconciliation. The OLED is reduced to groove
name and percentage; lock stays in settings.

Top-row pads now also listen to each direct child's playingNotes, restoring MIDI
activity lights with the rack on the group. Group audition remains independent;
note offs/removal clear child state, and drum-bank offsets map it to visible pads.
Added lock/Euclidean and activity-light tests; all 18 suites pass. Hardware checks
remain for live lock timing and child playingNotes feedback.


Build `0.83-groove-shapes-6` fixes an unintended Accent toggle when STEP SEQ was
held to view the groove and released without moving a control. Only a short,
unmodified tap now toggles Accent; holds and groove adjustments leave it alone.
Euclidean additions still use the current Normal/Accent velocity, while existing
notes keep theirs. Regression checks verify Ramp Down and lock reconciliation
preserve different existing and newly added velocities. No velocity shaping added.

The compact groove display now puts `>` beside the selected shape or amount.
Pressing Select switches the marker without adding explanatory text.


### Logic 16A–E and optional velocity layer

Build `0.84-logic-groove-1` replaces the visible preset list with Logic 16A–E
(straight, 54%, 58%, 62%, 66%). 16F is omitted by request; the existing 40% pad
limit remains. Hold STEP SEQ and use Select for Groove/Amount across the captured
child clips. OLED stays name/percentage with the selected-field marker.

Settings → Groove velocity adds Enable (Off), Amount (0%, −100..100), Min/Max
(70/120), Phase (0/1) and Reset. The extension's optional strong/weak profile uses
original rhythmic positions, not note order. Negative amounts invert it. Timing
and velocity can be used/reset independently. Zero/disable restores original host
velocity doubles without rounding. New preferences never auto-write on load.

Final timing and velocity targets derive from immutable originals. Host writes
acknowledge an in-place nudge before setting velocity at the confirmed destination;
no other property setter is used. Groove Lock preserves surviving original
velocities, captures new notes, removes deleted notes and adopts independent
velocity edits. Counts report distinct updated notes rather than double-counting
timing and velocity operations. Existing timing suites retain their assertions;
new vector/session/settings tests and actual adapter setter checks cover both
layers. Real Bitwig validation is still needed for velocity observer readback,
playback, expression retention and Euclidean editing with both layers active.


### Velocity settings activation fix

Build `0.84-logic-groove-2` defaults the controller preset to Logic 16C. Fixes
velocity staying inactive even with Enable On and a nonzero Amount: Bitwig's
NumberSetting caches initial defaults and can omit callbacks for unchanged raw
values. Waiting for all five callbacks therefore blocked the entire configuration
when, for example, Phase remained 0. The controller now reads interested setting
values on explicit Groove/Amount adjustment, independent of initial callbacks.
Subsequent settings edits apply live; startup/restored preferences cannot edit clips.
Context reset requires a new explicit groove adjustment before applying settings.
Adds request/settings diagnostics and a regression for missing default callbacks.


### Shared display Amount restores timing and velocity

Build `0.84-logic-groove-3` makes the compact Fire percentage a master for timing
and the optional velocity layer. At 0%, both restore immutable original values;
intermediate amounts scale the configured signed velocity amount. Velocity
preferences remain saved. Logic 16A provides velocity-only use with the master
above zero. No new display controls. Regression tests cover repeated all-child
master changes and zero after Groove Lock additions/deletions.


### Faster Groove Lock updates

Build `0.84-logic-groove-4` prioritizes the selected/edited child. After a local
note edit, its stable snapshot is reconciled, preflighted and written while the
shared cursor is still there, before checking unrelated children. Unchanged
children use one comparison read and do not re-enter a full preflight/write pass.
Global Groove/Amount/velocity-setting changes still preflight all children before
writing, and skip the write-pass visit for children with no planned changes.

Fire edit debounce is 150 ms (was 300 ms), idle polling 500 ms (was 2 seconds).
Changed content still needs two stable reads; every actual write keeps its fresh
read, recording/context checks and acknowledgement. Pending local work survives
interruption or temporary blocking. `GrooveLatencyChecks` verifies priority before
unrelated reads, unchanged-read counts, no-op passes, global preflight, interruption,
retry and scene-change guards. Live end-to-end latency still depends on Bitwig.
