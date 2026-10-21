package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.*;
import java.lang.reflect.Proxy;
import java.util.*;

/** Verify the actual rack API calls and note lifetimes, rather than just routing configuration. */
public final class DrumPadNoteInputChecks {
    static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }

    public static void main(String[] args) {
        List<String> calls = new ArrayList<>();
        Integer[][] mapping = new Integer[1][];
        Integer[][] velocities = new Integer[1][];
        MulticlipTargetChecks.Api arp = new MulticlipTargetChecks.Api("arp");
        NoteInput input = (NoteInput) Proxy.newProxyInstance(NoteInput.class.getClassLoader(),
                new Class<?>[]{NoteInput.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "setKeyTranslationTable": mapping[0] = ((Integer[]) a[0]).clone(); return null;
                        case "setVelocityTranslationTable": velocities[0] = ((Integer[]) a[0]).clone(); return null;
                        case "setShouldConsumeEvents": check(Boolean.FALSE.equals(a[0]), "hardware controls retain pad events"); return null;
                        case "includeInAllInputs": return Proxy.newProxyInstance(SettableBooleanValue.class.getClassLoader(),
                                new Class<?>[]{SettableBooleanValue.class}, (q, op, values) -> {
                                    if (op.getName().equals("set")) check(Boolean.TRUE.equals(values[0]),
                                            "native input retains the pre-multiclip All Inputs path");
                                    return null;
                                });
                        case "arpeggiator": return arp.proxy(Arpeggiator.class);
                        case "sendRawMidiEvent": calls.add("midi:" + a[0] + ":" + a[1] + ":" + a[2]); return null;
                        default: throw new AssertionError(m.getName());
                    }
                });
        MulticlipTargetChecks.Api exists = new MulticlipTargetChecks.Api("exists"); exists.value = true;
        MulticlipTargetChecks.Api name = new MulticlipTargetChecks.Api("name"); name.value = "Drums Master";
        MasterTrack rack = (MasterTrack) Proxy.newProxyInstance(MasterTrack.class.getClassLoader(),
                new Class<?>[]{MasterTrack.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "exists": return exists.proxy(BooleanValue.class);
                        case "name": return name.proxy(SettableStringValue.class);
                        case "position": throw new AssertionError(
                                "Rack routing must not read an unsubscribed master position, even for diagnostics");
                        case "addNoteSource": calls.add("rack:add"); return null;
                        case "removeNoteSource": calls.add("rack:remove"); return null;
                        case "startNote": calls.add("on:" + a[0] + ":" + a[1]); return null;
                        case "stopNote": calls.add("off:" + a[0]); return null;
                        default: throw new AssertionError(m.getName());
                    }
                });
        List<String> routes = new ArrayList<>();
        Track track1 = repeatTarget("Track 1", routes, exists, name);
        Track track2 = repeatTarget("Track 2", routes, exists, name);
        Track[] configured = {track1};
        NoteRepeatInput repeat = new NoteRepeatInput(input, () -> configured[0], text -> {});
        DrumPadNoteInput pads = new DrumPadNoteInput(input, rack, true, repeat, text -> {});
        check(Arrays.stream(mapping[0]).allMatch(n -> n == -1), "physical notes stay blocked so hardware callbacks own audition");
        pads.activate(); pads.update(true, 36); pads.pad(0, true, 100, false);
        check(calls.isEmpty(), "no notes until rack master is acquired");
        pads.rackReady(); calls.clear();
        check(routes.equals(List.of("Track 1:add")), "native source connects to the configured project track, independently of audition master");
        for (int pad = 0; pad < 16; pad++) {
            pads.pad(pad, true, 100, false); pads.pad(pad, false, 0, false);
            check(calls.equals(List.of("on:" + (36 + pad) + ":100", "off:" + (36 + pad))),
                    "top-row pad directly triggers its C1-through-Dsharp2 rack cell");
            calls.clear();
        }
        pads.pad(0, true, 127, false); pads.pad(3, true, 100, false);
        pads.update(false, 36); pads.pad(0, false, 0, false); pads.pad(3, false, 0, false);
        check(calls.equals(List.of("on:36:127", "on:39:100", "off:36", "off:39")),
                "releasing Select first stops every voice once, retaining accent and polyphony");
        calls.clear(); pads.pad(0, true, 100, false); pads.pad(0, false, 0, false);
        check(calls.isEmpty(), "normal pad selection never sends notes or note-offs");
        repeat.activate(); repeat.velocity(127); pads.update(true, 36);
        for (int key = 0; key < 128; key++) {
            int expected = key >= 0x36 && key < 0x46 ? key - 0x36 + 36 : -1;
            check(mapping[0][key] == expected, "native input maps only the top row to rack pitches");
        }
        check(velocities[0][0] == 0 && velocities[0][32] == 127 && velocities[0][127] == 127,
                "native velocity translation preserves note-off zero and applies Accent to real hits");
        calls.clear(); pads.pad(1, true, 127, true); pads.pad(1, false, 0, true);
        check(calls.isEmpty(), "hardware matcher callbacks never double-trigger the native repeat input");
        check(Boolean.FALSE.equals(arp.node("usePressureToVelocity").value), "repeat uses note velocity, not missing aftertouch");
        check(Boolean.FALSE.equals(arp.node("isFreeRunning").value), "native timing is synchronized to the transport");
        check(Boolean.FALSE.equals(arp.node("shuffle").value) && arp.node("humanize").value.equals(0.0), "straight timing has no shuffle or humanize");
        check(arp.node("gateLength").value.equals(0.5) && Boolean.FALSE.equals(arp.node("enableOverlappingNotes").value), "native engine owns gate lengths without overlaps");
        check(Boolean.TRUE.equals(arp.node("terminateNotesImmediately").value), "native pad release terminates the sounding hit");
        repeat.rate(1.0 / 6);
        check(arp.node("rate").value.equals(1.0 / 6), "triplet rate is sent directly to the native engine in beats");
        repeat.velocity(100);
        check(velocities[0][50] == 100, "normal/accent changes update native input velocity");
        repeat.deactivate();
        check(Arrays.stream(mapping[0]).allMatch(n -> n == -1), "BROWSER off blocks physical note forwarding again");
        check(Boolean.FALSE.equals(arp.node("isEnabled").value), "BROWSER off disables native repeat");
        calls.clear(); pads.pad(0, true, 100, false); pads.update(true, 40); pads.pad(0, false, 0, false);
        check(calls.equals(List.of("on:36:100", "off:36")), "bank changes stop the original pitch rather than a recomputed one");
        repeat.activate(); pads.suspend();
        check(Arrays.stream(mapping[0]).allMatch(n -> n == -1), "suspension blocks native input before detaching the rack");
        check(routes.get(routes.size() - 1).equals("Track 1:remove"), "suspension removes the native source from the configured project track");
        calls.clear(); pads.pad(0, true, 100, false); check(calls.isEmpty(), "suspended rack stays silent");
        repeat.deactivate(); pads.rackReady(); pads.update(true, 36); calls.clear();
        Runnable release = pads.testC1(100);
        check(release != null, "diagnostics can test rack without pad matching"); release.run();
        check(calls.equals(List.of("on:36:100", "off:36")), "Test C1 uses the same direct rack endpoint");
        calls.clear(); release = pads.testC1(100); pads.pad(0, true, 127, false); release.run();
        check(calls.equals(List.of("on:36:100", "off:36", "on:36:127")), "a stale diagnostic timer cannot stop a new physical pad hold");
        pads.deactivate(); calls.clear(); pads.rackReady(); pads.pad(0, true, 100, false);
        check(calls.isEmpty(), "late callbacks after deactivation cannot play notes");
        pads.activate(); pads.rackReady(); repeat.rate(0.25); repeat.velocity(100); calls.clear();
        Runnable endNative = repeat.testC1(127);
        check(endNative != null && Boolean.TRUE.equals(arp.node("isEnabled").value), "diagnostic test enables native engine independently of hardware");
        check(calls.equals(List.of("midi:144:36:127")), "native diagnostic sends one held C1, never timer-driven hits");
        endNative.run();
        check(calls.equals(List.of("midi:144:36:127", "midi:128:36:0")), "diagnostic release sends only the matching note-off");
        check(Boolean.FALSE.equals(arp.node("isEnabled").value), "diagnostic restores BROWSER-off state");
        calls.clear(); endNative.run(); check(calls.isEmpty(), "stale diagnostic callbacks cannot affect later holds");
        endNative = repeat.testC1(100); repeat.activate(); endNative.run();
        check(Boolean.TRUE.equals(arp.node("isEnabled").value), "ending a diagnostic preserves a newly enabled BROWSER mode");
        pads.update(true, 36); calls.clear();
        Runnable plain = repeat.testC1(100, false, 0);
        check(Boolean.FALSE.equals(arp.node("isEnabled").value), "plain diagnostic bypasses only the arpeggiator");
        check(Arrays.stream(mapping[0]).allMatch(n -> n == -1), "diagnostic holds block physical notes during the comparison");
        Runnable octaveOne = repeat.testC1(127, true, 1);
        check(calls.equals(List.of("midi:144:36:100", "midi:128:36:0", "midi:144:36:127")),
                "starting another diagnostic releases the prior held note first");
        check(arp.node("octaves").value.equals(1), "octave diagnostic tests the native default range independently");
        plain.run();
        check(calls.size() == 3, "stale plain-note release cannot stop the newer repeat comparison");
        octaveOne.run();
        check(arp.node("octaves").value.equals(0) && Boolean.TRUE.equals(arp.node("isEnabled").value)
                        && mapping[0][0x36] == 36,
                "comparison restores the active repeat configuration and physical input");
        pads.deactivate();
        repeat.attach(); repeat.context(true, 36); repeat.activate(); routes.clear();
        configured[0] = track2;
        repeat.attach(); repeat.context(true, 36);
        check(routes.equals(List.of("Track 1:remove", "Track 2:add")), "setting changes remove the old actual track route before attaching the new destination");
        check(mapping[0][0x36] == 36, "native pad mapping survives retargeting");
        routes.clear(); exists.value = false; configured[0] = track1; repeat.attach();
        check(routes.equals(List.of("Track 2:remove")), "an unavailable configured track detaches the old target without falling back to another track");
        check(Arrays.stream(mapping[0]).allMatch(n -> n == -1), "missing configured destination blocks native input");
        exists.value = true;
        configured[0] = rack; calls.clear(); repeat.attach();
        check(calls.equals(List.of("rack:add")), "native repeat attaches to the exact concrete rack used by Select");
        calls.clear(); repeat.activate();
        check(calls.equals(List.of("rack:remove", "rack:add")), "BROWSER activation refreshes the native route after initialization");
        configured[0] = null; repeat.attach();
        check(repeat.testC1(100) == null, "an unavailable rack cannot start a native diagnostic");
        System.out.println("Rack audition checks passed: exact note-on/off calls, native mapping/configuration, accent, polyphony, releases, bank changes and diagnostics.");
    }
    private static Track repeatTarget(String label, List<String> routes,
                                      MulticlipTargetChecks.Api exists, MulticlipTargetChecks.Api name) {
        return (Track) Proxy.newProxyInstance(Track.class.getClassLoader(), new Class<?>[]{Track.class},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "exists": return exists.proxy(BooleanValue.class);
                        case "position": throw new AssertionError(
                                "Native routing must not read an unsubscribed position");
                        case "name": return name.proxy(SettableStringValue.class);
                        case "addNoteSource": routes.add(label + ":add"); return null;
                        case "removeNoteSource": routes.add(label + ":remove"); return null;
                        case "startNote", "stopNote": throw new AssertionError("repeat must retain native engine timing");
                        default: throw new AssertionError(m.getName());
                    }
                });
    }

}
