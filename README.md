# Akai Fire Nudger

A Bitwig Studio drum-sequencer extension for the Akai Fire, based on Eric Ahrens' controller code, with additional work by R. Hawtin and this fork. This README describes the additions and changed controls in this version; [CHANGES.md](CHANGES.md) contains the development history.

Current build: **`0.84-logic-groove-4`**. Requires **Bitwig controller API 25**. The original single-track workflow remains available, alongside optional child-track clips feeding a group drum rack.

## Differences from Eric's original

| Area | Added or changed in this fork |
| --- | --- |
| Euclidean sequencing | Adjustable pulses and rotation, protection for manual notes, and generated-note ownership retained through fine nudges. |
| Microtiming | Selected-pitch, MIDI-channel-aware nudging; held-note or whole-loop scope; ±40% step limits and movement feedback. |
| Step display and editing | Stable logical pad positions for early/late notes; coordinated fine-cursor edits prevent an early neighbour from being overwritten. |
| Multi-clip drums | A drum rack on a group can be sequenced from up to 16 direct child tracks, with independent clip lengths and scene selection. |
| Groove Shapes / velocity | Logic 16A–E timing presets across child drum clips, using the existing fine nudge path. Optional signed velocity shaping lives in Settings; Groove Lock retains both layers after note edits. |
| Mute/solo | Persistent modes, optional dedicated second row, and state lights/colours. |
| Encoders | Per-note pitch, additional repeat controls, User 2 remote controls with an alternate bank, and parameter-name feedback. |
| Other controls | Track pinning, clip-content duplication when doubling length, colour cycling, undo/redo, record quantization, Fill, and launcher automation write. |
| Installation and diagnostics | Atomic archive replacement, runtime build identification in settings/console, and a diagnostic log. |

## Quick control reference

“Select” means the large Select encoder. “Bank arrows” means the left/right Bank buttons.

| Gesture | Function |
| --- | --- |
| Shift + turn Select | Increase/decrease Euclidean pulses on the selected drum's current page. |
| Shift + Alt + turn Select | Rotate the Euclidean pattern. |
| Hold step pad(s) + Bank left/right | Nudge those notes earlier/later. |
| Alt + Bank left/right, no steps held | Nudge the selected drum pitch throughout its clip loop. |
| Bank left/right alone | Rotate the visible page by one grid step. |
| Shift + Bank left/right | Undo/redo. |
| Short tap on a step | Add/delete a note. |
| Hold an existing step for at least 250 ms | Preserve the note on release, even without an edit. |
| STEP SEQ tap | Toggle Accent. |
| Hold STEP SEQ + press Select | Switch between Groove and Amount. |
| Hold STEP SEQ + turn Select | Adjust the displayed groove field. |
| NOTE | Toggle record quantization between Off and 1/16. |
| DRUM | Toggle Bitwig Fill mode. |
| Pattern/Metronome button | Follow the playing child scene for editing (group-child mode). |
| Shift + Pattern/Metronome | Toggle **clip-launcher automation write**. This is no longer a metronome toggle. |
| STOP, selected-track mode | Toggle track pinning. |
| STOP, group-child mode | Keep the configured hard pin; with hard pin `0`, reacquire the selected group. |
| Shift + drum pad / clip pad | Cycle its colour through the predefined palette. |
| Shift + double clip length using Last Step | Duplicate the clip content as its length doubles. |
| Shift + knob-mode button | Toggle the current encoder mode's alternate bank, where available. |

The first pad row selects drums; the last two rows provide a 32-step sequencer page. Under **Functionalities → Second Row**, choose `Mute-Row` or `ClipLaunch-Row`, then restart the extension/host to apply the layout.

## Euclidean pulses and rotation

Hold **Shift** and turn **Select** to add or remove pulses across the current page, using its current grid resolution and up to 32 active steps. The display shows the pulse count and page length. Releasing Shift retains the active pulse count.

Notes present before generation are protected. Reducing pulses to zero removes only generated notes. Explicitly adding or copying a step makes it manual/protected; deleting a step also preserves the running pulse count.

**Fine nudging preserves ownership:** generated notes remain generated after either held-step or Alt-loop nudging. Lowering pulses removes them at their actual shifted positions. Manual notes stay protected, even if nudged at the same time.

