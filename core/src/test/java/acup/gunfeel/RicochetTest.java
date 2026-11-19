package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

// Ricochet against our code as it was (cdad232), word for word with the game's made plain numbers: the server's stop
// instead of NTM's ricochet (MixinBulletServer.gunfeel$noRicochet: its returns as false, the stop as true), the
// tracer's bounce (Tracers.ricochet), the steel ricochet sound (BulletServer.ricochetSound). Every face, velocities of
// every size (none, below 1e-9, huge, infinite, not a number), angles about the limit, the rules either way.
class RicochetTest {
    static final int[][] FACES = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    // the server's, from its rule's answer on (air: returned before)
    static boolean oldServer(boolean rule, int[] f, double vx, double vy, double vz, float max) {
        if (rule) return false;
        double l = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (l < 1e-9) return false;
        double cos = (f[0] * vx + f[1] * vy + f[2] * vz) / l;
        if (Math.abs(Math.toDegrees(Math.acos(Math.clamp(cos, -1.0, 1.0))) - 90.0) > max) return false;
        return true;
    }

    // the tracer's, from air on (breaks: HAT or glowstone)
    static boolean oldClient(boolean breaks, boolean rule, int[] f, double vx, double vy, double vz, float max) {
        if (breaks || !rule) return false;
        double l = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (l < 1e-9) return false;
        double cos = (f[0] * vx + f[1] * vy + f[2] * vz) / l;
        double angle = Math.abs(Math.toDegrees(Math.acos(Math.clamp(cos, -1.0, 1.0))) - 90.0);
        return angle <= max;
    }

    static boolean oldSteel(String id) {
        return id != null && (id.startsWith("ntmgunfeel:bullet.hit.steel") || id.startsWith("ntmgunfeel:bullet.hit.metal"));
    }

    @Test
    void theSameAsBefore() {
        SplittableRandom r = new SplittableRandom(1121L);
        double[] odd = {0, -0.0, 1e-10, -1e-10, 1e-9, 5e-10, 1e300, -1e300, Double.MAX_VALUE, Double.MIN_VALUE, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NaN};
        float[] maxes = {0F, 5F, 25F, 45F, 90F, 180F, -1F, Float.NaN};
        int stops = 0, bounces = 0;
        for (int i = 0; i < 1_000_000; i++) {
            int[] f = FACES[r.nextInt(6)];
            double vx, vy, vz;
            if (r.nextInt(20) == 0) {
                vx = odd[r.nextInt(odd.length)];
                vy = odd[r.nextInt(odd.length)];
                vz = odd[r.nextInt(odd.length)];
            } else if (r.nextInt(3) == 0) {
                // about the limit: a set angle off the face
                double a = Math.toRadians(r.nextDouble(-40, 40)), b = r.nextDouble(0, 2 * Math.PI), s = r.nextDouble(0.01, 5);
                double n = Math.sin(a), t = Math.cos(a);
                double[] u = {t * Math.cos(b), t * Math.sin(b)};
                int ax = f[0] != 0 ? 0 : f[1] != 0 ? 1 : 2;
                double[] v = new double[3];
                v[ax] = n * s * (r.nextBoolean() ? 1 : -1);
                v[(ax + 1) % 3] = u[0] * s;
                v[(ax + 2) % 3] = u[1] * s;
                vx = v[0];
                vy = v[1];
                vz = v[2];
            } else {
                vx = r.nextDouble(-3, 3);
                vy = r.nextDouble(-3, 3);
                vz = r.nextDouble(-3, 3);
            }
            float max = r.nextInt(4) == 0 ? maxes[r.nextInt(maxes.length)] : (float) r.nextDouble(0, 60);
            boolean rule = r.nextBoolean(), breaks = r.nextInt(6) == 0;
            boolean os = oldServer(rule, f, vx, vy, vz, max), oc = oldClient(breaks, rule, f, vx, vy, vz, max);
            assertEquals(os, Ricochet.stopsInstead(rule, f[0], f[1], f[2], vx, vy, vz, max), vx + " " + vy + " " + vz + " " + max);
            assertEquals(oc, Ricochet.bounces(breaks, rule, f[0], f[1], f[2], vx, vy, vz, max), vx + " " + vy + " " + vz + " " + max);
            if (os) stops++;
            if (oc) bounces++;
        }
        assertTrue(stops > 50_000 && bounces > 50_000, stops + " stops, " + bounces + " bounces");
        // a velocity that is not a number: the server stops it, the tracer does not bounce (as each asked)
        assertEquals(true, Ricochet.stopsInstead(false, 0, 1, 0, Double.NaN, 0, 0, 25F));
        assertEquals(false, Ricochet.bounces(false, true, 0, 1, 0, Double.NaN, 0, 0, 25F));
        for (String id : new String[] {null, "", "ntmgunfeel:bullet.hit.steel", "ntmgunfeel:bullet.hit.steel_thin", "ntmgunfeel:bullet.hit.metal",
                "ntmgunfeel:bullet.hit.metalx", "ntmgunfeel:bullet.hit.metal_sheet", "ntmgunfeel:bullet.hit.meta", "ntmgunfeel:bullet.hit.stone", "minecraft:block.metal.hit", "ntmgunfeel:bullet.hit.ste"})
            assertEquals(oldSteel(id), Ricochet.steelSound(id), id);
    }
}
