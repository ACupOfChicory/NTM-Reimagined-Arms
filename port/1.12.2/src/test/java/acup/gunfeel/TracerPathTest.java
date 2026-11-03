package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// The tracer's path and ribbon: 26.2's Shot.at / tested, NTM's standard ricochet and Tracers.streak.
class TracerPathTest {
    // the server's bullet: start + v t, falling by g t^2 / 2
    @Test
    void fliesAsTheServerMovesIt() {
        TracerPath p = new TracerPath(new double[] {1, 64, 2}, new double[] {3, 0, 4}, 0.05);
        assertEquals(5.0, p.speed, 1e-12);
        assertArrayEquals(new double[] {7, 64 - 0.5 * 0.05 * 4, 10}, p.at(2), 1e-12);
        assertArrayEquals(new double[] {3, -0.1, 4}, p.velocity(2), 1e-12);
    }

    // the player's own tracer leaves the muzzle in their hands and joins the real path over JOIN blocks
    @Test
    void muzzleOffsetFadesOverJoin() {
        TracerPath p = new TracerPath(new double[] {0, 0, 0}, new double[] {2, 0, 0}, 0);
        p.muzzleOffset = new double[] {0, -0.2, 0.1};
        assertArrayEquals(new double[] {0, -0.2, 0.1}, p.at(0), 1e-12);
        assertArrayEquals(new double[] {2, -0.1, 0.05}, p.at(1), 1e-12); // 2 of 4 blocks
        assertArrayEquals(new double[] {4, 0, 0}, p.at(2), 1e-12);
        assertArrayEquals(new double[] {6, 0, 0}, p.at(3), 1e-12);
        // what is hit is decided without it
        assertArrayEquals(new double[] {2, 0, 0}, p.tested(1), 1e-12);
    }

    // the first tick is tested in a straight line from the shooter's eyes
    @Test
    void firstTickFromTheEyes() {
        TracerPath p = new TracerPath(new double[] {0.3, 1.5, 0}, new double[] {0, 0, 3}, 0);
        p.eye = new double[] {0, 1.62, 0};
        assertArrayEquals(new double[] {0, 1.62, 0}, p.tested(0), 1e-12);
        assertArrayEquals(new double[] {0.15, 1.56, 1.5}, p.tested(0.5), 1e-12);
        assertArrayEquals(new double[] {0.3, 1.5, 3}, p.tested(1), 1e-12);
        assertArrayEquals(new double[] {0.3, 1.5, 6}, p.tested(2), 1e-12);
    }

    // stopped by the server: the tracer stays at its stop point from then on
    @Test
    void stopsWhereTheServerStopped() {
        TracerPath p = new TracerPath(new double[] {0, 0, 0}, new double[] {1, 0, 0}, 0);
        double[] stop = {2.5, 0, 0};
        p.confirmedEnd = 2.5;
        p.stopPoint = stop;
        assertArrayEquals(new double[] {2, 0, 0}, p.at(2), 1e-12);
        assertSame(stop, p.at(2.5));
        assertSame(stop, p.at(10));
    }

    // NTM's standard ricochet: within ricochetAngle of the face's plane it bounces, mirrored on the face's axis
    @Test
    void ricochetsAsNtm() {
        double a = Math.toRadians(4);
        double[] glancing = {Math.cos(a), -Math.sin(a), 0};
        assertTrue(TracerPath.glances(1, glancing, 5F));
        assertFalse(TracerPath.glances(1, glancing, 3F));
        assertFalse(TracerPath.glances(1, new double[] {0, -1, 0}, 5F));
        assertFalse(TracerPath.glances(1, new double[] {0, 0, 0}, 90F));
        // the same angle on any face
        assertTrue(TracerPath.glances(5, new double[] {-Math.sin(a), 0, Math.cos(a)}, 5F));
        assertArrayEquals(new double[] {1, 0.5, -2}, TracerPath.bounce(1, new double[] {1, -0.5, -2}), 0);
        assertArrayEquals(new double[] {-1, -0.5, -2}, TracerPath.bounce(4, new double[] {1, -0.5, -2}), 0);
        assertArrayEquals(new double[] {1, -0.5, 2}, TracerPath.bounce(2, new double[] {1, -0.5, -2}), 0);
    }

    // the ribbon lies across the line of sight, as wide as tracerWidth near and never under a pixel far away
    @Test
    void streakFacesTheCamera() {
        float[] head = {0, 0, -10}, tail = {0, 0, -18};
        assertNull(TracerPath.streak(head, tail, 0.02F, 0F), "straight away from the camera: nothing to turn");
        head = new float[] {1, 0, -10};
        tail = new float[] {-1, 0, -10};
        float[][] q = TracerPath.streak(head, tail, 0.02F, 0F);
        // along x, seen down -z: its side is y
        assertEquals(0.02F, Math.abs(q[0][1]), 1e-6F);
        assertEquals(0.01F, Math.abs(q[2][1]), 1e-6F);
        assertEquals(0F, q[0][2] + 10, 1e-6F);
        float[][] far = TracerPath.streak(new float[] {1, 0, -1000}, new float[] {-1, 0, -1000}, 0.02F, 0.001F);
        assertTrue(Math.abs(far[0][1]) >= 0.001F * 1000 - 1e-3, "at least a pixel wide");
        assertNull(TracerPath.streak(head, head, 0.02F, 0F));
    }
}
