package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

// Two copies of one sum: core's ModelBox.point (Vec3d) and 26.2's ModelContours.point (Vec3) give a box's point by the
// same operations in the same order, so the same bits on any box and any a, b, c (signed zeros, NaN, infinities and
// huge numbers among them; a NaN only as a NaN, its sign and payload are the CPU's, Vec3dTest)
class ModelBoxPointTest {
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

    private static void same(String what, double want, double got) {
        if (Double.isNaN(want) && Double.isNaN(got)) return;
        assertEquals(Double.doubleToRawLongBits(want), Double.doubleToRawLongBits(got), () -> what + ": " + want + " / " + got);
    }

    @Test
    void corePointIsContoursPoint() {
        SplittableRandom r = new SplittableRandom(20261119);
        for (int i = 0; i < 200_000; i++) {
            var box = new ModelBox("p", pick(r), pick(r), pick(r), pick(r), pick(r), pick(r), pick(r), pick(r), pick(r),
                    pick(r), pick(r), pick(r));
            double a = pick(r), b = pick(r), c = pick(r);
            var want = ModelContours.point(box, a, b, c);
            var got = box.point(a, b, c);
            String at = i + " " + box + " " + a + " " + b + " " + c;
            same("x " + at, want.x, got.x());
            same("y " + at, want.y, got.y());
            same("z " + at, want.z, got.z());
        }
    }
}
