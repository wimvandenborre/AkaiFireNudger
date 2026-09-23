package com.akai.fire.sequence;

public final class StepHoldGestureChecks {
    public static void main(String[] args) {
        long[] now = {0};
        StepHoldGesture gesture = new StepHoldGesture(() -> now[0]);
        gesture.press(0);
        now[0] = 100;
        check(gesture.releaseIsTap(0), "short tap toggles");
        gesture.press(0);
        now[0] = 500;
        check(!gesture.releaseIsTap(0), "holding an existing note must not delete it");
        gesture.press(1);
        now[0] = 600;
        gesture.press(2);
        now[0] = 800;
        check(!gesture.releaseIsTap(1), "first held note survives");
        check(gesture.releaseIsTap(2), "independent short tap");
        gesture.press(0);
        gesture.clear();
        check(!gesture.releaseIsTap(0), "release after context switch cannot delete");
        check(!gesture.releaseIsTap(0), "duplicate release cannot delete");
        System.out.println("Step hold checks passed: tap toggle, long hold preservation, independent pads and cancelled releases.");
    }
    static void check(boolean ok, String text) { if (!ok) throw new AssertionError(text); }
}