Hold **Shift + Alt** and turn **Select** to rotate generated hits without moving manual notes. Rotation can be set before adding pulses and wraps at the active page length. For example, four pulses with rotation +2 in 16 steps produces hits at steps 3, 7, 11 and 15.

Rotation for drum notes **36–52** is saved in the project's document settings. Save a template to reuse starting rotations. Racks using the same note positions share those rotation settings within a project.

Pulse count and generated/manual ownership are temporary. Changing pad, clip, page or grid starts a fresh overlay; page rotation, undo/redo and extension reload also reset it. Existing notes then become protected. Ownership lost in an earlier session is not reconstructed from the MIDI notes.

## Fine nudging and fixed pad positions

Each Bank-arrow press moves a note **1/64 beat**, not 1/64 of a whole note. Left is earlier; right is later. Hold steps to target them, or hold Alt without steps to target the selected drum pitch across its whole loop. Other pitches are not shifted. MIDI channels and note properties are retained.

Movement is bounded to **±40% of the current grid step**, rounded down to the available fine increments. At the default 1/16-note grid, that is **six presses each way (37.5%)**. Releasing and re-holding does not reset the limit. Existing notes outside the range can move back toward their nearest slot, but not farther away.

The Fire maps notes to logical grid slots independently of Bitwig's coarse cells. A slightly early note stays on its original pad, including across page and loop boundaries. This same mapping drives selection, note-property edits, deletion and Euclidean occupancy. Adjacent notes can therefore share a coarse Bitwig cell while still occupying separate Fire pads.

An in-memory note index tracks moves while Bitwig reports updates. Pending notes stay visible, and new step edits wait for confirmation instead of treating a temporarily empty source cell as a missing note. Moves into occupied destinations are skipped rather than overwriting notes.

The OLED shows signed movement during the current hold, such as `-3/64 beat` or `+2/64 beat`. It resets for a new selection/hold and is **not an absolute offset from the musical grid**. Mixed offsets can display a range; reaching the cap displays `40% step limit`. Nudges execute on arrow press, so the step and arrow can be released in either order.

Fine mapping/nudging requires a loop of at most **64 beats**, with loop start and length aligned to the current grid and the grid size aligned to 1/64 beat. Unsupported geometry retains the coarse editing view and reports unavailable fine nudging.

## Multi-clip drums on a group track

### Project setup

1. Place the receiving Drum Machine/instrument on a **group track**.
2. Add up to **16 direct child note tracks**.
3. Route each child's **note output** to the group instrument. Audio output to the group alone is insufficient.
4. Put the related child patterns in the same launcher scene. Keep the group's own launcher slots empty for this workflow.
5. Set **Sequencer → Hard pin group track (0 = selected group)** to the group’s track number. The default is **1**. Numbering uses the top-level track list (nested children do not count). Use `0` to acquire the group from Bitwig selection instead.
6. Set **Sequencer → Clip source (reload extension) → Group child tracks** in the Fire controller settings, then restart Bitwig.

The group does **not** need a `PolySeq` name. This implementation uses the configured track number or selection; Oikontrol's named-group discovery is not implemented here. Tracks and routing must already exist; the extension does not create or route child tracks.

The first 16 child positions map to drum notes **36–51**, with newly inserted notes using MIDI channels **1–16**, respectively. Ensure the receiving instrument accepts those channels. The group master and effect tracks are excluded from the child bank. Audio tracks and nested groups cannot be edited as lanes but still occupy their main-child position, so use direct note tracks for predictable pad mapping.

The group stays pinned for drum-pad/device controls while a separate cursor edits the selected child's clip. Each child can have its own loop length. At startup, selection prefers a selected populated child clip, then a playing clip, then a populated slot. **Sequencer → Child clip selection → Manual (Metronome)** is the default. The editing track and clips stay pinned: launching another scene or selecting clips in Bitwig does not change the Fire’s captured scene. Use **Follow editor selection** to restore selection following.

Press a step in an empty child slot to create a four-beat clip and insert a note. Switching lanes cancels stale deferred edits. Copies capture note properties before changing cursors, including velocity, duration, chance, recurrence and repeats.

