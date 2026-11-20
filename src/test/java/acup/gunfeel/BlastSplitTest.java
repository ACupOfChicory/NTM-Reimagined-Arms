package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.DoubleUnaryOperator;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// core's BlastPartsBase against c2fd8bf's BlastParts word for word with the game's types: split's sum over the parts
// (the same answer, the multiplier found or not, the stand's counted parts, and the same calls of the stand-ins for
// NTM's falloff, the water's scale and push and the rays, in the same order), surface, nearest, inside
class BlastSplitTest {
    record Part(ModelBox box, float multiplier, double share) {
    }

    record Counted(String part, float multiplier, double share, double scaled, double seen, float raw) {
    }

    // BlastParts.nearest at c2fd8bf
    static Vec3 oldNearest(ModelBox b, Vec3 p) {
        double[][] e = { { b.ux(), b.uy(), b.uz() }, { b.vx(), b.vy(), b.vz() }, { b.wx(), b.wy(), b.wz() } };
        double x = b.x(), y = b.y(), z = b.z();
        double dx = p.x - b.x(), dy = p.y - b.y(), dz = p.z - b.z();
        for (double[] a : e) {
            double len2 = a[0] * a[0] + a[1] * a[1] + a[2] * a[2];
            if (len2 < 1e-12) continue;
            double t = Math.clamp((dx * a[0] + dy * a[1] + dz * a[2]) / len2, 0, 1);
            x += a[0] * t;
            y += a[1] * t;
            z += a[2] * t;
        }
        return new Vec3(x, y, z);
    }

    // BlastParts.surface at c2fd8bf
    static double oldSurface(ModelBox b) {
        Vec3 u = new Vec3(b.ux(), b.uy(), b.uz()), v = new Vec3(b.vx(), b.vy(), b.vz()), w = new Vec3(b.wx(), b.wy(), b.wz());
        return 2 * (u.cross(v).length() + v.cross(w).length() + w.cross(u).length());
    }

    // BlastParts.inside at c2fd8bf
    static boolean oldInside(ModelBox b, AABB near) {
        for (int i = 0; i < 8; i++) {
            double x = b.x() + (i & 1) * b.ux() + ((i >> 1) & 1) * b.vx() + ((i >> 2) & 1) * b.wx();
            double y = b.y() + (i & 1) * b.uy() + ((i >> 1) & 1) * b.vy() + ((i >> 2) & 1) * b.wy();
            double z = b.z() + (i & 1) * b.uz() + ((i >> 1) & 1) * b.vz() + ((i >> 2) & 1) * b.wz();
            if (!(x >= near.minX && x <= near.maxX && y >= near.minY && y <= near.maxY && z >= near.minZ && z <= near.maxZ)) return false;
        }
        return true;
    }

    /** the stand-ins' calls, in order */
    static final class Log {
        final List<String> calls = new ArrayList<>();
        final SplittableRandom r;

        Log(long seed) {
            r = new SplittableRandom(seed);
        }

        double odd(double v) {
            int k = r.nextInt(30);
            return k == 0 ? Double.NaN : k == 1 ? Double.POSITIVE_INFINITY : k == 2 ? -v : v;
        }
    }

    // BlastParts.split at c2fd8bf from its loop on (`even` its key's answer, `seen` the rays' stand-in); its answer, the
    // multiplier it put (NaN: none) and the counted parts
    static float oldSplit(List<Part> parts, Vec3 centre, float size, float whole, double density, BlastParts.Damage calc, DoubleUnaryOperator scale,
            DoubleUnaryOperator push, java.util.function.ToDoubleFunction<ModelBox> seenOf, boolean even, List<Counted> counted, float[] put) {
        double total = 0, weighted = 0;
        boolean reached = false, limbs = false;
        double mean = 0;
        for (Part p : parts) {
            limbs |= p.multiplier() < 1;
            mean += p.share() * p.multiplier();
            double scaled = scale.applyAsDouble(oldNearest(p.box(), centre).distanceTo(centre) / size);
            reached |= scaled <= 1;
            if (!(scaled <= 1)) {
                if (counted != null) counted.add(new Counted(p.box().part(), p.multiplier(), p.share(), scaled, 0, 0));
                continue;
            }
            double seen = density >= 1 ? 1 : seenOf.applyAsDouble(p.box());
            float raw = Math.max(0F, calc.of(scaled, seen, push.applyAsDouble((1 - scaled) * seen)));
            total += p.share() * raw;
            weighted += p.share() * raw * p.multiplier();
            if (counted != null) counted.add(new Counted(p.box().part(), p.multiplier(), p.share(), scaled, seen, raw));
        }
        if (!reached || !Double.isFinite(total) || !Double.isFinite(weighted)) return whole;
        float m = total > 0 ? (float) (weighted / total) : 1F;
        if (even && mean > 0) m = (float) (m / mean);
        else if (!limbs) m = Math.min(m, 1F);
        put[0] = m;
        return (float) total;
    }

    // a box, now and then a flat one (an edge of no length: nearest skips it)
    static ModelBox box(SplittableRandom r, String name) {
        double s = r.nextDouble(0.05, 1);
        double[] e = new double[9];
        for (int i = 0; i < 9; i++) e[i] = r.nextDouble(-s, s);
        if (r.nextInt(10) == 0) for (int i = 6; i < 9; i++) e[i] = 0;
        return new ModelBox(name, r.nextDouble(-3, 3), r.nextDouble(-1, 3), r.nextDouble(-3, 3), e[0], e[1], e[2], e[3], e[4], e[5], e[6], e[7], e[8]);
    }

