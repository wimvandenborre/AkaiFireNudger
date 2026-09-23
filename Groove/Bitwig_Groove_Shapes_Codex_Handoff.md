# Codex handoff — Bitwig Groove Shapes

**Feature:** Reversible groove/nudge shapes over the existing MIDI note-nudging implementation.
**User workflow:** Drum instruments already have separate MIDI clips.
**Companion:** `Bitwig_Groove_Shapes_Test_Vectors.json`
**Specification date:** 23 September 2026.

## 1. Assignment

Implement this feature in the current repository. Inspect the actual project first, then make working changes and add tests. Do not stop at another architecture proposal.

The user already has a Bitwig extension and is developing note nudging. Extend that implementation; do not build a replacement extension, a separate MIDI processor, or a new desktop application.

**The central design decision is that one instrument already lives in its own MIDI clip.** Apply groove settings to that clip, rather than extracting instruments from a combined drum clip or identifying an instrument solely by MIDI pitch. Different instrument clips can contain the same MIDI note number and must remain independent.

The feature should provide:

- Independently adjustable groove shapes for each explicitly targeted instrument clip.
- Musical templates and mathematical timing curves, applied by rhythmic position.
- Reversible edit sessions without cumulative drift.
- Optional linked-clip editing with a shared amount and explicitly defined alignment, only where the real host integration can support it safely.

Start with selected-clip operation, make that safe and complete, then enable linked targets and motion shapes. Do not let an unavailable advanced host capability prevent shipping the supported core.

## 2. Source of truth and repository audit

The repository, its pinned Bitwig API dependency, and its working note-editing code are authoritative. No repository or current API adapter was inspected when this handoff was prepared. All component names below describe responsibilities, not existing files or Bitwig SDK methods.

Before changing code, identify and record:

1. Project language, build/test commands, pinned SDK/API version, and existing UI/controller bindings.
2. Current note-nudge entry points, smallest supported movement increment, coordinate units, note-duration handling, and collision protections.
3. How clip targets and note selections are represented, how notes are enumerated, whether enumeration is paged/windowed, and how read completion is established.
4. Whether stable clip/note identities, loop markers, expression preservation, multi-clip access, write acknowledgements, and native undo grouping are actually available.
5. The host-thread/scheduler and observer conventions already used by this extension.

Preserve the working nudge calculations and protections. If they restrict movement to a cell or change duration to avoid overwriting another note, do not silently bypass those restrictions: expose an accurate unsupported/blocked result or deliberately improve the adapter with regression tests.

Do not invent convenient SDK calls such as a generic note timeline, bulk note setter, note mover, or transaction API. An application-level adapter is fine; every operation inside it must resolve to verified existing code or documented methods in the pinned SDK.

Do not assume this repository uses another project’s JUCE, JavaFX, Scala, Java, controller layout, or messaging architecture. Reuse what is actually present. Bitwig’s official extension repository points to the installed scripting guide/API reference under **Help → Documentation → Developer Resources**. [R3]

Produce a short capability report, citing real source files and symbols. Distinguish verified support from assumptions and from features disabled for lack of support.

## 3. Product semantics

### 3.1 This is an edit operation, not a playback plugin

The initial implementation changes stored MIDI note positions through the existing editor/nudge path. An audible preview therefore also edits clip data.

Call this a **reversible edit preview**, not a guaranteed nondestructive playback layer. Reversal is available only while the original snapshot is retained, the target identity remains valid, and no conflicting external edits have occurred. Do not promise crash-proof restoration or restoration after reopening a project unless those capabilities are separately implemented and tested.

Ableton separates grid quantization from template timing, and committing a groove writes its effect into the clip. Its manual also describes splitting a voice into a separate clip to groove that voice independently. This project already has that separation. [R1]

### 3.2 Targeting

Default target: **all eligible notes in the explicitly selected MIDI clip**.

Additional scopes, enabled only when verified:

- Selected notes in that clip, with selection frozen when the session starts.
- An explicit set of linked instrument clips, with independent settings per member.

