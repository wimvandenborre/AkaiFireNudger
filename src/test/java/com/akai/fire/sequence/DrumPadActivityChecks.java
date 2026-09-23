package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.PlayingNote;

public final class DrumPadActivityChecks {
    static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    static PlayingNote note(int pitch){return new PlayingNote(){public int pitch(){return pitch;}public int velocity(){return 100;}};}
    public static void main(String[] args) {
        DrumPadActivity activity=new DrumPadActivity();
        activity.child(0,true);activity.child(3,true);
        check(activity.playing(0,36)&&activity.playing(3,36)&&!activity.playing(1,36),"child MIDI lights correct top-row pads");
        activity.rack(new PlayingNote[0]);
        check(activity.playing(0,36),"empty group stream cannot extinguish child playback");
        activity.rack(new PlayingNote[]{note(36),note(38)});activity.child(0,false);
        check(activity.playing(0,36)&&activity.playing(2,36),"group/live audition still lights pads independently");
        activity.rack(new PlayingNote[0]);
        check(!activity.playing(0,36)&&activity.playing(3,36),"note offs clear only their own source");
        check(activity.playing(0,39)&&!activity.playing(3,52),"drum bank offset projects child lane to visible pad");
        activity.child(3,false);check(!activity.playing(3,36),"child note off clears light");
        activity.rack(new PlayingNote[]{note(-1),note(128)});check(!activity.playing(0,36),"out of range pitches ignored");
        System.out.println("Drum pad activity checks passed: child MIDI feedback, rack audition, source isolation, note offs and bank offsets.");
    }
}
