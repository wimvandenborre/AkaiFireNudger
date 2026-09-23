# Groove Shapes validation

`./scripts/check-sequencer.sh` builds offline with Java 17/API 25 and runs 18 suites.

- Supplied JSON: 33 numeric cases, eight presets, six motion sample tables, three
  rejections and four loop compatibility cases; 1,000 depth cycles check drift.
- Session tests: immutable originals/properties, explicit preview/apply, restore,
  coalescing, delayed acknowledgements, conflicts, partial failure, stale callbacks,
  transport gates, collision ordering and the 40% limit.
- API adapter tests: pinned equality, stale capture cancellation, no automatic
  writes and conservative inspection fallback. The drum-mode test runs the real
  FineNudge bridge against SDK proxies: swing, depth change from originals, fixed
  pad slots, Cancel restoration, and no recreation/duration writes.
- Existing manual nudge, logical index, Euclidean, multiclip, snapshot and gesture
  regression suites remain active.

These tests do not run a Bitwig process or Fire hardware.

## Precision

The movement quantum is 1/64 quarter-note beat (7.5 ms at 125 BPM). Sixteenth Deep
Swing 57 requests 0.035 beats and moves 0.03125 beats (15 ms versus ideal 16.8 ms).
Each translation is rounded once from the captured baseline. Small offsets may
round to zero, reported on the display. Exact sub-cell starts are not available;
in-place movement retains their residual. Pre-quantization is disabled.

## Group tests

`GrooveBatchChecks` covers three independent clips, all-child preflight before any
write, amount/zero restoration, coalescing, changes during capture, scene changes,
external conflicts, playback/recording rejection and partial failure stopping later
children. `GrooveWorkerChecks` verifies exact scene navigation on private cursors,
pitch selection, readback reuse, cancelled navigation and no editor/launch actions.
The fine-grid suite also covers mirrored background moves during partial frames.

## Test in Bitwig

1. Install with `python3 scripts/install-extension.py`, save and fully restart
   Bitwig. Confirm Loaded build `0.84-logic-groove-4`.
2. Use the existing rack group with separate child clips. Select the desired scene
   with Metronome. Test both stopped and during playback. Keep one drum pitch per child clip and at
   most one onset per pitch/channel/1/64-beat cell.
3. Hold STEP SEQ, turn Select to choose Groove, press Select once and turn Amount
   up. Every nonempty child clip in the captured scene should receive that groove.
   The editor, Fire lane, group pin and launcher playback selection should stay put.
4. Adjust Amount repeatedly, switch Groove, then return to 0%. Verify exact original
   cell timing, note counts, lengths, velocities, expressions and operators.
5. Release and reopen the page, or switch drum lanes: the same baseline should
   remain. Launch another scene: scope stays captured. Press Metronome to capture
   it: queued old-scene work stops and Amount resets to 0 for the new scene.
6. Apply groove to Euclidean-generated notes, then lower pulses. Verify that the
   moved generated notes remain removable and early notes keep their pads lit.
7. Edit a clip externally, remove a child, or start recording during a batch.
   Verify stopped writes, clear feedback, and no edit to another scene. A partial
   write must not be described as atomic restoration. Reopen after a conflict to
   establish a new baseline at 0%.
8. Check responsiveness with 16 children, long clips and delayed observation frames.
   Verify that private cursor navigation and pinning behave as in the SDK mocks.

Disabled: selected-note-only scope, pre-quantization, and writes while recording. No single native undo step or persistent original snapshots is promised.

`OledMidiChecks` verifies real OLED SysEx packets remain 7-bit with Unicode names
and the former middle-dot separator, preserving ASCII text and packet framing.

The real adapter proxy test now runs capture, nudge, depth changes and Cancel with
transport playing, and separately verifies that recording still prevents capture.
Group tests cover all-child movement/restoration during playback and actual move
counts, including unchanged results. Confirm the audible result in Bitwig using
Deep Swing 57 at 100% on clips with offbeat sixteenth notes.


## Groove Lock and activity lights (build 5)

`GrooveLockChecks` verifies additions/deletions, empty/refilled clips, cancellation
of obsolete queued writes, survivor baseline retention, zero restoration and
repeated real EuclideanPattern fill/reduce/clear cycles. Adapter checks adopt new
IDs and reuse vacated cells without duplicate IDs or accumulated offsets. Existing
strict conflict tests remain for unlocked sessions.

`DrumPadActivityChecks` tests independent child/rack activity, note offs and drum
bank offsets. `MulticlipTargetChecks` now emits actual fake playingNotes callbacks
to verify the direct-child subscriptions and removal clearing.

