# Codex Handoff — Logic 16A–F Groove + Velocity Range

## Goal

Add the six classic **Logic 1/16 swing grooves A–F** to the existing Bitwig groove/nudging implementation.

The timing groove implementation already exists. Reuse it.

Our drum instruments already live in **separate MIDI clips per instrument**, so apply the selected groove directly to the selected instrument clip.

Also add an **independent velocity-groove layer** with a user-definable velocity range.

Do not rebuild the existing timing engine or note-nudge implementation.

---

# 1. Logic 16A–F timing presets

Use these exact Logic swing ratios:

```text
Logic 16A = 50%
Logic 16B = 54%
Logic 16C = 58%
Logic 16D = 62%
Logic 16E = 66%
Logic 16F = 71%
```

These are 1/16-note swing templates.

Within each pair of sixteenth notes:

```text
slot 0 = unchanged
slot 1 = delayed
```

Using the existing groove representation where offsets are fractions of one 1/16 subdivision:

```text
offset = 2 * swingRatio - 1
```

Therefore:

```text
Logic 16A: [0.00, 0.00]
Logic 16B: [0.00, 0.08]
Logic 16C: [0.00, 0.16]
Logic 16D: [0.00, 0.24]
Logic 16E: [0.00, 0.32]
Logic 16F: [0.00, 0.42]
```

Preset IDs:

```text
logic_16a
logic_16b
logic_16c
logic_16d
logic_16e
logic_16f
```

Display names:

```text
Logic 16A — Straight
Logic 16B — 54%
Logic 16C — 58%
Logic 16D — 62%
Logic 16E — 66%
Logic 16F — 71%
```

Default base:

```text
1/16
```

These should use the existing groove Depth control.

Example:

```text
Logic 16D
Depth 0%   -> straight timing
Depth 50%  -> halfway between straight and 62% swing
Depth 100% -> full 62% swing
```

Do not confuse Depth with the displayed Logic swing percentage.

---

# 2. Important: Logic A–F contains timing only

Do **not** claim that Logic 16A–F has an original velocity curve.

The Logic A–F definitions are timing swing presets.

Velocity shaping in our extension is therefore an **optional additional feature**, inspired by groove-pool workflows.

It must be possible to use:

```text
Timing ON
Velocity OFF
```

or:

```text
Timing OFF
Velocity ON
```

or both together.

---

# 3. Velocity controls

Add these controls:

```text
Velocity Enable
Velocity Amount
Velocity Min
Velocity Max
```

Recommended ranges:

```text
Velocity Enable: OFF / ON

Velocity Amount:
-100% .. +100%

Velocity Min:
1 .. 127

Velocity Max:
1 .. 127
```

Default:

```text
Velocity Enable = OFF
Velocity Amount = 0%
Velocity Min = 70
Velocity Max = 120
```

Require:

```text
Velocity Min <= Velocity Max
```

If the user moves Min above Max, either constrain it or move Max with it according to the existing UI convention.

---

# 4. Default velocity profile

Use a simple two-slot velocity profile corresponding to the same strong/weak subdivision relationship as sixteenth swing:

```text
[1.0, 0.0]
```

Meaning:

```text
first 16th  = strong
second 16th = weak
first 16th  = strong
second 16th = weak
...
```

The values are normalized.

```text
1.0 -> Velocity Max
0.0 -> Velocity Min
```

Example with:

```text
Velocity Min = 70
Velocity Max = 120
```

the full pattern becomes:

```text
120, 70, 120, 70, 120, 70...
```

This profile is our extension's velocity behavior.

Do not label it as an original Logic velocity pattern.

---

# 5. Velocity Amount behavior

Model it similarly to a groove-pool amount control.

## Amount = 0%

Preserve the original note velocity exactly.

```text
original = 93
result   = 93
```

No velocity groove is applied.

---

## Amount = +100%

Apply the complete velocity profile.

With:

```text
Min = 70
Max = 120
profile = [1, 0]
```

the target becomes:

```text
120, 70, 120, 70...
```

---

## Amount = +50%

Blend halfway between the note's original velocity and the groove target.

Example:

```text
Original velocity = 100
Groove target     = 70
Amount            = 50%

Result = 85
```

---

## Negative amounts

Negative Velocity Amount reverses the strong/weak relationship.

Therefore:

```text
profile = [1, 0]
```

becomes:

```text
invertedProfile = [0, 1]
```

At:

```text
Amount = -100%
Min = 70
Max = 120
```

the output becomes:

```text
70, 120, 70, 120...
```

At:

```text
Amount = -50%
```

blend halfway from the original velocity toward that reversed target.