Press **Metronome/Pattern** after launching a scene to move the Fire's
editing selection to the most recently started scene with a playing eligible
child clip. The selected drum lane stays the same, even if its slot in that scene
is empty. Switching drum pads then edits that same scene. Refresh requests the
target slot and directly navigates both MIDI clip cursors if Bitwig leaves them
on the old clip. Editing resumes only after both cursors confirm the requested
child track and scene. The group drum rack remains pinned throughout. Refresh does not
launch, create or stop any clips; ordinary scene playback does not automatically
move the Fire's editing selection. Press after the launch quantization boundary,
when the new clips are actually playing. With no playing child clip, selection
stays unchanged. Discovery covers the current 16-scene child bank. If several
scenes are playing independently, the latest observed start wins; on initial
attachment, ties prefer the selected lane. **Shift + Metronome/Pattern** toggles launcher automation write.

With a hard pin configured, **STOP** keeps that group pinned and preserves the editing scene. **Metronome** works without pressing STOP first; it acquires the configured group if needed. A missing/non-group target reports an error rather than choosing another track. To change racks, change the hard-pin setting; with `0`, select a group or child in Bitwig and press STOP. Choose **Selected track** as the clip source to return to the original single-track workflow.

### Manual editing selection and playback

The settings under **Sequencer** control the rack and the clips selected for editing:

| Setting | Default | Behaviour |
| --- | --- | --- |
| Hard pin group track (0 = selected group) | `1` | Keeps the rack controls on top-level track 1. Set another track number for a different group, or `0` to acquire the selected group with STOP. |
| Child clip selection | Manual (Metronome) | Keeps the captured child scene selected for editing when Bitwig launches or selects another scene. **Follow editor selection** allows Bitwig clip selection to change the Fire's lane and scene. |

**Pinning holds the editing selection, not playback.** Launching another scene changes which MIDI clips play according to Bitwig's normal launcher behaviour. The Fire can continue showing and editing the previously captured clips even though those clips are no longer playing. Their edits become audible when those clips play again.

For example:

1. Launch scene 2, wait for it to start, then press **Metronome**. The Fire edits scene 2's child clips.
2. Launch scene 5. Bitwig plays scene 5, while the Fire continues editing scene 2.
3. Press **Metronome** again to select scene 5's child clips for editing. Switching drum pads then accesses the other children in scene 5.

**STOP** preserves an established hard pin and captured editing scene. There is no need to press STOP before Metronome. **Shift + Metronome** continues to toggle clip-launcher automation write.

### Child clip launching

With **Functionalities → Second Row → ClipLaunch-Row**:

- Clip pads select/launch or create clips on the selected child. Switching drum pads retains the scene index; row lights show that child's clips.
- **Alt + clip pad** launches existing clips in the scene across eligible children and creates four-beat clips in empty slots. Press again to launch newly created empty clips.
- Copy, Delete/clear, Shift + Delete/remove and Shift/colour apply to the selected child.

The top-row drum pads show actual MIDI activity from each child track, independently
of the Fire's captured editing scene. Group/live audition feedback remains active;
empty group-note updates do not clear child activity.

Pad mute/solo, remote controls, note repeat, accent, Euclidean sequencing retain their roles. Groove Shapes replaces the former velocity-groove page. Selecting a child no longer draws a persistent “Multiclip” banner over the normal display. Error feedback is temporary.

## Groove Shapes and accent

Hold **STEP SEQ** to open the groove page. It has just two controls:

- **Groove:** turn Select to choose the preset.
- **Amount:** press Select to switch to Amount, then turn it from 0–100%.

Press Select to switch back. Changes automatically apply across all existing MIDI
child clips in the **Fire's captured scene**. Empty slots are skipped. No Capture,
Preview or Apply menu is required. A short STEP SEQ tap still toggles Accent; holding to view the groove does not.
Shift + Select retains its Euclidean function.

The default preset is **Logic 16C (58%)**. Amount starts at **0%**. The first nonzero adjustment automatically captures each
child's original timing and velocity. Subsequent changes derive from those same
originals; **display Amount 0% restores both timing and velocity**. Releasing STEP SEQ keeps the applied timing and
baseline. Choosing another drum lane retains the group groove session. Capturing
another scene with Metronome starts a new baseline at 0%, leaving the old scene's
edited notes intact. Launching another scene alone does not change this scope.

Groove edits work during **playback**, through the same in-place movement as manual
nudging. Recording clips remain protected. Background cursors target the child clips
without selecting them in the editor or changing the Fire's selected drum lane.
The Fire display shows only the groove name and percentage; `>` marks the selected control. Status/movement counts
are logged, and errors appear as Bitwig notifications. Groove-setting changes check
all children before writing; note-edit lock updates validate the affected child locally; individual moves are acknowledged before continuing. A host failure
mid-batch can leave a partial result; there is no native atomic multi-clip transaction.