In Bitwig, leave Groove lock On (default), set a groove/amount, then add/remove
notes using pads, Euclidean controls and the editor. Check that new notes receive
the groove and deleted notes do not return at 0%. Repeat while playback runs.
Confirm that the OLED shows only name/percentage. Turn Groove lock Off in Sequencer
settings and verify that further note edits are not automatically regrooved.

Play clips on several child tracks: corresponding top-row pads should flash without
changing the captured editing scene. Check simultaneous notes, note offs, missing
children, rack audition, bank scrolling and stopping playback on real hardware.


Build 6 adds gesture tests for the unintended long-hold Accent toggle and a Ramp
Down test using different velocities for original and newly added notes. Confirm
that a short STEP SEQ tap toggles Accent, while holding/releasing the groove view
without changing controls does not. Euclidean-created notes should use the current
Normal/Accent value; timing shape changes must preserve those velocities.


## Logic 16A–E and independent velocity (0.84-logic-groove-1)

`GrooveLogicVelocityChecks` covers exact A–E arrays/IDs and absence of F, full/half
positive and negative amounts, equal/extreme ranges, independent phases, sparse
steps, exact host doubles at zero/disable, distinct input dynamics, no cumulative
drift, independent timing reset, delayed operation acknowledgements, forbidden
property changes and original preservation through multi-child lock edits.
`GrooveVelocityPreferencesChecks` covers defaults, range coupling, signed amount,
velocity reset and absence of automatic writes when loading saved preferences.
`GrooveHostChecks` additionally executes the real adapter's `NoteStep.setVelocity`
path after nudging and verifies independent restoration without recreation or
duration setters. The original timing assertions and JSON fixtures remain active.
Run all **21 suites** with `./scripts/check-sequencer.sh`.

In Bitwig:

1. Fully restart Bitwig and verify Loaded build. Only Logic 16A–E should appear on
   the two-control page. With velocity disabled, original velocities must stay intact.
2. With notes at sixteenth positions 0/3/4/7 and velocity 100, use Settings → Groove
   velocity: Enable On, Amount +100, Min 70, Max 120. Expect 120/70/120/70 at their
   original logical pads. Negative 100 should produce 70/120/70/120.
3. Try Amount +50 (110/85/110/85), change Phase, then reset velocity. Original
   velocities must return while timing stays put. Set the display Amount to zero with velocity
   enabled and verify both timing and velocity restore. Raise it again to reapply
   both layers. Choose Logic 16A for velocity without timing swing.
4. With both layers active and Groove Lock On, add/remove pads and fill/reduce the
   Euclidean sequence repeatedly. Surviving notes must not drift; new notes inherit
   both layers. Return both amounts to zero: only surviving original/new notes
   remain, with their own original velocities and timing.
5. Repeat during playback and across multiple children. Confirm stable pad lights,
   no changed durations/chance/repeats/expressions, and no conflict notifications.
   Test a non-MIDI-integer host velocity for exact off/zero restoration. Verify
   saved enabled preferences do not write to a new clip on extension startup.


Build 2 regression: leave Phase at 0, Min at 70 and Max at 120; set Enable On and
Amount +100. After restart, adjust the Fire Groove/Amount control once. Verify
velocity applies to all eligible child notes without first touching Phase or the
range controls. `GrooveVelocityPreferencesChecks` simulates Bitwig omitting all
unchanged-default callbacks, then checks activation, live edits and deactivation.
`GROOVE_REQUEST` now records the preset and effective timing/velocity amounts;
`GROOVE_VELOCITY` records subsequent settings changes. Default preset is Logic 16C.


Build 3: the Fire percentage is now a master for both layers. The pure engine
still supports independent timing/velocity, but `GrooveSettings.forController`
scales the signed velocity amount with the display percentage. Added regression
coverage checks exact original note equality at zero across three children,
repeated master changes, unchanged saved preferences, and zero after lock edits.

The master/lock regression also adds a note at display 50% and checks its rounded
nudge and blended velocity without another parameter change. In Bitwig, leave
Groove Lock On, set a nonzero display amount, then add notes through pads and the
Euclidean controls. Let the queued child updates finish; verify timing/velocity
inheritance, then return the display to zero to restore those notes' own originals.


Build 4 latency checks: `GrooveLatencyChecks` proves the edited last child writes
before reading the other two, unchanged children perform only one comparison read,
and repeated no-op settings updates skip their write-pass visits. It also tests
global preflight during a local scan, cancellation mid-preparation, retry after a
temporary playing restriction, and context changes before writes. Live checks:
select the last child and add/delete notes with pads/Euclidean controls, then draw
notes manually in Bitwig; verify the selected child's update starts promptly,
other children keep their timing, and zero restores timing/velocity. Change Groove
while lock work is pending, then capture another scene; verify no stale writes.
No fixed wall-clock latency is asserted by the proxy tests.