Never broaden a selected-notes operation to a whole clip because selection access is unavailable. Never target all playing clips, every clip on a track, or every clip in a scene implicitly.

Freeze the target set before previewing. Renaming a track or using the same note number elsewhere must not change the target. A displayed clip name, a mutable slot index, and the currently focused cursor are not sufficient identity guarantees by themselves.

No extraction, track creation, routing changes, clip duplication, or instrument remapping is part of this feature.

### 3.3 First-release boundary

Required core: selected-clip operation; eight musical presets; precise grid indexing; depth, phase and base; staged inspection; safe apply; reversible preview where supported; bypass/reset/cancel; preservation and tests.

Then implement six motion shapes and explicit linked-clip editing where the audited host access supports them. Keep unsupported actions disabled with a specific explanation rather than silently substituting a different operation.

Velocity shaping, MIDI/AGR import, audio groove extraction, realtime MIDI delay, realtime song-phase processing, and commercial groove-library bundling are later work, not dependencies for this release.

## 4. User experience

Integrate with the existing nudge page/panel and controller conventions. Preserve the existing manual left/right nudge workflow.

### Main controls

| Control | Required meaning |
|---|---|
| Target | Exact clip, selected-note scope, or explicitly linked clips. |
| Bank / Shape | Groove or Motion; preset choice. |
| Depth | 0–100% of that shape’s displacement. |
| Base | Eighth, sixteenth, or thirty-second note. Default: sixteenth. |
| Phase | Signed integer slot rotation of the shape. |
| Cycle | Motion shapes only in v1; musical templates keep their native slot period. |
| Preview | Explicitly enable/disable write-based audition, if supported. |
| Bypass | Restore session-original notes temporarily while retaining settings. |
| Apply | Commit the current result once. |
| Reset | Restore originals and set Depth, Quantize and Bias to zero. Keep the session open. |
| Cancel | Restore originals and close the session, only when conflict-free. |

Advanced controls: pre-quantization, constant timing bias, protected beat anchors, off-grid inclusion, motion range, and random seed.

Opening the page, focusing a clip, restoring UI settings, or loading a project must not write notes. A newly selected preset can stage a result. It may update audition only within an already explicitly enabled preview session.

Show the target name, number of affected/protected/off-grid notes, native shape period, early/late offset range, and any blocked operation. Display movement in subdivision percentage and current-tempo milliseconds. Beat positions, not displayed milliseconds, are authoritative.

A small timing graph should show original grid positions and displacement; distinguish protected notes and absent grid events. Do not redesign unrelated mixer/controller screens to add it.

### Independent instruments and linked amounts

Store settings against the verified clip target, not a global note pitch. Switching from hats to percussion must not silently copy settings.

A linked group has a master depth, while each member retains its own shape, clip depth, bias, phase and protection. Global amount does not mean every instrument gets the same shape. Explicitly excluded members remain untouched.

Example starting arrangement, for a user-assigned group—not an automatic name-based rule:

| Instrument clip | Shape | Depth | Bias |
|---|---|---:|---:|
| Kick | Excluded | — | — |
| Closed hats | Deep Swing 57 | 100% | 0 |
| Extra percussion | Asymmetric Roll | 50% | 0 |
| Clap on quarter-grid beats | Deep Swing 57 | 100% | +0.05 base units |

The clap example uses the swing template’s zero offsets on those beats; its bias adds a small late placement. At 125 BPM with sixteenth-note base, that bias is +6 ms. This is an illustrative setup, not a prescribed musical rule. Do not infer roles from names or pitches and modify clips without selection.

## 5. Units and timing model

### 5.1 Representation

Use quarter-note beats internally, with a sufficiently precise representation compatible with the repository. Do not invent a PPQ resolution or assume a specific existing fine-grid constant.

For a base note denominator `D`:

```text
h = 4 / D              // quarter-note beats per base subdivision
D = 8  -> h = 0.5
D = 16 -> h = 0.25
D = 32 -> h = 0.125
```

Presets contain offsets in **fractions of one base subdivision**. Positive is late, negative is early.

