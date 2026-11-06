package acup.gunfeel.water;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import acup.gunfeel.water.SunkPhysics.Body;

class SunkPhysicsTest {
    private static final double[] EMPTY = {};

    /** Floor of full blocks below y=0, water from y=0 up, a wall of full blocks at z<=-4. */
    static final class TestWorld implements SunkPhysics.World {
        public double[] boxes(int x, int y, int z) {
            if (y < 0 || z <= -4) return new double[] {x, y, z, x + 1, y + 1, z + 1};
            return EMPTY;
        }

        public boolean water(int x, int y, int z) {
            return y >= 0;
        }
    }

    private static final TestWorld W = new TestWorld();

    private static Body capsule(SunkPhysics p, double x, double y, double z, double vx, double vy, double vz) {
        return p.add(null, x, y, z, 0, 0, -1, 0.027, 0.02, vx, vy, vz, 5, 1.2, 0, 0, false);
    }

    private static void run(SunkPhysics p, double sec) {
        for (int i = 0, n = (int) Math.round(sec * 60); i < n; i++) p.step(W, 1.0 / 60);
    }

    private static double lowest(Body b) {
        double[] e = new double[6];
        SunkPhysics.ends(b, e);
        return Math.min(e[1], e[4]) - b.r;
    }

    @Test
    void capsuleLiesOnTheFloor() {
        SunkPhysics p = new SunkPhysics(400, 30);
        Body b = capsule(p, 0.5, 3, 0.5, 0, 0, -1.5);
        run(p, 10);
        run(p, 2);
        double low = lowest(b);
        System.out.printf("lie: lowest point %.4f, asleep %b, pos %.3f %.3f %.3f%n", low, b.asleep, b.px, b.py, b.pz);
        assertTrue(low >= -0.01 && low <= 0.02, "lowest " + low);
        assertTrue(b.asleep);
    }

    @Test
    void terminalSpeed() {
        SunkPhysics p = new SunkPhysics(400, 30);
        Body b = p.add(null, 0.5, 20, 0.5, 0, 0, -1, 0.05, 0, 0, 0, 0, 0, 1.2, 0, 0, false);
        run(p, 5);
        System.out.printf("terminal: |vy| %.4f%n", Math.abs(b.vy));
        assertEquals(1.2, Math.abs(b.vy), 0.05);
    }

    @Test
    void pile() {
        SunkPhysics p = new SunkPhysics(400, 30);
        Random rnd = new Random(7);
        double minR = 1;
        for (int i = 0; i < 300; i++) {
            boolean ball = i % 3 == 0;
            double r = 0.02 + rnd.nextDouble() * 0.06, h = ball ? 0 : rnd.nextDouble() * 0.15;
            minR = Math.min(minR, r);
            p.add(null, rnd.nextDouble() * 2, 1 + rnd.nextDouble() * 4, -2 + rnd.nextDouble() * 2,
                    rnd.nextDouble() - .5, rnd.nextDouble() - .5, rnd.nextDouble() - .5, r, h, 0, 0, 0, 3, 1.2, 0, 0, false);
        }
        run(p, 20);
        List<Body> bs = p.bodies();
        double pen = 0, below = 0;
        int asleep = 0;
        double[] ea = new double[6], eb = new double[6], c = new double[6];
        for (int i = 0; i < bs.size(); i++) {
            Body a = bs.get(i);
            assertFalse(Double.isNaN(a.px + a.py + a.pz + a.q[0] + a.q[3]));
            if (a.asleep) asleep++;
            below = Math.max(below, -lowest(a));
            SunkPhysics.ends(a, ea);
            for (int j = i + 1; j < bs.size(); j++) {
                Body b = bs.get(j);
                SunkPhysics.ends(b, eb);
                SunkPhysics.closest(ea, eb, c);
                double d = Math.sqrt(Math.pow(c[0] - c[3], 2) + Math.pow(c[1] - c[4], 2) + Math.pow(c[2] - c[5], 2));
                pen = Math.max(pen, a.r + b.r - d);
            }
        }
        double frac = asleep / (double) bs.size();
        double awakeMs = p.lastMs();
        for (Body q : bs) { q.asleep = true; q.vx = q.vy = q.vz = q.wx = q.wy = q.wz = 0; }
        int reps = 200;
        long t0 = System.nanoTime();
        for (int i = 0; i < reps; i++) p.step(W, 1.0 / 60);
        double ms = (System.nanoTime() - t0) * 1e-6 / reps;
        System.out.printf("pile: bodies %d, max penetration %.5f (limit %.5f), below floor %.5f, asleep %.1f%%, %.4f ms/step with all asleep (%.3f ms/step while settling)%n",
                bs.size(), pen, 0.25 * minR, below, frac * 100, ms, awakeMs);
        assertTrue(pen < 0.25 * minR);
        assertTrue(below <= 0.02);
        assertTrue(frac >= 0.9);
        assertTrue(ms < 0.5);
    }