**Settings → Sequencer → Groove lock** defaults to **On**. Added notes inherit the
current groove/amount, including enabled velocity shaping, without turning the
groove knob again; deleted notes stay deleted. This includes Fire pad edits, notes drawn manually in Bitwig’s MIDI editor, and
Euclidean fills, rotations and pulse reductions. Surviving notes retain their
original timing and velocity baselines, so subsequent adjustments never stack the groove twice.
At 0%, surviving and newly added notes return to their own original timing and velocity;
removed notes are never recreated. Velocity stays unchanged unless the optional
velocity layer is enabled; other note properties remain intact.

Lock watches edits in the captured child clips, including edits made in Bitwig.
Updates are coalesced. The selected/edited child is checked first and receives its
update before scanning the remaining children. Unchanged children need only one
comparison read and skip the additional preflight/write pass. Fire edits wait
150 ms to settle; background checks run every 500 ms when idle. These are scheduling
intervals, not a total-latency guarantee: cursor navigation and note acknowledgements
still take time. Known Fire edits pause queued groove moves first. Recording and genuine
identity/readback conflicts still block writes. Turning Lock off disables automatic
note reconciliation; explicit Groove/Amount changes still work against unchanged
snapshots. The lock switch adds no controls or explanations to the OLED.

Presets: **Logic 16A (straight), 16B (54%), 16C (58%), 16D (62%), 16E (66%)**.
These are timing templates on a sixteenth-note base, resetting at each child's
loop. Amount scales the displacement; the percentage in the preset name is its
full swing ratio. 16F is deliberately omitted to retain our 40% pad limit.
The previous mathematical/motion shapes remain internal for regression coverage
but are no longer in the controller's preset selector.

Timing edits use our existing **FineNudge** code: 1/64-beat movement and the 40%
logical-pad limit. The visible Fire index mirrors background moves, so early notes
retain their pads. No note recreation or duration changes are used. Tiny amount
changes can round to zero at this resolution. Full B/C/D/E delays round to
1/3/4/5 fine steps respectively (1 fine step = 1/64 beat).

**Settings → Groove velocity** contains the optional velocity layer:

| Setting | Default / behavior |
| --- | --- |
| Enable | Off. Turning Off restores original velocities while retaining timing and the velocity settings. |
| Amount | 0%, range −100% to +100%. Zero restores exact original velocities; positive amounts blend toward the strong/weak pattern; negative amounts reverse it. |
| Min / Max | 70 / 120, each 1–127. Moving one past the other moves its counterpart too. |
| Phase | 0 or 1; swaps strong/weak positions independently of timing. |
| Reset | Sets velocity Amount and Phase to zero without changing timing. |

At display Amount 100% and velocity Amount +100%, sixteenth positions alternate
Max/Min; velocity −100% gives Min/Max. The display percentage scales both layers: for
example, display 50% with velocity +80% gives an effective velocity blend of +40%.
Returning the display to 0% restores both originals without changing saved settings.
The original grid position chooses the accent, so missing notes never shift the
pattern. At an effective velocity amount of +50%, an original velocity of 100
aimed at 70 becomes 85.
This is our optional velocity profile, **not an original Logic velocity curve**
or exact Ableton emulation. For velocity without timing swing, choose Logic 16A
and raise the display Amount.
It shares the same all-child scope and Groove Lock as timing, without adding
anything to the Fire display. Adjust Groove or Amount once to start the editing
session; velocity settings then apply live to that session. Initial preference
loading never writes to clips. Original baselines last for the current
editing session, not across extension restarts or scene recapture.

Keep one instrument pitch per child clip and at most one onset per
pitch/channel/fine cell. API 25 cannot distinguish multiple onsets in one fine cell
or report exact sub-cell starts; in-place translations retain existing residual
timing. Unsafe overlaps and loop boundaries are rejected. Pre-quantization,
selected-note-only scope and edits during recording remain disabled.
See [capabilities](docs/GROOVE_CAPABILITIES.md) and [Bitwig testing](docs/GROOVE_TESTING.md).

