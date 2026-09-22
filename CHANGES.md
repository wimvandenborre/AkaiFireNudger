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