At 125 BPM, a sixteenth is 120 ms; an offset of `+0.10` therefore means `+12 ms` at full depth. This conversion is for display, not a reason to rewrite notes on tempo changes.

### 5.2 Index from original rhythmic position

For note `i`, using its immutable original start `t0`:

```text
origin = loopStartBeats - alignmentOffsetBeats
k      = nearestInteger((t0 - origin) / h)
g      = origin + k * h
slot   = positiveModulo(k + phaseSlots, patternLength)
```

Exact half-way ties choose the earlier grid point. Implement that explicitly; language-default rounding conventions differ. Positive phase reads later entries in the stored pattern. Negative phase must use positive modulo.

`alignmentOffsetBeats` is the groove-coordinate position corresponding to this clip’s loop start. Default is zero. Do not confuse host timeline coordinates, clip-local time, loop start, launch offset, and a visible editor-page offset.

If the existing sequencer already supplies a reliable logical grid association from the original performance, it may be used after proving its equivalence for the selected base. Otherwise use the rule above.

**Do not index by the ordinal number of existing notes.** Empty steps still advance the pattern. The same slot receives the same timing displacement even when earlier notes are missing or removed.

Recompute from the original snapshot when Base or alignment changes. Never recompute grid associations from the already-grooved preview.

### 5.3 Exact transformation

```text
G = clipDepth * masterDepth

targetStart = t0
            + quantizeAmount * (g - t0)
            + G * h * (pattern[slot] + biasInBaseUnits)
```

Default values: `clipDepth=1`, `masterDepth=1`, `quantizeAmount=0`, `bias=0`, `phase=0`, `base=1/16`. These describe the staged effect; they do not authorize automatic writes.

`Quantize` is independent of groove depth. Therefore:

- With Quantize and Bias at zero, Depth zero returns original timing.
- With nonzero Quantize, Depth zero can still move notes towards the grid.
- **Bypass always returns the exact session originals**, regardless of any settings.

Bias is a signed fraction of the base subdivision and is scaled by clip/master depth. Suggested v1 range: −0.25 to +0.25; default zero. Do not store a fixed-millisecond bias and silently rewrite it when tempo changes.

This equation is this extension’s specified model. It is not a claim of exact Ableton-internal emulation.

### 5.4 Eligibility and anchors

Default off-grid capture window: distance from the original note to its nearest grid point must be at most `0.25 * h`, inclusive. Preserve farther-away notes unchanged and report them. This is a deliberate conservative product choice to avoid unexpectedly reshaping flams, grace notes and triplets.

An explicit **Include off-grid** option can override the window. A custom manual protection flag always wins.

Protected quarter-note anchors are original events whose associated grid point lies on the quarter-note grid of the chosen origin. Protection is evaluated before phase rotation. Protected notes retain their original start and all properties; quantization and bias do not move them either.

Musical presets default to anchor protection off. Motion presets default to anchor protection on. Expose the state clearly and preserve explicit user choices. Do not protect a note merely because it happens to be a kick pitch.

## 6. Groove bank: exact presets

These are original mathematical designs. They are not extracted Ableton, MPC, SP1200, or other hardware grooves. Do not use manufacturer branding to imply captured authenticity.

| ID | Display name | Offsets in base units |
|---|---|---|
| `soft_swing_54` | Soft Swing 54 | `[0, 0.08]` |
| `deep_swing_57` | Deep Swing 57 | `[0, 0.14]` |
| `house_swing_60` | House Swing 60 | `[0, 0.20]` |
| `garage_swing_62` | Garage Swing 62 | `[0, 0.24]` |
| `asymmetric_roll` | Asymmetric Roll | `[0, 0.10, -0.03, 0.18]` |
| `push_pull` | Push–Pull | `[0, 0.08, -0.04, 0.12, 0, 0.04, -0.06, 0.16]` |
| `late_offbeat` | Late Offbeat | `[0, 0, 0.06, 0]` |
| `anticipation` | Anticipation | `[0, 0, 0, -0.06]` |

