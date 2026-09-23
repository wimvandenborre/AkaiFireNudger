package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.PlayingNote;
import java.util.Arrays;

/** Rack input and direct-child MIDI activity are independent sources for the same pad lights. */
final class DrumPadActivity {
    private final boolean[] pitches=new boolean[128];
    private final boolean[] children=new boolean[16];
    void rack(PlayingNote[] notes) {
        Arrays.fill(pitches,false);
        for(PlayingNote note:notes)if(note.pitch()>=0&&note.pitch()<128)pitches[note.pitch()]=true;
    }
    void child(int lane,boolean playing){if(lane>=0&&lane<16)children[lane]=playing;}
    boolean playing(int pad,int offset) {
        int pitch=offset+pad,lane=pitch-MulticlipTarget.FIRST_NOTE;
        return pitch>=0&&pitch<128&&(pitches[pitch]||(lane>=0&&lane<16&&children[lane]));
    }
}
