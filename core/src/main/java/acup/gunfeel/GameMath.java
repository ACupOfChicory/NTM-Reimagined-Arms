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

    /** Mth.DEG_TO_RAD */
    public static final float DEG_TO_RAD = 0.017453292F;

    /** Mth's sine table: 65536 steps round the circle, each Math.sin of its angle as a float */
    private static final float[] SIN = new float[65536];

    static {
        for (int i = 0; i < SIN.length; i++) SIN[i] = (float) Math.sin(i / 10430.378350470453);
    }

    /** Mth.sin: the table's step at or below v (not Math.sin) */
    public static float sin(double v) {
        return SIN[(int) ((long) (v * 10430.378350470453) & 65535L)];
    }

    /** Mth.cos: the table's step a quarter round on */
    public static float cos(double v) {
        return SIN[(int) ((long) (v * 10430.378350470453 + 16384.0) & 65535L)];
    }

    /**
     * Vec3.directionFromRotation(xRot, yRot): the way a body looks at these rotations (degrees), by the same float
     * operations on the same table; out x, y, z
     */
    public static void direction(float xRot, float yRot, double[] out) {
        float f = cos(-yRot * 0.017453292F - 3.1415927F);
        float f1 = sin(-yRot * 0.017453292F - 3.1415927F);
        float f2 = -cos(-xRot * 0.017453292F);
        float f3 = sin(-xRot * 0.017453292F);
        out[0] = f1 * f2;
        out[1] = f3;
        out[2] = f * f2;
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