The four swing presets should share one implementation. For swing ratio `r` expressed as a fraction of the pair:

```text
pattern = [0, 2*r - 1]
```

Fifty percent is straight; two-thirds is ideal triplet swing. Do not label groove Depth as swing ratio.

For straight input, zero bias and quantization, and an ordinary phase-zero swing template:

```text
effectiveSwingRatio = 0.5 + (templateSwingRatio - 0.5) * clipDepth * masterDepth
```

Only show that ratio when the conditions make it meaningful. Otherwise show timing displacement. In particular, it is not a meaningful summary for asymmetric curves or already irregular input.

At sixteenth base and 125 BPM, full-depth swing delays are:

```text
54% ->  9.6 ms
57% -> 16.8 ms
60% -> 24.0 ms
62% -> 28.8 ms
```

Do not equate these numbers with Bitwig’s Shuffle percentage. Bitwig documents its own distance-based shuffle control. Also, native global groove can add a further playback treatment to the edited notes; testing must isolate the two. [R2]

Ordinary sixteenth swing does not move a pattern consisting only of eighth-note offbeats. `late_offbeat` exists specifically to move those positions independently.

Musical template lengths are their natural two-, four-, or eight-slot cycles. Do not stretch a two-slot swing pattern into a bar-length ramp when the user changes a display cycle length. In v1, show the native repetition and keep Cycle editing for Motion.

## 7. Motion bank

Implement six named shapes using discrete grid slots. They are musical-time displacement curves, not tempo automation or runtime LFOs.

Let:

```text
j = positiveModulo(k + phaseSlots, N)
u = j / N
pattern[j] = motionRangeInBaseUnits * normalizedShape(j)
```

Default `N=16`, `motionRange=0.10`; expose `N` values 4, 8, 16, 32 and 64 slots and an advanced nonnegative range from 0 to 0.49 base units. Safety checks still apply at every range. Default quarter anchors protected.

| ID | Normalized definition |
|---|---|
| `sine` | `sin(2*pi*u)` |
| `triangle` | `(2/pi) * asin(sin(2*pi*u))` |
| `ramp_up` | `2*u - 1` |
| `ramp_down` | `1 - 2*u` |
| `arch` | `sin(pi*j/(N-1))^2` |
| `seeded_random` | Deterministic slot values defined below. |

The arch is intentionally unipolar and exactly zero at its first and last discrete slots. Do not silently center its mean. Ramps have an intentional discontinuity at wrap; show that in the graph and validate the resulting note spacing.

Report cycle length in slots and beats. Only label it in bars when the time signature supports the conversion; sixteen sixteenths is not universally one bar.

### Reproducible random shape

This is a repeating random **template**, not new randomness on every note, preview, playback pass or refresh. Generate from slot and seed, not note order or note count. Chords on the same grid position move together.

Use this portable reference definition, or an exactly equivalent implementation:

```text
seed: unsigned 32-bit integer, default 0
message = UTF8("groove-shape-v1|" + decimal(seed) + "|" + decimal(j))
digest = SHA256(message)
x = unsigned big-endian integer from digest bytes 0..3
normalizedShape(j) = 2 * x / 4294967295 - 1
```

Only an explicit **New seed** action changes the seed. Two clips with the same seed and settings use the same template. Distinct seeds allow different patterns. A finite random template need not have zero mean; do not secretly recenter it.

The companion JSON contains reference samples for all six shapes. Keep hashing outside the host write loop; cache samples by relevant parameters.

## 8. Shared alignment and the short-loop limitation

### 8.1 Loop-relative mode is the default

Anchor the shape at each clip’s actual loop start, not the first existing note and not the current playhead position. Different loop-start markers must be correctly mapped into the normalized coordinates above.

For clips launched at aligned phases, the same origin/base/phase settings produce a shared static timing reference, with independent depth and shape per instrument.

This does not synchronize independently launched clips. Do not describe static note edits as a live song-phase engine.

### 8.2 A short stored loop cannot contain successive phases of a longer curve

A one-bar clip with one stored set of note positions repeats those positions every bar. It cannot alternate between the first and second halves of a two-bar groove without expanded clip content or runtime processing.

