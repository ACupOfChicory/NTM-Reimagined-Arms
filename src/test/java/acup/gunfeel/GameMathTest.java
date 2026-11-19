package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.SplittableRandom;

import net.minecraft.util.Mth;

import org.junit.jupiter.api.Test;

// core's GameMath does Mth's arithmetic: on the same numbers each method gives the same bits (signed zeros, NaN,
// infinities, huge values and the ends of a clamp among them; a NaN where Mth gives a NaN)
class GameMathTest {
    private static final double[] ODD = {0.0, -0.0, 1.0, -1.0, 0.5, -0.5, 1e-7, -1e-300, 1e300, Double.MIN_VALUE, Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 2147483647.5, -2147483648.5, 9.3e18, -9.3e18, 0.9999999999999999};

    private static double pick(SplittableRandom r) {
        return switch (r.nextInt(4)) {
            case 0 -> ODD[r.nextInt(ODD.length)];
            case 1 -> r.nextDouble(-2, 2);
            case 2 -> r.nextDouble(-3e7, 3e7);
            default -> (r.nextDouble() - 0.5) * 1e-5;
        };
    }

    // the same bits, ±0 apart; a NaN only as a NaN (its sign and payload are the CPU's, Vec3dTest)
    private static void same(String what, double want, double got) {
        if (Double.isNaN(want) && Double.isNaN(got)) return;
        assertEquals(Double.doubleToRawLongBits(want), Double.doubleToRawLongBits(got), () -> what + ": " + want + " / " + got);
    }

    private static void same(String what, float want, float got) {
        if (Float.isNaN(want) && Float.isNaN(got)) return;
        assertEquals(Float.floatToRawIntBits(want), Float.floatToRawIntBits(got), () -> what + ": " + want + " / " + got);
    }

    @Test
    void everyMethodGivesMthsBits() {
        SplittableRandom r = new SplittableRandom(20261119);
        for (int i = 0; i < 200_000; i++) {
            double a = pick(r), b = pick(r), c = pick(r);
            float fa = (float) a, fb = (float) b, fc = (float) c;
            int ia = (int) (a * 1e3), ib = (int) (b * 1e3), ic = (int) (c * 1e3);
            if (r.nextInt(8) == 0) ia = r.nextInt();
            String at = i + " " + a + " " + b + " " + c;
            same("clampF " + at, Mth.clamp(fa, fb, fc), GameMath.clamp(fa, fb, fc));
            same("clampD " + at, Mth.clamp(a, b, c), GameMath.clamp(a, b, c));
            assertEquals(Mth.clamp(ia, ib, ic), GameMath.clamp(ia, ib, ic), () -> "clampI " + at);
            same("lerpD " + at, Mth.lerp(a, b, c), GameMath.lerp(a, b, c));
            same("lerpF " + at, Mth.lerp(fa, fb, fc), GameMath.lerp(fa, fb, fc));
            assertEquals(Mth.floor(a), GameMath.floor(a), () -> "floorD " + at);
            assertEquals(Mth.floor(fa), GameMath.floor(fa), () -> "floorF " + at);
            assertEquals(Mth.lfloor(a), GameMath.lfloor(a), () -> "lfloor " + at);
            same("frac " + at, Mth.frac(a), GameMath.frac(a));
            assertEquals(Mth.sign(a), GameMath.sign(a), () -> "sign " + at);
        }
    }

    // sin and cos by Mth's table: every step of it, numbers round it and far off, and the angle AiDash asks for
    @Test
    void theSineTable() {
        assertEquals(Mth.DEG_TO_RAD, GameMath.DEG_TO_RAD);
        for (int i = 0; i < 65536; i++) {
            double v = i / 10430.378350470453;
            same("sin " + i, Mth.sin(v), GameMath.sin(v));
            same("cos " + i, Mth.cos(v), GameMath.cos(v));
        }
        SplittableRandom r = new SplittableRandom(20261120);
        for (int i = 0; i < 200_000; i++) {
            double v = pick(r);
            same("sin " + v, Mth.sin(v), GameMath.sin(v));
            same("cos " + v, Mth.cos(v), GameMath.cos(v));
            float f = (float) v;
            same("sinF " + f, Mth.sin(f), GameMath.sin(f));
            same("cosF " + f, Mth.cos(f), GameMath.cos(f));
        }
        same("cos 30°", Mth.cos(30F * Mth.DEG_TO_RAD), GameMath.cos(30F * GameMath.DEG_TO_RAD));
    }

    // the ends of a clamp the random numbers miss: a value on an end, ±0 against ±0, an empty range (lo > hi)
    @Test
    void theClampsEnds() {
        float[] fs = {0F, -0F, 1F, -1F, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (float v : fs) for (float lo : fs) for (float hi : fs)
            same("clampF " + v + " " + lo + " " + hi, Mth.clamp(v, lo, hi), GameMath.clamp(v, lo, hi));
        double[] ds = {0.0, -0.0, 1.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double v : ds) for (double lo : ds) for (double hi : ds)
            same("clampD " + v + " " + lo + " " + hi, Mth.clamp(v, lo, hi), GameMath.clamp(v, lo, hi));
    }

    // the way a body looks: Vec3.directionFromRotation's bits, on rotations a mob turns to and odd floats
    @Test
    void directionIsVec3s() {
        float[] odd = {0F, -0F, 90F, -90F, 180F, -180F, 360F, 1e-7F, -1e-30F, 3e7F, Float.MIN_VALUE, Float.NaN,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 89.99999F, 45F, 1e30F};
        SplittableRandom r = new SplittableRandom(20261120);
        double[] out = new double[3];
        for (int i = 0; i < 400_000; i++) {
            float x = r.nextInt(5) == 0 ? odd[r.nextInt(odd.length)] : (float) r.nextDouble(-90, 90);
            float y = r.nextInt(5) == 0 ? odd[r.nextInt(odd.length)] : r.nextBoolean() ? (float) r.nextDouble(-180, 180) : (float) r.nextDouble(-1e6, 1e6);
            net.minecraft.world.phys.Vec3 want = net.minecraft.world.phys.Vec3.directionFromRotation(x, y);
            GameMath.direction(x, y, out);
            String at = x + " " + y;
            same("x " + at, want.x, out[0]);
            same("y " + at, want.y, out[1]);
            same("z " + at, want.z, out[2]);
        }
    }
}
