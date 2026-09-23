# Groove Shapes capability audit

Audit date: 2026-09-23. Sources: this repository, the locally installed Bitwig API,
and `~/.m2/repository/com/bitwig/extension-api/25/extension-api-25-sources.jar`.
The repository originally compiled against API 20 and declared API 20 in
`AkaiFireDrumSeqDefinition.getRequiredAPIVersion()`. This change upgrades both to
25. API 25's `Clip` and `NoteStep` editing interfaces are unchanged for this work.

| Area | Verified source / capability | Consequence |
| --- | --- | --- |
| Build | Java 17; Maven; `scripts/check-sequencer.sh` standalone assertion checks | Offline build and regression tests |
| Existing UI | `AccentHandler.handleMainEncoder` and `handeMainEncoderPress`; hold STEP SEQ, turn/press Select | Reuse this page; preserve ordinary accent tap and manual nudge gestures |
| Existing nudge | `FineNudge.STEP_BEATS = 1/64`, `WINDOW = 4096`; `move`, `withinLimit`, `plan` | Whole loop of one pitch, 16 channels, at most 64 beats; ±40% logical-cell limit; collision-aware in-place `Clip.moveStep` |
| Grid observations | `Clip.getStep(channel,x,y)` and `addNoteStepObserver`; `NoteStep.x/y/state` | Cell coordinates, not exact note-on beat positions; a cell can contain multiple onsets |
| Timing | `Clip.moveStep(channel,x,y,dx,dy)`; installed host `ClipProxy.doMoveStep` translates the notes in a cell | Reuse movement quantum; never use `setStep`/delete/recreate or duration setters for groove |
| Properties | `NoteSnapshot.capture` records API-exposed scalar expressions, duration, velocity, chance/occurrence/recurrence/repeats, mute | Scalar observations are not a lossless serialization of arbitrary note-expression curves or multiple notes within a cell |
| Identity | `ObjectProxy.createEqualsValue`, `PinnableCursorClip.isPinned/selectClip` | Independently pin an inspection cursor and compare host objects; no persistent clip/note UUID exposed for cross-project settings |
| Read coverage | Cursor key/step windows; no documented snapshot-complete callback or note count | Repeated stable window reads support **approximate inspection**, not proof of lossless whole-clip capture |
| Undo / transactions | API 25 Application undo/redo actions, no verified note transaction/grouping | Do not promise one undo step or atomic linked edits |
| Playback | Transport playing and slot recording flags available | Drum-cell edits allow playback using the existing nudge path; recording remains blocked |
| Host thread | `ControllerHost.scheduleTask`, existing generation guards and `LogicalStepIndex` expected-cell confirmation | Reuse scheduler and stale-work cancellation; a cell acknowledgement is not proof of complete per-note preservation |

## Explicit drum-cell scope

The user authorized the existing nudging semantics for separate multi-clip drums.
The production adapter applies groove/amount changes through `FineNudge.moveAbsolute`.
This calls the same in-place `moveStep` and `LogicalStepIndex.move` used by manual
nudging. No `setStep`, deletion, recreation or duration change is used. The optional
velocity layer uses only `NoteStep.setVelocity(double)`; all other properties
are retained, with complete scalar fingerprints checked before/after each write. A frozen ID follows each captured onset cell through expected moves.

This is **not** an assertion of exact arbitrary-note reads: the capability record
keeps `exactStarts` and `complete` false and explicitly enables `drumCellMode`.
The scope requires one drum pitch per clip and one onset per channel/fine cell.
An all-pitch census rejects other pitches. Same-cell multiplicity is not observable
and must be excluded by project setup. Fine-cursor reads cover the selected pitch
and every channel across the loop; capture requires repeated stable observations.
Sub-cell residuals and attached data remain on the existing host note. Scalar
fingerprints detect observable external edits; unexposed expression-curve edits
cannot be fingerprinted. Real-host expression preservation still needs testing.