For a static shape with effective period `P` beats and a clip loop of `L` beats, a conservative compatibility requirement for continuous group-phase behavior is:

```text
L / P is an integer
```

Use the **effective repeated transformation**, including active protection masks and the quantization reference grid where relevant, not merely an expanded array’s declared length. A sixteen-slot array containing repeated two-slot swing still has a two-slot timing period. With pre-quantization active, also require the loop to span an integer number of base subdivisions for continuous grid alignment. A truly constant offset, or a zero transformation with quantization disabled, has no varying phase to preserve.

Examples:

- One-bar and two-bar clips can both carry a two-sixteenth swing cycle continuously.
- A genuinely two-bar motion curve cannot remain continuous across repeated one-bar clip content.
- A three-sixteenth loop cannot retain the same continuous phase of a two-sixteenth swing cycle on every repetition.

When incompatible, show the actual limitation and allow an explicit **Reset shape each clip loop** mode, or a shorter compatible cycle. Do not silently duplicate, extend, consolidate or rewrite the loop length. Continuous song-relative launcher processing is out of scope.

All clips in a batch must be checked individually. Group membership alone is not proof of compatible lengths, origins, launch phases or data access.

## 9. Snapshots and preservation

Separate a pure transformation engine from host editing. Suggested responsibilities:

```text
Shape definitions / generator
Original clip and note snapshot
Per-clip settings and optional group settings
Pure transformation planner
Boundary / overlap / capability validation
Reversible session coordinator
Existing-host-API adapter
UI / controller binding
```

Adapt these responsibilities to the real codebase; do not create needless frameworks.

A snapshot needs the full relevant note data, not just pitch and start. Preserve duration, channel, velocity, release velocity where present, mute state, expressions, chance/operators/repeats, and any other per-note data the current host supports. Treat these properties in their actual host representation; the JSON fixture velocity values are only test scalars.

Prefer verified in-place timing movement that preserves attached data. Delete-and-recreate is unacceptable unless the adapter proves a lossless round trip for every affected property. Reject unsupported notes before writing rather than quietly dropping expression or operators.

Move note end by the same delta as its start; duration stays unchanged. A duration-editing mode is not part of v1. Preserve existing overlaps where the adapter supports them; do not silently create new same-channel/same-pitch overlaps or reorder those notes.

Read beyond the visible editor page. Collision validation must include untouched neighboring notes and loop-boundary neighbors. Never report a whole-clip success when only the currently observed page was enumerated.

Use a frozen original-note key inside the session. Do not recover identity solely from the note’s new position after each preview. If matching becomes ambiguous or the host target changes, stop writing.

## 10. Reversible edit-session lifecycle

### Capture and stage

On explicit session start, pin targets; await complete, verified reads; retain immutable original snapshots and content fingerprints; build an initial plan without changing notes.

Every plan is derived from the original snapshots. Keep original positions, grid associations, durations, properties and target bindings stable through all depth/preset changes.

### Preview

When the user explicitly enables preview, validate the complete plan and then write it through verified host operations. Cache the expected applied state and await observer/write confirmation according to the real API.

A later parameter change computes a fresh target from originals. It does **not** add another displacement to the currently shifted notes. Diff against the last confirmed preview only to minimize writes, never to define the musical baseline.

Coalesce rapid control updates. Reuse the project’s event/scheduler pattern and host thread. Discard queued work tagged with stale session, target-generation or parameter-revision identifiers. Do not perform GUI-thread or background-thread host writes simply because they are convenient.

### Bypass, reset and cancel

Bypass restores originals but retains all settings. Re-enable preview to reapply the same transform from the same originals. Reset restores originals and sets Depth, Quantize and Bias to zero. Cancel restores originals and closes the session.

Restoration must use original host values, not an inverse mathematical transform or a second quantization pass. No cumulative rounding is acceptable.

### Apply

If the confirmed preview already equals the desired result, Apply must not apply the shape again. It simply commits that result and ends the reversible session. From staged-only state, Apply validates and writes exactly once before closing.