    @Test
    void splitTheSame() {
        boolean even = BlastParts.even();
        SplittableRandom r = new SplittableRandom(1926);
        int found = 0, wholeAnswers = 0, n = 200_000;
        long calls = 0;
        float[] mults = {1.75F, 1F, 0.5F, 0.75F, 2F};
        for (int i = 0; i < n; i++) {
            int k = r.nextInt(12);
            List<Part> old = new ArrayList<>(k);
            List<BlastPartsBase.Part> now = new ArrayList<>(k);
            for (int j = 0; j < k; j++) {
                ModelBox b = box(r, "p" + j);
                // now and then a multiplier past any float sum (weighted not finite, total finite)
                float m = r.nextInt(200) == 0 ? Float.POSITIVE_INFINITY : mults[r.nextInt(mults.length)];
                double share = r.nextInt(20) == 0 ? 0 : r.nextDouble();
                old.add(new Part(b, m, share));
                now.add(new BlastPartsBase.Part(b, m, share));
            }
            Vec3 centre = new Vec3(r.nextDouble(-4, 4), r.nextDouble(-2, 4), r.nextDouble(-4, 4));
            float size = r.nextInt(30) == 0 ? (float) r.nextDouble(0.01, 0.2) : (float) r.nextDouble(1, 8);
            float whole = (float) r.nextDouble(0, 40);
            double density = new double[] {0, 0.5, 1, 1.5, r.nextDouble()}[r.nextInt(5)];
            long seed = r.nextLong();
            // the stand-ins: their answers by a seeded generator, so both sides get the same if called the same
            Log a = new Log(seed), b = new Log(seed);
            float[] put = {Float.NaN};
            List<Counted> ca = new ArrayList<>();
            float x = oldSplit(old, centre, size, whole, density, (s, d, kb) -> { a.calls.add("calc " + s + " " + d + " " + kb); return (float) a.odd(40 * (1 - s) * d); },
                    v -> { a.calls.add("scale " + v); return a.r.nextInt(8) == 0 ? 1.0 : a.odd(v * 1.1); }, v -> { a.calls.add("push " + v); return a.odd(v); },
                    box -> { a.calls.add("seen " + box.part()); return a.odd(a.r.nextDouble()); }, even, ca, put);
            float[] m = {Float.NaN, 0F};
            List<BlastPartsBase.Counted> cb = new ArrayList<>();
            float y = BlastPartsBase.split(now, new Vec3d(centre.x, centre.y, centre.z), size, whole, density,
                    (s, d, kb) -> { b.calls.add("calc " + s + " " + d + " " + kb); return (float) b.odd(40 * (1 - s) * d); },
                    v -> { b.calls.add("scale " + v); return b.r.nextInt(8) == 0 ? 1.0 : b.odd(v * 1.1); }, v -> { b.calls.add("push " + v); return b.odd(v); },
                    box -> { b.calls.add("seen " + box.part()); return b.odd(b.r.nextDouble()); }, cb, m);
            assertEquals(Float.floatToRawIntBits(x), Float.floatToRawIntBits(y), "answer " + i);
            assertEquals(a.calls, b.calls, "calls " + i);
            calls += a.calls.size();
            // found: the old one put a number there (never NaN: total and weighted finite, total > 0 or 1)
            boolean oldFound = !Float.isNaN(put[0]);
            assertEquals(oldFound, m[1] != 0F, "found " + i);
            if (oldFound) {
                found++;
                assertEquals(Float.floatToRawIntBits(put[0]), Float.floatToRawIntBits(m[0]), "multiplier " + i);
            } else wholeAnswers++;
            assertEquals(ca.size(), cb.size());
            for (int j = 0; j < ca.size(); j++) {
                Counted o = ca.get(j);
                BlastPartsBase.Counted c = cb.get(j);
                assertEquals(o, new Counted(c.part(), c.multiplier(), c.share(), c.scaled(), c.seen(), c.raw()), "counted " + i + " " + j);
            }
        }
        assertTrue(found > n / 10 && wholeAnswers > n / 10, found + " " + wholeAnswers);
        assertTrue(calls > n, "calls " + calls);
    }

    @Test
    void boxesTheSame() {
        SplittableRandom r = new SplittableRandom(1927);
        double[] odd = {Double.NaN, Double.POSITIVE_INFINITY, -0.0, 1e-7, 1e300};
        int in = 0;
        for (int i = 0; i < 1_000_000; i++) {
            ModelBox b = box(r, "b");
            if (r.nextInt(50) == 0) b = new ModelBox("b", odd[r.nextInt(odd.length)], b.y(), b.z(), b.ux(), b.uy(), odd[r.nextInt(odd.length)],
                    b.vx(), b.vy(), b.vz(), b.wx(), b.wy(), b.wz());
            Vec3 p = new Vec3(r.nextDouble(-5, 5), r.nextDouble(-5, 5), r.nextDouble(-5, 5));
            Vec3 o = oldNearest(b, p), nw = BlastParts.nearest(b, p);
            assertEquals(Double.doubleToRawLongBits(o.x), Double.doubleToRawLongBits(nw.x));
            assertEquals(Double.doubleToRawLongBits(o.y), Double.doubleToRawLongBits(nw.y));
            assertEquals(Double.doubleToRawLongBits(o.z), Double.doubleToRawLongBits(nw.z));
            assertEquals(Double.doubleToRawLongBits(oldSurface(b)), Double.doubleToRawLongBits(BlastParts.surface(b)));
            double rr = r.nextDouble(0.5, 4);
            AABB near = new AABB(-rr, -1.5, -rr, rr, r.nextDouble(1, 4), rr);
            boolean want = oldInside(b, near);
            assertEquals(want, BlastParts.inside(b, near));
            if (want) in++;
        }
        assertTrue(in > 10_000, "in " + in);
    }
}
