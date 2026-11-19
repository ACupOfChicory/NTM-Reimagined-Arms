package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.SplittableRandom;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

// core's Vec3d does Vec3's arithmetic: on the same doubles each method gives the same bits (signed zeros, NaN, the
// normalize floor and huge values among them)
class Vec3dTest {
    private static final double[] ODD = {0.0, -0.0, 1e-5, 9.99999e-6, 1.0E-5F, -1e-300, 1e300, Double.MIN_VALUE, Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.1, -0.5, 3.0, 1e-7};

    private static double pick(SplittableRandom r) {
        return switch (r.nextInt(4)) {
            case 0 -> ODD[r.nextInt(ODD.length)];
            case 1 -> r.nextDouble(-2, 2);
            case 2 -> r.nextDouble(-3e7, 3e7);
            default -> (r.nextDouble() - 0.5) * 1e-5;
        };
    }

    private static void same(String what, Vec3 want, Vec3d got) {
        same(what + ".x", want.x, got.x());
        same(what + ".y", want.y, got.y());
        same(what + ".z", want.z, got.z());
    }

    private static void same(String what, double want, double got) {
        assertEquals(Double.doubleToRawLongBits(want), Double.doubleToRawLongBits(got), () -> what + ": " + want + " / " + got);
    }

    @Test
    void everyMethodGivesVec3sBits() {
        SplittableRandom r = new SplittableRandom(20261119);
        for (int i = 0; i < 200_000; i++) {
            double ax = pick(r), ay = pick(r), az = pick(r), bx = pick(r), by = pick(r), bz = pick(r), f = pick(r);
            Vec3 a = new Vec3(ax, ay, az), b = new Vec3(bx, by, bz);
            Vec3d c = new Vec3d(ax, ay, az), d = new Vec3d(bx, by, bz);
            String at = i + " " + a + " " + b + " " + f;
            same("add " + at, a.add(b), c.add(d));
            same("add3 " + at, a.add(bx, by, bz), c.add(bx, by, bz));
            same("subtract " + at, a.subtract(b), c.subtract(d));
            same("subtract3 " + at, a.subtract(bx, by, bz), c.subtract(bx, by, bz));
            same("vectorTo " + at, a.vectorTo(b), c.vectorTo(d));
            same("multiply " + at, a.multiply(b), c.multiply(d));
            same("scale " + at, a.scale(f), c.scale(f));
            same("reverse " + at, a.reverse(), c.reverse());
            same("normalize " + at, a.normalize(), c.normalize());
            same("cross " + at, a.cross(b), c.cross(d));
            same("lerp " + at, a.lerp(b, f), c.lerp(d, f));
            same("projectedOn " + at, a.projectedOn(b), c.projectedOn(d));
            same("dot " + at, a.dot(b), c.dot(d));
            same("length " + at, a.length(), c.length());
            same("lengthSqr " + at, a.lengthSqr(), c.lengthSqr());
            same("horizontalDistance " + at, a.horizontalDistance(), c.horizontalDistance());
            same("horizontalDistanceSqr " + at, a.horizontalDistanceSqr(), c.horizontalDistanceSqr());
            same("distanceTo " + at, a.distanceTo(b), c.distanceTo(d));
            same("distanceToSqr " + at, a.distanceToSqr(b), c.distanceToSqr(d));
            same("distanceToSqr3 " + at, a.distanceToSqr(bx, by, bz), c.distanceToSqr(bx, by, bz));
        }
    }

    @Test
    void theVersionsVectorGoesThereAndBack() {
        Vec3 v = new Vec3(-0.0, 1e-300, Double.NaN);
        same("of", v, Vecs.of(v));
        same("mc.x", v.x, Vecs.mc(Vecs.of(v)).x);
        same("mc.z", v.z, Vecs.mc(Vecs.of(v)).z);
        assertEquals(Vec3.ZERO, Vecs.mc(Vec3d.ZERO));
    }
}