In v1, a new session after Apply captures the then-current notes as its baseline. Mark the last result as applied; do not imply the old straight performance remains recoverable after its snapshot has been discarded. A further operation is intentionally additive to the current performance and must never happen automatically on reopening the panel.

### Focus changes and external edits

Changing focus must not retarget pending writes. Keep the current session pinned and require an explicit Apply/Cancel resolution in the UI before changing targets, or safely invalidate it before another target can be written.

If a user action, native undo, recording, another extension, or an unexplained observer update changes relevant content, enter a conflict state. Stop automatic preview and restoration. Never overwrite those changes with a stale original snapshot.

An explicit new capture may adopt the current notes after the user resolves the conflict. If safe restoration is no longer possible, say so; preserve the current host content rather than claiming Cancel succeeded.

Correlate expected own edits with readbacks; a blanket “ignore all observers while applying” flag can lose real edits. Use the repository’s proven mechanism or implement and test a bounded expected-state protocol.

### Persistence

Persist reusable shape settings through existing project/preferences facilities when appropriate. Persist per-clip bindings only when target identity is trustworthy. Do not maintain fragile cross-project associations by clip name.

Never reapply a saved groove automatically at extension startup. Stored note positions are already edited. Settings persistence is not evidence that original-note snapshots or reversibility survived a restart.

## 11. Safe write planning

### Validate before any write

Validate target identity, complete read coverage, unchanged baseline/last-preview state, requested scope, time precision, loop limits, note-property preservation, and all final collision conditions.

Round a desired new position once at the adapter’s actual supported movement resolution, then revalidate. Do not quantize the pure shape table to a coarser display grid. If the requested displacement is below supported resolution, show the limitation rather than claiming it was applied accurately.

Original untouched notes and restored originals retain their original values; do not run them through new-position rounding unnecessarily.

### Boundaries

Default: no newly moved start before loop start or at/after loop end. Use the actual supported editable region and note semantics; account for pre-existing pickup/tail notes without claiming they are ordinary loop notes.

An out-of-bounds target rejects the entire requested batch before writing. Do not silently clamp, wrap, skip an invalid transformed note, change the loop markers or extend the clip. Protected/off-grid notes are different: they were deliberately excluded from transformation and must be reported as such.

Preserve note tails crossing the loop boundary only where existing host behavior and the adapter demonstrably support them. Otherwise reject before mutation. Include circular same-pitch collision checks where looping makes them relevant.

### Intermediate writes also matter

A safe final arrangement is not enough if an intermediate move overwrites another note. Reuse a verified batch/in-place path or derive a safe movement order. A blanket left-to-right or right-to-left order is not sufficient for mixed-sign transformations.

If a required movement cycle cannot be executed safely through available operations, reject it. Do not use arbitrary temporary pitches, negative positions, or hidden staging clips as a workaround.

### Multi-clip edits are not assumed atomic

Preflight every target before beginning the batch. Do not advertise an atomic transaction or a single native undo step unless the SDK and implementation actually provide it.

On a write failure, stop remaining writes. Restore only states the session can still verify as its own changes; do not overwrite external edits during recovery. Report partial or uncertain completion explicitly when verified recovery is impossible.

Use native undo grouping only where supported and tested. The session’s own exact snapshot restoration is separate from host undo behavior.

### Playback

The first safe fallback is staged editing and Apply while transport is stopped. Block edits to a clip being recorded.

Enable write-based preview while playing only after proving the actual API path preserves playback safely. Avoid flooding edits near active note on/off events or causing stuck notes. Multi-clip sequential edits must not be marketed as a sample-accurate live groove engine.

Do not automatically disable native Bitwig global groove in the user’s project. Warn when its additional treatment is detectable; use a disposable test project with it off for integration tests. [R2]

## 12. Tests and completion criteria

Import the companion JSON as a test resource or translate it into the repository’s native test framework. It supplies eight musical preset definitions, **33 numerical cases**, samples for six motion shapes, three rejection cases, and four loop-compatibility cases.