This gives useful behavior similar in concept to Ableton's negative Groove Pool Velocity values without claiming exact numerical Ableton emulation.

---

# 6. Exact velocity calculation

For each note:

```text
profileValue = velocityPattern[slot]
```

For positive amount:

```text
p = profileValue
```

For negative amount:

```text
p = 1.0 - profileValue
```

Then calculate the groove target:

```text
grooveVelocity =
    velocityMin
    + p * (velocityMax - velocityMin)
```

Velocity blend amount:

```text
blend = abs(velocityAmount) / 100.0
```

Final velocity:

```text
result =
    originalVelocity
    + blend * (grooveVelocity - originalVelocity)
```

Then:

```text
result = round(result)
result = clamp(result, 1, 127)
```

Pseudo-code:

```java
double p = velocityPattern[slot];

if (velocityAmount < 0) {
    p = 1.0 - p;
}

double target =
        velocityMin
        + p * (velocityMax - velocityMin);

double amount = Math.abs(velocityAmount) / 100.0;

int velocity = round(
        originalVelocity
        + amount * (target - originalVelocity)
);

velocity = clamp(velocity, 1, 127);
```

Adapt types to the actual Bitwig API representation.

If the API represents velocity as `0.0 .. 1.0`, perform the calculation in a normalized equivalent rather than unnecessarily converting back and forth.

---

# 7. Use rhythmic position, not note order

Velocity pattern selection must use the same rhythmic grid association as the timing groove.

For 1/16:

```text
slot = positiveModulo(gridIndex + velocityPhase, velocityPatternLength)
```

Do NOT do:

```text
first existing note  -> velocity slot 0
second existing note -> velocity slot 1
third existing note  -> velocity slot 0
```

because sparse clips would then change groove when notes are added or removed.

Example:

Existing notes at:

```text
16th grid positions:
0, 3, 4, 7
```

Their velocity slots must be:

```text
0 -> strong
3 -> weak
4 -> strong
7 -> weak
```

regardless of how many notes exist before them.

---

# 8. Independent velocity phase

Prefer an independent:

```text
Velocity Phase
```

Default:

```text
0
```

Range can initially use the velocity-profile period:

```text
0 or 1
```

Later it can support longer velocity patterns.

Timing Phase and Velocity Phase should therefore be separate parameters.

Example:

```text
Timing:
Logic 16D
Phase 0

Velocity:
Amount +80%
Phase 1
```

allows timing to swing the second sixteenth while velocity emphasizes it instead.

---

# 9. Velocity range examples

## Subtle hats

```text
Logic 16C
Timing Depth = 70%

Velocity Enable = ON
Velocity Amount = +40%
Velocity Min = 85
Velocity Max = 110
```

Very restrained dynamics.

---

## House hats

```text
Logic 16D
Timing Depth = 100%

Velocity Amount = +70%
Velocity Min = 65
Velocity Max = 120
```

More obvious strong/weak movement.

---

## Strong garage shuffle

```text
Logic 16E
Timing Depth = 100%

Velocity Amount = +100%
Velocity Min = 45
Velocity Max = 125
```

Large dynamic difference.

These are examples only.

Do not hardcode instrument-specific values.

---

# 10. Preserve original velocities

Velocity preview must always derive from an immutable snapshot of the original velocity.

Never do:

```text
currentVelocity = modify(currentVelocity)
```

repeatedly while turning the knob.

Instead:

```text
resultVelocity =
    transform(originalVelocity, currentSettings)
```

For example:

```text
Amount sequence:

0
100
30
80
10
0
```

must return to the exact original velocity at `0`.

No cumulative rounding or velocity drift is allowed.

---

# 11. Timing and velocity snapshots

The existing timing groove session may already contain an original note snapshot.

Extend that snapshot to include original velocity if not already present.

Conceptually:

```text
OriginalNoteState
    start
    duration
    velocity
    pitch
    channel
    other supported note properties
```

Timing transformation reads:

```text
original.start
```

Velocity transformation reads:

```text
original.velocity
```

Both produce one final desired note state.

Do not use the already-previewed timing/velocity values as the next baseline.

---

# 12. Reset and bypass behavior

Velocity controls must not destroy already-applied timing.

## Velocity Amount = 0

Restore original velocities only.

Do not move notes.

## Velocity Bypass

Temporarily restore original velocities.

Keep the selected velocity settings.

## Velocity Reset

```text
Velocity Amount = 0
Velocity Phase = 0
```

Restore original velocities.

Do not reset or alter:

```text
Logic timing preset
Timing Depth
Timing Phase
Timing Bias
```

## Timing Reset

Likewise, resetting timing must not silently reset velocity.

---

# 13. Applying both layers

The transformation pipeline should conceptually be:

