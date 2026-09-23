# Akai Fire Nudger

A Bitwig Studio drum-sequencer extension for the Akai Fire, based on Eric Ahrens' controller code, with additional work by R. Hawtin and this fork. This README describes the additions and changed controls in this version; [CHANGES.md](CHANGES.md) contains the development history.

Current build: **`0.82-step-index-5`**. Requires **Bitwig controller API 20**. The original single-track workflow remains available, alongside optional child-track clips feeding a group drum rack.

## Differences from Eric's original

| Area | Added or changed in this fork |
| --- | --- |
| Euclidean sequencing | Adjustable pulses and rotation, protection for manual notes, and generated-note ownership retained through fine nudges. |
| Microtiming | Selected-pitch, MIDI-channel-aware nudging; held-note or whole-loop scope; ±40% step limits and movement feedback. |
| Step display and editing | Stable logical pad positions for early/late notes; coordinated fine-cursor edits prevent an early neighbour from being overwritten. |
| Multi-clip drums | A drum rack on a group can be sequenced from up to 16 direct child tracks, with independent clip lengths and scene selection. |
| Velocity | Four groove contours, reversible groove amount, configurable normal/accent velocity, and matching note-repeat velocity. |
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
| Hold STEP SEQ + press Select | Switch between groove shape and amount. |
| Hold STEP SEQ + turn Select | Adjust the displayed groove field. |
| NOTE | Toggle record quantization between Off and 1/16. |
| DRUM | Toggle Bitwig Fill mode. |
| Pattern/Metronome button | Toggle **clip-launcher automation write**. This is no longer a metronome toggle. |
| STOP, selected-track mode | Toggle track pinning. |
| STOP, group-child mode | Reacquire the group from Bitwig's current selection. |
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
5. Select the group or a direct child/child clip in Bitwig.
6. Set **Sequencer → Clip source (reload extension) → Group child tracks** in the Fire controller settings, then restart Bitwig.

The group does **not** need a `PolySeq` name. This implementation uses selection; Oikontrol's named-group discovery is not implemented here. Tracks and routing must already exist; the extension does not create or route child tracks.

The first 16 child positions map to drum notes **36–51**, with newly inserted notes using MIDI channels **1–16**, respectively. Ensure the receiving instrument accepts those channels. The group master and effect tracks are excluded from the child bank. Audio tracks and nested groups cannot be edited as lanes but still occupy their main-child position, so use direct note tracks for predictable pad mapping.

The group stays pinned for drum-pad/device controls while a separate cursor edits the selected child's clip. Each child can have its own loop length. At startup, selection prefers a selected populated child clip, then a playing clip, then a populated slot. Selecting a different child clip in Bitwig updates the Fire lane and scene.

Press a step in an empty child slot to create a four-beat clip and insert a note. Switching lanes cancels stale deferred edits. Copies capture note properties before changing cursors, including velocity, duration, chance, recurrence and repeats.

Press **STOP** to acquire a different group after selecting it or one of its children. Choose **Selected track** as the clip source to return to the original single-track workflow.

### Child clip launching

With **Functionalities → Second Row → ClipLaunch-Row**:

- Clip pads select/launch or create clips on the selected child. Switching drum pads retains the scene index; row lights show that child's clips.
- **Alt + clip pad** launches existing clips in the scene across eligible children and creates four-beat clips in empty slots. Press again to launch newly created empty clips.
- Copy, Delete/clear, Shift + Delete/remove and Shift/colour apply to the selected child.

Pad mute/solo, remote controls, note repeat, accent, Euclidean sequencing and velocity groove retain their roles. Selecting a child no longer draws a persistent “Multiclip” banner over the normal display. Error feedback is temporary.

## Velocity groove and accent

Normal and Accent velocities are independent settings under **General velocity**, each from 1–127 (defaults 100 and 127). Changes also update note-repeat input velocity.

Hold **STEP SEQ**, press **Select** to choose Groove shape or Groove amount, and turn Select to adjust it. A plain STEP SEQ tap still toggles Accent; editing a groove while held does not toggle Accent on release. Shift + Select retains priority for Euclidean pulses.

Available shapes: **Agogo, Timbales, Congas and Bongo**. These are approximations reconstructed from Torso's published 16-point illustrations, not extracted T-1 firmware presets. Each repeats every 16 grid steps. Adjustable groove length and interpolation are not implemented.

Amount (0–100%) varies velocities around their original baseline, clamped to MIDI 1–127. It edits the selected drum's current page without changing timing, rests or Euclidean ownership. New Fire/Euclidean notes inherit the active groove. Returning amount to zero restores the current baseline; manual velocity edits establish a new baseline.

Changing pad, clip, page, grid or loop context starts a fresh groove overlay at zero. Resulting velocities save with the clip; shape, amount and baseline snapshots are temporary.

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
| Groove shape/amount and baseline snapshots | Extension memory for the current editing context |

The mapping layer is implemented by [LogicalStepIndex.java](src/main/java/com/akai/fire/sequence/LogicalStepIndex.java), [LogicalNoteStep.java](src/main/java/com/akai/fire/sequence/LogicalNoteStep.java) and [FineNudge.java](src/main/java/com/akai/fire/sequence/FineNudge.java). It is not a separate project file or a Bitwig device.

## Build, install and verify

Use Java 17, Maven and Python 3. With Maven dependencies already cached:

```sh
./scripts/check-sequencer.sh
python3 scripts/install-extension.py
```

For a first build, run `mvn package` with dependency access before the offline check script. The script runs 11 regression suites against the packaged JAR and creates `target/FireNudger.bwextension`.

The installer defaults to `~/Documents/Bitwig Studio/Extensions/FireNudger.bwextension`. An optional destination supports other locations:

```sh
python3 scripts/install-extension.py "/path/to/Bitwig Studio/Extensions/FireNudger.bwextension"
```

It validates the archive and replaces it atomically. **Save the project and fully quit/reopen Bitwig after installing.** Restarting only the controller can retain cached code. Avoid overwriting a loaded archive with a direct copy; this previously caused class-loading errors. `mvn install` no longer deploys into Bitwig's Extensions folder.

In the Fire controller settings, **About → Loaded build** shows the running version. Clicking it prints the version to Bitwig's controller console; the console also prints it at initialization. The current expected build is `0.82-step-index-5`.

`FireNudger.log` in the Bitwig Extensions folder records startup, group/child selection, note-to-pad mappings and nudge diagnostics. Automated checks cover Euclidean ownership, velocity groove, delayed observations, collisions, loop/page seams, channel isolation and copy snapshots. Hardware testing is still needed when changing controller behaviour.

## Credits and scope

Based on Eric Ahrens' MIT-licensed controller work and Bitwig's extension framework, with the existing R. Hawtin customizations and additions in this fork. Multi-clip targeting and fine-cursor nudging were adapted from **Oiko Audio / David Fredman's Oikontrol v2.23.0**. Copyright notices are preserved in [LICENSE](LICENSE).

The separate BitX `()LDR` preset-loading commands are not part of this extension; those changes belong to the BitX project. This repository documents and builds the Akai Fire extension.
