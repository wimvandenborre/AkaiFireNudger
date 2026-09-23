package com.akai.fire.sequence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Original mathematical templates; no hardware/factory groove claims. */
final class GrooveShapes {
    enum Shape {
        SOFT_SWING_54("Soft Swing 54"), DEEP_SWING_57("Deep Swing 57"),
        HOUSE_SWING_60("House Swing 60"), GARAGE_SWING_62("Garage Swing 62"),
        ASYMMETRIC_ROLL("Asymmetric Roll"), PUSH_PULL("Push-Pull"),
        LATE_OFFBEAT("Late Offbeat"), ANTICIPATION("Anticipation"),
        SINE("Sine"), TRIANGLE("Triangle"), RAMP_UP("Ramp Up"), RAMP_DOWN("Ramp Down"),
        ARCH("Arch"), SEEDED_RANDOM("Seeded Random"),
        LOGIC_16A("Logic 16A Straight"), LOGIC_16B("Logic 16B 54%"),
        LOGIC_16C("Logic 16C 58%"), LOGIC_16D("Logic 16D 62%"), LOGIC_16E("Logic 16E 66%");
        String id() { return name().toLowerCase(Locale.ROOT); }
        final String label;
        Shape(String label) { this.label = label; }
        boolean motion() { return ordinal() >= SINE.ordinal() && ordinal() <= SEEDED_RANDOM.ordinal(); }
    }
    private record Key(Shape shape, int cycle, long seed) {}
    // Bounded cache: encoder edits must not grow it indefinitely.
    private static final Map<Key, double[]> CACHE = new LinkedHashMap<>() {
        protected boolean removeEldestEntry(Map.Entry<Key, double[]> e) { return size() > 32; }
    };
    static double[] samples(Shape shape, int cycle, long seed) {
        Key key = new Key(shape, shape.motion() ? cycle : 0, shape == Shape.SEEDED_RANDOM ? seed : 0);
        return CACHE.computeIfAbsent(key, GrooveShapes::generate).clone();
    }
    static double[] swing(double ratio) { return new double[]{0, 2 * ratio - 1}; }
    private static double[] generate(Key key) {
        switch (key.shape) {
            case LOGIC_16A: return new double[]{0, 0};
            case LOGIC_16B: return new double[]{0, .08};
            case LOGIC_16C: return new double[]{0, .16};
            case LOGIC_16D: return new double[]{0, .24};
            case LOGIC_16E: return new double[]{0, .32};
            case SOFT_SWING_54: return swing(.54);
            case DEEP_SWING_57: return swing(.57);
            case HOUSE_SWING_60: return swing(.60);
            case GARAGE_SWING_62: return swing(.62);
            case ASYMMETRIC_ROLL: return new double[]{0, .10, -.03, .18};
            case PUSH_PULL: return new double[]{0, .08, -.04, .12, 0, .04, -.06, .16};
            case LATE_OFFBEAT: return new double[]{0, 0, .06, 0};
            case ANTICIPATION: return new double[]{0, 0, 0, -.06};
            default: break;
        }
        if (!Set.of(4, 8, 16, 32, 64).contains(key.cycle)) throw new IllegalArgumentException("Motion cycle: 4/8/16/32/64 slots");
        if (key.seed < 0 || key.seed > 0xffffffffL) throw new IllegalArgumentException("Seed must be uint32");
        double[] result = new double[key.cycle];
        for (int j = 0; j < result.length; j++) {
            double u = (double) j / result.length;
            result[j] = switch (key.shape) {
                case SINE -> Math.sin(2 * Math.PI * u);
                case TRIANGLE -> 2 / Math.PI * Math.asin(Math.sin(2 * Math.PI * u));
                case RAMP_UP -> 2 * u - 1;
                case RAMP_DOWN -> 1 - 2 * u;
                case ARCH -> j == 0 || j == result.length - 1 ? 0 : Math.pow(Math.sin(Math.PI * j / (result.length - 1)), 2);
                case SEEDED_RANDOM -> random(key.seed, j);
                default -> throw new IllegalArgumentException("Unknown motion");
            };
        }
        return result;
    }
    private static double random(long seed, int slot) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                    ("groove-shape-v1|" + seed + "|" + slot).getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 4; i++) value = (value << 8) | (bytes[i] & 255);
            return 2.0 * value / 4294967295L - 1;
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