Quantum is 1/64 beat; originals are cell positions and translations are rounded
once from that baseline. Cancel restores the original cell by translating back,
retaining the original residual. Tiny displacements can round to zero, explicitly
reported. Pre-quantization is disabled because exact starts are unavailable.

Writes require no recording, stable object equality, unchanged
loop/pitch/grid/content, and safe collisions and boundaries. The 40% pad cap stays
active. Notes crossing the loop tail are rejected if affected. Selected-note scope remains disabled. Drum-cell playback writes use the manual-nudge semantics. Linked child clips now
use independent snapshots and a serial batch coordinator. No native undo
transaction or atomic batch is claimed. Unexpected readback stops the batch;
Reopening the two-control page after a conflict discards old snapshots and starts
a new baseline at Amount 0%, retaining host edits. No per-clip identity or
settings are persisted by name. Opening the page never writes automatically.

## Changed implementation files

- `GrooveShapes`, `GrooveSettings`, `GrooveEngine`: exact mathematical definitions,
  original-position planning and effective period calculations.
- `GrooveSafety`, `GrooveSession`: capability preflight, collision/order validation,
  immutable state, explicit actions and bounded expected-state acknowledgements.
- `GrooveHostPort`, `GrooveControl`: independently pinned drum-cell sessions, scope checks and
  existing Fire page integration through FineNudge.
- `AccentHandler`, `DrumSequenceMode`: replace the old groove controls, preserve
  ordinary accent/manual-nudge controls, invalidate sessions on target changes.
- `pom.xml`, `AkaiFireDrumSeqDefinition`, check/install scripts: API 25, build ID,
  suite registration and archive-content validation.
- `GrooveFixtureChecks`, `GrooveSessionChecks`, `GrooveHostChecks`, `FixtureJson`:
  reference-data and adapter/protocol checks; existing nudge tests remain active.
- `archive/t1-velocity-groove/`: original velocity engine, control handler, tests
  and integration excerpt. The three original source/test files are byte-for-byte
  copies and are excluded from Maven compilation and the `.bwextension`.
- `Groove/`: supplied handoff and JSON retained as the specification/reference data.

## Group groove controls (build 0.83-groove-shapes-2)

`GrooveControl` now exposes only Groove and Amount. `GrooveBatch` automatically
captures all nonempty eligible children in the Fire's captured scene, preflights
them all, and serializes previews. Zero restores original timing; release retains
snapshots. Knob events are coalesced. Group/scene/membership/grid changes cancel
queued work and reset Amount. Lane selection alone retains the session.

`GrooveClipWorker` owns a private CursorTrack, launcher cursor and FineNudge.
It uses the existing `ClipSceneSeek`, verifying child track and scene observations,
then `selectClip` to bind the fine view. It does not call slot select/showInEditor,
selectSlot or launch. Each child has a uniquely named pinned GrooveHostPort; the
worker is shared and navigation completes before each capture/read. The pitch
census uses 32 time cells across 128 keys. All host objects are created at startup.
`FineNudge.mirrorMove` projects a visible child's background moves into its pad
index while awaiting host confirmation. Other lanes use the same nearest-pad map
when selected. Duration/property and 40% protections remain in the shared engine.

This implements a serial child batch, not native transactional linked editing.
Real Bitwig verification remains required for independent cursor navigation,
pinning and observer timing; tests use SDK proxies. No rollback over an unknown
external edit, atomic multi-clip undo, or crash-persistent original snapshot is
claimed.

Build `0.83-groove-shapes-4` removes the blanket playback gate for drum-cell
nudging after the runtime log showed it blocked every user attempt. Read-only
inspection still requires stopped transport. Capture, preflight and acknowledgements
continue during playback; recording and content/target conflicts still stop writes.
This does not promise sample-accurate live scheduling or repair an already sounding
voice: real playback verification remains necessary, as with manual nudging.