The numerical reference examples were checked in a standalone model. They do not validate Bitwig host access, preservation, native undo, observer handling or live playback.

### Pure engine

Pass all numerical cases, including:

- Exact 54/57/60/62 swing, independent master and clip depth, and each asymmetric template.
- Sparse patterns, missing earlier notes, arbitrary note-list ordering, and simultaneous chords.
- Eighth/sixteenth/thirty-second base changes.
- Positive/negative phase, nonzero loop start, explicit alignment offset, and earlier-grid tie resolution.
- Existing microtiming, independent pre-quantization, bypass, capture-window edges, and protected anchors.

Verify all motion samples, deterministic random reproduction, arch endpoints, ramp discontinuity, and no dependence on note enumeration order. Use the supplied tolerances for mathematical comparisons, not as a substitute for the host’s actual resolution.

Add an explicit drift test: depth sequence `0 → 1 → 0.25 → 0.8 → 0`, computed from one snapshot, restores identical original timing when Quantize and Bias are zero. Repeat many times; preset switches and bypass cycles must also leave no drift.

### Host adapter and sessions

Use mocks/fakes for protocol tests plus real integration tests in a disposable Bitwig project:

1. No writes on page open, focus change, startup, or settings restore.
2. One selected instrument clip changes; another clip with the same MIDI pitch does not.
3. Clip identity stays pinned when selection/focus changes during pending callbacks.
4. Every supported note property and duration survives preview, Apply, Bypass and Cancel.
5. Full clip coverage works beyond the visible step window; incomplete reads block whole-clip writes.
6. Stale queued work is discarded; own observer acknowledgements do not become a new baseline.
7. External edits/native undo cause conflict handling, not destructive automatic restoration.
8. Apply after an already-applied preview does not double the offsets.
9. Left/right loop boundary violations and new collisions reject before mutation.
10. Mixed-direction moves cannot overwrite notes at intermediate positions.
11. Group preflight detects any incompatible target before writing; injected write failure is reported accurately.
12. Project reopen does not apply the saved preset again.
13. Stopped/playing/recording behavior matches the actual enabled capability claims.
14. The original manual nudge controls still work outside the groove session.

Compare intended versus observed timing at actual adapter resolution and document any unavoidable error. Do not claim milliseconds of precision the host edit path cannot deliver.

## 13. Implementation order and final report

**A. Audit and baseline tests.** Locate existing code and capabilities; add regression coverage for manual nudging.

**B. Pure engine.** Add original preset definitions, time normalization, deterministic grid indexing and fixture-driven tests.

**C. Selected-clip session.** Add snapshot capture, staged plan, safe apply, reversible preview where verified, conflict handling, and integration with the existing UI.

**D. Motion and linked targets.** Add the six generators and linked independent settings/shared amount, including loop-compatibility checks. Disable any unverified host operations explicitly.

**E. Integration validation and documentation.** Test preservation and timing against a disposable project, document controls and limits, and leave a reproducible test checklist.

At completion report actual changed files/symbols, build/test commands and results, enabled features, disabled capabilities with reasons, and remaining host-dependent checks. Distinguish numerical/unit-test success from actual Bitwig integration testing. No fabricated successful builds, screenshots or host tests.

## 14. Reference sources

These sources inform the design; they do not replace local SDK verification. All source-derived statements are limited to the behavior described above. The proposed formulas, template arrays, architecture and acceptance criteria are this specification’s design choices.

**[R1] Ableton manual — Using Grooves.** Separate quantization/timing controls; commit semantics; independently grooving a separated instrument voice.

```text
https://www.ableton.com/en/manual/using-grooves/
```

**[R2] Bitwig user guide — The Global Groove.** Native project-level playback groove, clip participation, and Shuffle control meaning.

```text
https://www.bitwig.com/userguide/latest/the_global_groove/
```

**[R3] Bitwig official controller-extensions repository.** Location of the installed scripting guide/API reference; use the project’s pinned SDK rather than guessing available methods.

```text
https://github.com/bitwig/bitwig-extensions
```
