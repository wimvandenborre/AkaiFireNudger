package com.akai.fire.sequence;

import com.akai.fire.display.OledDisplay;
import com.bitwig.extension.controller.api.MidiOut;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Verify packets at the real display/MIDI boundary, including the reported 0xB7 crash. */
public final class OledMidiChecks {
    static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    public static void main(String[] args) {
        List<byte[]> packets=new ArrayList<>();
        MidiOut midi=(MidiOut)Proxy.newProxyInstance(MidiOut.class.getClassLoader(),new Class<?>[]{MidiOut.class},(p,m,a)->{
            if(m.getName().equals("sendSysex")){packets.add(((byte[])a[0]).clone());return null;}
            throw new AssertionError("Unexpected MIDI method: "+m.getName());
        });
        OledDisplay display=new OledDisplay(midi);
        display.sendString(1,OledDisplay.TextJustification.CENTER,0,"Groove \u00b7 all child clips");
        check(payload(packets.get(0)).equals("Groove ? all child c"),"reported separator is replaced before sending");
        display.sendString(0,OledDisplay.TextJustification.LEFT,2,"Kick 100% +1/64");
        check(payload(packets.get(1)).equals("Kick 100% +1/64"),"existing ASCII display text unchanged");
        display.sendString(0,OledDisplay.TextJustification.LEFT,2,"A\u00e9\ud83e\udd41\u00f7\u0100\n\u007fZ");
        check(payload(packets.get(2)).equals("A??????Z"),"Unicode, surrogate pair, MIDI terminator and controls handled as text");
        display.detailInfo("Groove - all clips","Amount 20%\nCaf\u00e9 drums\nChild \u2022 1");
        // Exercise every UTF-16 code unit, including all possible high MIDI status bytes.
        for(int start=0;start<=65535;start+=20) {
            StringBuilder text=new StringBuilder();
            for(int c=start;c<Math.min(65536,start+20);c++)text.append((char)c);
            display.sendString(0,OledDisplay.TextJustification.LEFT,2,text.toString());
        }
        for(byte[] packet:packets) {
            check((packet[0]&255)==0xf0&&(packet[packet.length-1]&255)==0xf7,"SysEx framing retained");
            for(int i=1;i<packet.length-1;i++)check((packet[i]&255)<128,"all SysEx data bytes are 7-bit");
            check(packet.length<=31&&packet[6]==packet.length-8,"bounded text and accurate length field");
        }
        int count=packets.size();
        display.sendString(-1,OledDisplay.TextJustification.LEFT,0,"invalid");
        display.sendString(0,OledDisplay.TextJustification.LEFT,-1,"invalid");
        check(packets.size()==count,"invalid placement/font cannot emit status bytes");
        System.out.println("OLED MIDI checks passed: reported separator, ASCII preservation, Unicode/control characters, lengths and 7-bit SysEx data.");
    }
    private static String payload(byte[] packet){return new String(packet,10,packet.length-11,StandardCharsets.US_ASCII);}
}