Build `0.83-groove-shapes-5` adds default-on Groove lock in Sequencer settings.
Stable double reads reconcile membership: surviving IDs retain original starts;
new IDs capture their observed timing; deleted IDs leave the snapshot. Current
non-timing properties are retained. Adopted addresses use separate channel metadata
and fresh IDs, avoiding identity reuse when cells are refilled. Known Fire edits
cancel queued work first; a single in-flight acknowledged move is recognized when
reconciling. Periodic shared-worker reads cover other clip edits, without writing
when nothing changed. Recording/context mismatches still prevent adoption/writes.

The OLED now shows only groove name and amount. Diagnostics remain in the log and
Bitwig notifications. Child playingNotes subscriptions restore activity feedback
independently of the group's playingNotes stream and the captured editing scene.
No MIDI routing or clip launcher behavior is changed by the lights.


## Logic / velocity update (0.84-logic-groove-1)

Visible templates are Logic 16A–E. Exact mathematical offsets are 0/.08/.16/.24/.32
of a sixteenth; real movements retain the existing 1/64-beat rounding. 16F is not
included, as requested, so no cap exception or timing clamp was added.

`GrooveVelocity` adds an independent signed blend from immutable original host
velocity doubles to MIDI-range strong/weak targets. `NoteSnapshot.withVelocity`
changes only velocity. `GrooveVelocityPreferences` owns Settings-only controls,
with initial observer delivery explicitly excluded from applying clip changes.
`GrooveEngine` plans both layers together; `GrooveSafety` accepts only the exact
specified velocity transformation, continuing to reject other property changes.
`GrooveSession` acknowledges timing and velocity operations separately and retains
original velocities during lock reconciliation. Timing reset does not reset the
velocity layer. `GrooveBatch` counts distinct edited notes per child.

API 25 declares `NoteStep.velocity()` and `setVelocity(double)`. The installed
Bitwig `NoteStep`/`ClipProxy` implementation confirms double-valued cached velocity
and in-place `updateStepVelocity`; this path does not recreate notes. Production
adapter tests exercise this setter at the acknowledged destination, restore the
exact original double, and reject unexpected properties. No broadened readback
tolerance is introduced. Real-host observer precision and arbitrary expression
curves still require Bitwig testing; unexpected readback stops further writes.

No originals are saved across restart. Persistent velocity preferences can load
without editing clips; the next explicit adjustment captures a new baseline.
Logic presets are timing only; the optional accent profile belongs to this
extension and makes no exact Ableton velocity emulation claim.


Build 2 host finding: installed `NumberSetting` initializes `mRawValue` from its
specified default; `AbstractRangedValueProxy.addRawValueObserver` registers the
observer, while `notifyObservers` calls it only when raw values differ. An unchanged
default therefore cannot serve as a readiness callback. Preferences now use
interested `getRaw()` / enum `get()` snapshots on explicit groove activation;
callbacks update an already activated session. This preserves the startup no-write
rule without waiting for nonexistent default echoes.


Build 3 UI change: the Fire Amount now scales both the timing depth and signed
velocity amount through `GrooveSettings.forController`. Display zero restores both
layers; saved velocity preferences are not changed. The pure engine retains
independent layer support. Logic 16A remains the velocity-only preset. Groove Lock
uses the same master-scaled settings for new notes, with stable-read/debounce delay;
it does not require another encoder gesture. It is not a sample-accurate insertion
transform and can take longer across several child clips.


Build 4 optimizes traversal, not the host note-write protocol. Known Fire edits
prioritize their child; background scans prioritize the currently selected child.
An unchanged snapshot can exit after one read because it neither adopts a new
baseline nor writes. Changed snapshots still need the original two stable reads.
Local Groove Lock applies only the changed child's existing settings immediately
before scanning others. Global settings changes retain all-child preflight; empty
move plans skip redundant write-pass visits. Pending repairs survive cancellation
and temporary blocking. Per-write target, transport and scalar checks remain;
shared-worker navigation and acknowledgement timings are unchanged. The shorter
150 ms debounce / 500 ms idle poll do not imply sample-accurate or instant editing.