The original **Agogo/Timbales/Congas/Bongo velocity groove** and its integration
are archived in [archive/t1-velocity-groove](archive/t1-velocity-groove/README.md),
excluded from the build. New notes no longer inherit those contours. Normal and
Euclidean-created notes start with the current Normal/Accent velocity. Groove Lock
then applies the optional velocity layer from that original value. Surviving notes
retain their original velocity baseline; independently edited velocities become
new per-note baselines during lock reconciliation.
Accent velocities remain independent **General velocity** settings (1–127;
defaults 100 and 127), including note-repeat input velocity.

## Mute, solo and encoder changes

Mute and solo modes can remain active until another mode is chosen. The dedicated second-row layout supports mute and solo without replacing the step-edit rows, with red/green state feedback. Clip colours are retained in the corresponding clip-row modes.

Encoder mode assignments in this fork:

| Mode | Encoders 1–4 |
| --- | --- |
| Channel | Velocity, Chance, Repeats, Pitch |
| Mixer | Velocity Spread, Pressure, Length, Occurrence |
| Mixer alternate | Repeat Curve, Repeat Velocity Curve, Repeat Velocity End, Occurrence |
| User 1 | Selected pad Level, Pan, FX1, FX2 |
| User 2 | Remote controls 1–4 of the selected pad's first device |
| User 2 alternate | Remote controls 5–8 |

The knob-mode button cycles modes; Shift + knob-mode toggles available alternate banks. User modes provide parameter-name feedback, including dynamic remote-control names. Existing note properties, recurrence, copy/clear, drum audition and note-repeat controls remain available.

## What is saved?

| State | Storage |
| --- | --- |
| MIDI notes, nudged timing, velocities and note properties | Bitwig clips, saved with the project |
| Per-note Euclidean rotation (36–52) | Bitwig project document settings |
| Clip-source/layout and normal/accent preferences | Bitwig controller preferences |
| Logical pad mapping and pending moves | Extension memory; reconstructed from clip notes |
| Euclidean pulse count and generated-note ownership | Extension memory for the current editing context |
| Groove velocity controls | Controller preferences (initial load does not edit clips) |
| Groove shape/amount and baseline snapshots | Extension memory for the current editing context |

The mapping layer is implemented by [LogicalStepIndex.java](src/main/java/com/akai/fire/sequence/LogicalStepIndex.java), [LogicalNoteStep.java](src/main/java/com/akai/fire/sequence/LogicalNoteStep.java) and [FineNudge.java](src/main/java/com/akai/fire/sequence/FineNudge.java). It is not a separate project file or a Bitwig device.

## Build, install and verify

Use Java 17, Maven and Python 3. With Maven dependencies already cached:

```sh
./scripts/check-sequencer.sh
python3 scripts/install-extension.py
```

For a first build, run `mvn package` with dependency access before the offline check script. The script runs 21 regression suites against the packaged JAR and creates `target/FireNudger.bwextension`.

The installer defaults to `~/Documents/Bitwig Studio/Extensions/FireNudger.bwextension`. An optional destination supports other locations:

```sh
python3 scripts/install-extension.py "/path/to/Bitwig Studio/Extensions/FireNudger.bwextension"
```

It validates the archive and replaces it atomically. **Save the project and fully quit/reopen Bitwig after installing.** Restarting only the controller can retain cached code. Avoid overwriting a loaded archive with a direct copy; this previously caused class-loading errors. `mvn install` no longer deploys into Bitwig's Extensions folder.

In the Fire controller settings, **About → Loaded build** shows the running version. Clicking it prints the version to Bitwig's controller console; the console also prints it at initialization. The current expected build is `0.84-logic-groove-4`.

`FireNudger.log` in the Bitwig Extensions folder records startup, group/child selection, note-to-pad mappings and nudge diagnostics. Automated checks cover Groove Shapes fixtures and session safety, Euclidean ownership, delayed observations, collisions, loop/page seams, channel isolation and copy snapshots. Hardware testing is still needed when changing controller behaviour.

## Credits and scope

Based on Eric Ahrens' MIT-licensed controller work and Bitwig's extension framework, with the existing R. Hawtin customizations and additions in this fork. Multi-clip targeting and fine-cursor nudging were adapted from **Oiko Audio / David Fredman's Oikontrol v2.23.0**. Copyright notices are preserved in [LICENSE](LICENSE).

The separate BitX `()LDR` preset-loading commands are not part of this extension; those changes belong to the BitX project. This repository documents and builds the Akai Fire extension.
