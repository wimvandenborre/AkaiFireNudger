package com.akai.fire.sequence;

import java.util.*;

record GrooveSettings(GrooveShapes.Shape shape, int base, double depth, double master,
                      int phase, int cycle, double quantize, double bias, double range, long seed,
                      boolean anchors, boolean offGrid, double captureWindow, double alignment,
                      boolean bypass, boolean resetEachLoop, GrooveVelocity velocity) {
    GrooveSettings {
        Objects.requireNonNull(shape); Objects.requireNonNull(velocity);
        if (!Set.of(8, 16, 32).contains(base)) throw new IllegalArgumentException("Base: 8/16/32");
        if (!Set.of(4, 8, 16, 32, 64).contains(cycle)) throw new IllegalArgumentException("Cycle: 4/8/16/32/64");
        bound(depth, 0, 1); bound(master, 0, 1); bound(quantize, 0, 1);
        bound(bias, -.25, .25); bound(range, 0, .49); bound(captureWindow, 0, .5);
        if (!Double.isFinite(alignment) || seed < 0 || seed > 0xffffffffL) throw new IllegalArgumentException("Invalid alignment/seed");
    }
    private static void bound(double x, double lo, double hi) {
        if (!Double.isFinite(x) || x < lo || x > hi) throw new IllegalArgumentException("Parameter out of range");
    }
    static GrooveSettings defaults() { return new Builder().build(); }
    /** The compact Fire Amount is the master for both layers; preferences remain unchanged. */
    static GrooveSettings forController(GrooveShapes.Shape shape, int amount, GrooveVelocity velocity) {
        if (amount < 0 || amount > 100) throw new IllegalArgumentException("Groove amount: 0..100");
        var b=defaults().edit();b.shape=shape;b.depth=amount/100.0;b.resetEachLoop=true;
        int scaled=(int)Math.round(Math.abs(velocity.amount())*amount/100.0)*Integer.signum(velocity.amount());
        b.velocity=new GrooveVelocity(velocity.enabled(),scaled,velocity.min(),velocity.max(),velocity.phase());
        return b.build();
    }
    double h() { return 4.0 / base; }
    double[] pattern() {
        double[] values = GrooveShapes.samples(shape, cycle, seed);
        if (shape.motion()) for (int i = 0; i < values.length; i++) values[i] *= range;
        return values;
    }
    Builder edit() { return new Builder(this); }
    static final class Builder {
        GrooveShapes.Shape shape = GrooveShapes.Shape.DEEP_SWING_57;
        int base = 16, phase, cycle = 16;
        double depth = 1, master = 1, quantize, bias, range = .10, captureWindow = .25, alignment;
        long seed;
        boolean anchors, offGrid, bypass, resetEachLoop;
        GrooveVelocity velocity = GrooveVelocity.defaults();
        Builder() {}
        Builder(GrooveSettings s) {
            shape=s.shape; base=s.base; phase=s.phase; cycle=s.cycle; depth=s.depth; master=s.master;
            quantize=s.quantize; bias=s.bias; range=s.range; seed=s.seed; anchors=s.anchors;
            offGrid=s.offGrid; captureWindow=s.captureWindow; alignment=s.alignment;
            bypass=s.bypass; resetEachLoop=s.resetEachLoop; velocity=s.velocity;
        }
        GrooveSettings build() { return new GrooveSettings(shape,base,depth,master,phase,cycle,quantize,bias,range,seed,
                anchors,offGrid,captureWindow,alignment,bypass,resetEachLoop,velocity); }
    }
}
