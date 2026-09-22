package com.akai.fire.sequence;

public final class VelocityGrooveChecks {
    public static void main(String[] args) {
        for (int shape = 0; shape < 4; shape++) {
            boolean above = false, below = false;
            for (int step = 0; step < 16; step++) {
                double v = VelocityGroove.velocity(shape, 25, step, .5);
                above |= v > .5;
                below |= v < .5;
                equal(v, VelocityGroove.velocity(shape, 25, step + 16, .5));
                equal(.5, VelocityGroove.velocity(shape, 0, step, .5));
                for (double base : new double[]{1.0 / 127, .5, 1}) {
                    double full = VelocityGroove.velocity(shape, 100, step, base);
                    check(full >= 1.0 / 127 && full <= 1, "MIDI bounds");
                }
            }
            check(above && below, "Bipolar contour " + shape);
        }
        VelocityGroove groove = new VelocityGroove();
        groove.turnAmount(20);
        double first = groove.apply(0, 0, .5);
        groove.turnAmount(10);
        double second = groove.apply(0, 0, .5); // No host echo yet.
        groove.turnAmount(10);
        double third = groove.apply(0, 0, .5);
        equal(VelocityGroove.velocity(0, 40, 0, .5), third);
        equal(third, groove.apply(0, 0, first)); // Delayed intermediate echoes.
        equal(third, groove.apply(0, 0, second));
        equal(third, groove.apply(0, 0, third));
        groove.turnShape(1);
        double changed = groove.apply(0, 0, third);
        equal(VelocityGroove.velocity(1, 40, 0, .5), changed);
        groove.turnAmount(-100);
        equal(.5, groove.apply(0, 0, changed));
        groove.turnAmount(25);
        double manual = groove.apply(0, 0, .7); // Explicit manual edit becomes baseline.
        groove.turnAmount(-25);
        equal(.7, groove.apply(0, 0, manual));
        groove.turnAmount(100);
        int created = groove.newNote(7, 7, 93);
        groove.turnAmount(-100);
        equal(93.0 / 127, groove.apply(7, 7, created / 127.0));
        groove.remove(7);
        equal(.3, groove.apply(7, 7, .3));
        groove.turnShape(-2);
        check(groove.shape() == 3, "Reverse shape wraps");
        System.out.println("Velocity groove checks passed");
    }
    private static void equal(double expected, double actual) {
        check(Math.abs(expected - actual) < 1e-9, expected + " != " + actual);
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