    @Test
    void limitDropsTheOldest() {
        SunkPhysics p = new SunkPhysics(400, 30);
        for (int i = 0; i < 450; i++) capsule(p, i * 0.1, 3, 0, 0, 0, 0);
        assertEquals(400, p.bodies().size());
        assertEquals(50, p.bodies().get(0).id);
    }

    @Test
    void lifetime() {
        SunkPhysics p = new SunkPhysics(400, 1);
        capsule(p, 0.5, 3, 0.5, 0, 0, 0);
        run(p, 2.2);
        System.out.println("lifetime: bodies after 2.2 s = " + p.bodies().size());
        assertEquals(0, p.bodies().size());
    }

    @Test
    void explosiveBodies() {
        SunkPhysics p = new SunkPhysics(400, 30);
        p.add(null, 0.5, 1, 0.5, 0, 0, -1, 0.03, 0.02, 0, 0, 0, 0, 1.2, 1, 0.5f, false);
        run(p, 3);
        List<Body> booms = p.takeBooms();
        System.out.println("explosive: booms " + booms.size() + ", bodies " + p.bodies().size());
        assertEquals(1, booms.size());
        assertTrue(booms.get(0).boom);
        assertEquals(0, p.bodies().size());

        // two pellets of one shot overlap in the water and just bump
        p.add(null, 0.5, 5, 0.5, 0, 0, -1, 0.05, 0, 0, 0, 0, 0, 1.2, 9, 0.5f, false);
        p.add(null, 0.54, 5, 0.5, 0, 0, -1, 0.05, 0, 0, 0, 0, 0, 1.2, 9, 0.5f, false);
        run(p, 0.5);
        assertEquals(0, p.takeBooms().size());
        assertEquals(2, p.bodies().size());
        // another shot's pellet goes off on touching
        p.add(null, 0.52, p.bodies().get(0).py, 0.5, 0, 0, -1, 0.05, 0, 0, 0, 0, 0, 1.2, 10, 0.5f, false);
        run(p, 0.1);
        assertTrue(p.takeBooms().size() >= 1);
    }

    @Test
    void blastPushesNear() {
        SunkPhysics p = new SunkPhysics(400, 30);
        Body near = p.add(null, 1, 0.2, 0, 0, 0, -1, 0.05, 0, 0, 0, 0, 0, 1.2, 0, 0, false);
        Body far = p.add(null, 5, 0.2, 0, 0, 0, -1, 0.05, 0, 0, 0, 0, 0, 1.2, 0, 0, false);
        p.blast(0, 0.2, 0, 1, false);
        double sn = Math.hypot(near.vx, Math.hypot(near.vy, near.vz)), sf = Math.hypot(far.vx, Math.hypot(far.vy, far.vz));
        System.out.printf("blast: near speed %.3f, far speed %.3f%n", sn, sf);
        assertTrue(near.vx > 0.1);
        assertEquals(0, sf, 1e-12);
    }

    @Test
    void closestPoints() {
        double[] c = new double[6];
        // parallel, overlapping along the axis
        SunkPhysics.closest(new double[] {0, 0, 0, 2, 0, 0}, new double[] {1, 1, 0, 3, 1, 0}, c);
        assertArrayEquals(new double[] {1, 0, 0, 1, 1, 0}, c, 1e-9);
        // crossed
        SunkPhysics.closest(new double[] {-1, 0, 0, 1, 0, 0}, new double[] {0, 1, -1, 0, 1, 1}, c);
        assertArrayEquals(new double[] {0, 0, 0, 0, 1, 0}, c, 1e-9);
        // one segment is a point
        SunkPhysics.closest(new double[] {1, 2, 3, 1, 2, 3}, new double[] {0, 0, 0, 2, 0, 0}, c);
        assertArrayEquals(new double[] {1, 2, 3, 1, 0, 0}, c, 1e-9);
    }
}
