package acup.gunfeel;

/**
 * Minecraft's {@code Mth} for core's code: the methods core needs, each doing the operations of 26.2's {@code Mth} in
 * the same order, so its numbers come out bit for bit the same (GameMathTest on 26.2 holds it, against Mth itself).
 * Core's code that had {@code Mth.clamp(...)} calls {@code GameMath.clamp(...)} with nothing else changed. A method is
 * added here only with its line in GameMathTest, from Mth's bytecode ({@code javap -c -p net.minecraft.util.Mth}).
 */
public final class GameMath {
    private GameMath() {
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    public static int clamp(int v, int lo, int hi) {
        return Math.min(Math.max(v, lo), hi);
    }

    /** {@code start + delta·(end - start)}. */
    public static double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }

    public static float lerp(float delta, float start, float end) {
        return start + delta * (end - start);
    }

    public static int floor(double v) {
        return (int) Math.floor(v);
    }

    public static int floor(float v) {
        return (int) Math.floor(v);
    }

    public static long lfloor(double v) {
        return (long) Math.floor(v);
    }

    /** {@code v - lfloor(v)}. */
    public static double frac(double v) {
        return v - (double) lfloor(v);
    }

    /** 0 for ±0, 1 above, -1 below and for NaN. */
    public static int sign(double v) {
        if (v == 0.0) return 0;
        return v > 0.0 ? 1 : -1;
    }
}
