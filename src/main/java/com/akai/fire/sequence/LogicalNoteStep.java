package com.akai.fire.sequence;

import com.bitwig.extension.controller.api.*;

/** Logical Fire slot backed by the actual fine-cursor note; setters retain its timing. */
final class LogicalNoteStep implements NoteStep {
    final NoteStep source;
    private final int slot;
    LogicalNoteStep(NoteStep source, int slot) { this.source = source; this.slot = slot; }
    @Override public int x() { return slot; }
    @Override public int y() { return source.y(); }
    @Override public int channel() { return source.channel(); }
    @Override public NoteStep.State state() { return source.state(); }
    @Override public double velocity() { return source.velocity(); }
    @Override public void setVelocity(double arg0) { source.setVelocity(arg0); }
    @Override public double releaseVelocity() { return source.releaseVelocity(); }
    @Override public void setReleaseVelocity(double arg0) { source.setReleaseVelocity(arg0); }
    @Override public double velocitySpread() { return source.velocitySpread(); }
    @Override public void setVelocitySpread(double arg0) { source.setVelocitySpread(arg0); }
    @Override public double duration() { return source.duration(); }
    @Override public void setDuration(double arg0) { source.setDuration(arg0); }
    @Override public double pan() { return source.pan(); }
    @Override public void setPan(double arg0) { source.setPan(arg0); }
    @Override public double timbre() { return source.timbre(); }
    @Override public void setTimbre(double arg0) { source.setTimbre(arg0); }
    @Override public double pressure() { return source.pressure(); }
    @Override public void setPressure(double arg0) { source.setPressure(arg0); }
    @Override public double gain() { return source.gain(); }
    @Override public void setGain(double arg0) { source.setGain(arg0); }
    @Override public double transpose() { return source.transpose(); }
    @Override public void setTranspose(double arg0) { source.setTranspose(arg0); }
    @Override public boolean isIsSelected() { return source.isIsSelected(); }
    @Override public double chance() { return source.chance(); }
    @Override public void setChance(double arg0) { source.setChance(arg0); }
    @Override public boolean isChanceEnabled() { return source.isChanceEnabled(); }
    @Override public void setIsChanceEnabled(boolean arg0) { source.setIsChanceEnabled(arg0); }
    @Override public boolean isOccurrenceEnabled() { return source.isOccurrenceEnabled(); }
    @Override public void setIsOccurrenceEnabled(boolean arg0) { source.setIsOccurrenceEnabled(arg0); }
    @Override public NoteOccurrence occurrence() { return source.occurrence(); }
    @Override public void setOccurrence(NoteOccurrence arg0) { source.setOccurrence(arg0); }
    @Override public boolean isRecurrenceEnabled() { return source.isRecurrenceEnabled(); }
    @Override public void setIsRecurrenceEnabled(boolean arg0) { source.setIsRecurrenceEnabled(arg0); }
    @Override public int recurrenceLength() { return source.recurrenceLength(); }
    @Override public int recurrenceMask() { return source.recurrenceMask(); }
    @Override public void setRecurrence(int arg0, int arg1) { source.setRecurrence(arg0, arg1); }
    @Override public boolean isRepeatEnabled() { return source.isRepeatEnabled(); }
    @Override public void setIsRepeatEnabled(boolean arg0) { source.setIsRepeatEnabled(arg0); }
    @Override public int repeatCount() { return source.repeatCount(); }
    @Override public void setRepeatCount(int arg0) { source.setRepeatCount(arg0); }
    @Override public double repeatCurve() { return source.repeatCurve(); }
    @Override public void setRepeatCurve(double arg0) { source.setRepeatCurve(arg0); }
    @Override public double repeatVelocityEnd() { return source.repeatVelocityEnd(); }
    @Override public void setRepeatVelocityEnd(double arg0) { source.setRepeatVelocityEnd(arg0); }
    @Override public double repeatVelocityCurve() { return source.repeatVelocityCurve(); }
    @Override public void setRepeatVelocityCurve(double arg0) { source.setRepeatVelocityCurve(arg0); }
    @Override public boolean isMuted() { return source.isMuted(); }
    @Override public void setIsMuted(boolean arg0) { source.setIsMuted(arg0); }
}
