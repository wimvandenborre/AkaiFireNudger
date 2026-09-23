package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.NoteStep;
import com.bitwig.extension.controller.api.NoteOccurrence;

/** Captured before changing a clip cursor, whose NoteStep proxies are live. */
record NoteSnapshot(
        int x,
        int channel,
        double velocity,
        double duration,
        double releaseVelocity,
        double velocitySpread,
        double pan,
        double timbre,
        double pressure,
        double gain,
        double transpose,
        double chance,
        boolean isChanceEnabled,
        NoteOccurrence occurrence,
        boolean isOccurrenceEnabled,
        int recurrenceLength,
        int recurrenceMask,
        boolean isRecurrenceEnabled,
        int repeatCount,
        double repeatCurve,
        double repeatVelocityEnd,
        double repeatVelocityCurve,
        boolean isRepeatEnabled,
        boolean isMuted) {
    static NoteSnapshot capture(NoteStep note) {
        return new NoteSnapshot(note.x(), note.channel(), note.velocity(), note.duration(), note.releaseVelocity(), note.velocitySpread(), note.pan(), note.timbre(), note.pressure(), note.gain(), note.transpose(), note.chance(), note.isChanceEnabled(), note.occurrence(), note.isOccurrenceEnabled(), note.recurrenceLength(), note.recurrenceMask(), note.isRecurrenceEnabled(), note.repeatCount(), note.repeatCurve(), note.repeatVelocityEnd(), note.repeatVelocityCurve(), note.isRepeatEnabled(), note.isMuted());
    }

    void applyTo(NoteStep note) {
        note.setVelocity(velocity);
        note.setDuration(duration);
        note.setReleaseVelocity(releaseVelocity);
        note.setVelocitySpread(velocitySpread);
        note.setPan(pan);
        note.setTimbre(timbre);
        note.setPressure(pressure);
        note.setGain(gain);
        note.setTranspose(transpose);
        note.setChance(chance);
        note.setIsChanceEnabled(isChanceEnabled);
        note.setOccurrence(occurrence);
        note.setIsOccurrenceEnabled(isOccurrenceEnabled);
        note.setIsRecurrenceEnabled(isRecurrenceEnabled);
        note.setRepeatCount(repeatCount);
        note.setRepeatCurve(repeatCurve);
        note.setRepeatVelocityEnd(repeatVelocityEnd);
        note.setRepeatVelocityCurve(repeatVelocityCurve);
        note.setIsRepeatEnabled(isRepeatEnabled);
        note.setIsMuted(isMuted);
        note.setRecurrence(recurrenceLength, recurrenceMask);
    }
}