```text
ORIGINAL NOTE
      |
      +--> timing transformation
      |
      +--> velocity transformation
      |
      v
FINAL NOTE STATE
```

Not:

```text
original
 -> write timing
 -> reread modified note
 -> modify velocity
```

Build one desired final note state from the same immutable source where possible.

---

# 14. Preset definitions

Add:

```json
[
  {
    "id": "logic_16a",
    "name": "Logic 16A",
    "swingPercent": 50,
    "timingOffsets": [0.0, 0.0],
    "velocityProfile": [1.0, 0.0]
  },
  {
    "id": "logic_16b",
    "name": "Logic 16B",
    "swingPercent": 54,
    "timingOffsets": [0.0, 0.08],
    "velocityProfile": [1.0, 0.0]
  },
  {
    "id": "logic_16c",
    "name": "Logic 16C",
    "swingPercent": 58,
    "timingOffsets": [0.0, 0.16],
    "velocityProfile": [1.0, 0.0]
  },
  {
    "id": "logic_16d",
    "name": "Logic 16D",
    "swingPercent": 62,
    "timingOffsets": [0.0, 0.24],
    "velocityProfile": [1.0, 0.0]
  },
  {
    "id": "logic_16e",
    "name": "Logic 16E",
    "swingPercent": 66,
    "timingOffsets": [0.0, 0.32],
    "velocityProfile": [1.0, 0.0]
  },
  {
    "id": "logic_16f",
    "name": "Logic 16F",
    "swingPercent": 71,
    "timingOffsets": [0.0, 0.42],
    "velocityProfile": [1.0, 0.0]
  }
]
```

Important:

`velocityProfile` is our own optional extension behavior.

The timing percentages are the Logic A–F definitions.

---

# 15. UI suggestion

For each selected instrument clip:

```text
GROOVE
Logic 16D

TIMING
Depth       100%
Phase       0

VELOCITY
Enable      ON
Amount      +65%
Min         70
Max         120
Phase       0
```

Keep this compact.

Since every drum instrument is already in its own clip, there is no need for:

```text
Kick velocity
Hat velocity
Clap velocity
```

inside one groove panel.

The selected clip is the instrument target.

---

# 16. Tests

Add tests for:

### Logic timing

Verify:

```text
16A = [0, 0]
16B = [0, .08]
16C = [0, .16]
16D = [0, .24]
16E = [0, .32]
16F = [0, .42]
```

### Velocity identity

```text
Amount = 0
```

must preserve every original velocity exactly.

### Positive full amount

Given:

```text
original velocities:
100 100 100 100

Min = 70
Max = 120
Amount = +100
```

expect:

```text
120 70 120 70
```

### Positive half amount

Same original:

```text
Amount = +50
```

expect:

```text
110 85 110 85
```

### Negative full amount

```text
Amount = -100
```

expect:

```text
70 120 70 120
```

### Negative half amount

```text
Amount = -50
```

expect:

```text
85 110 85 110
```

### Preserve existing dynamics

Given:

```text
original:
80 100 72 115

Min = 60
Max = 120
Amount = +50
```

blend individually from each original value.

Do not first normalize all input notes to the same velocity.

### Sparse notes

Ensure missing steps do not alter the profile phase.

### Drift

Repeatedly change:

```text
0
100
25
-60
80
-100
0
```

and verify that final velocities exactly equal the original snapshot.

### Independence

Changing Velocity Amount must not alter note start position.

Changing Timing Depth must not alter velocity.

---

# 17. Migration

Existing groove settings must load with:

```text
Velocity Enable = OFF
Velocity Amount = 0
```

Therefore upgrading the extension must not unexpectedly alter velocities in existing projects.

Do not automatically enable velocity because a Logic groove is selected.

---

# 18. Implementation instruction for Codex

Inspect the existing groove/nudge implementation first.

Reuse:

* clip targeting
* grid-index calculation
* original-note snapshots
* preview/session management
* note update path
* UI/controller conventions

Do not invent replacement architecture when existing code already performs these tasks.

Implement the six Logic 16A–F presets and the velocity layer described above.

The timing engine already works. Do not regress or rewrite it unnecessarily.

At completion:

1. Run existing timing tests unchanged.
2. Add velocity unit tests.
3. Verify Amount 0 causes no velocity changes.
4. Verify timing-only use remains possible.
5. Verify velocity-only use remains possible.
6. Verify negative velocity amount reverses the accent pattern.
7. Verify Min/Max range works.
8. Verify no cumulative velocity drift.
9. Report changed files and test results.
10. Do not claim exact Ableton Velocity emulation; this is our deterministic implementation inspired by its positive/negative amount workflow.
