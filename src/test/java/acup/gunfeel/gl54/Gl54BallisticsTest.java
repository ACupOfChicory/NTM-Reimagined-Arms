package acup.gunfeel.gl54;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// The flight walked tick by tick as EntityThrowableNT does it (Gl54Ballistics), and the holdover solved from it.
class Gl54BallisticsTest {
    static final double V = Gl54Grenade.SPEED, G = Gl54Grenade.GRAVITY;

    @Test
    void withoutGravityTheFlightIsAStraightLineAndNeverComesDown() {
        assertEquals(0.0, Gl54Ballistics.range(Math.toRadians(10), V, 0.0, 1.0, 0.0));
    }

    @Test
    void theStepsAreTheEntitys() {
        // 45 degrees: after one tick the grenade is v/sqrt2 out and up; the drop a tick is gravity times speed
        double a = Math.toRadians(20);
        double y1 = V * Math.sin(a), y2 = y1 + V * (Math.sin(a) - G);
        assertTrue(y2 > y1);
        // the same walk by hand, to the tick the grenade is back at the eye's height
        double x = 0, y = 0, mx = Math.cos(a), my = Math.sin(a);
        double reach = -1;
        for (int t = 0; t < 400; t++) {
            double nx = x + mx * V, ny = y + my * V;
            if (ny <= 0 && y > 0) {
                reach = x + (nx - x) * (y / (y - ny));
                break;
            }
            x = nx;
            y = ny;
            my -= G;
        }
        assertEquals(reach, Gl54Ballistics.range(a, 0.0), 1e-9);
    }

    @Test
    void holdoverComesBackToTheRangeItWasAskedFor() {
        for (double r : new double[] {20, 40, 80, 120}) {
            var h = Gl54Ballistics.holdover(r);
            assertTrue(h.reachable(), "reach " + r);
            assertEquals(r, Gl54Ballistics.range(h.angle(), 0.0), 1e-4, "range " + r);
        }
    }

    @Test
    void holdoverGrowsWithTheRangeAndTakesTheLowArc() {
        double last = -1;
        double best = Gl54Ballistics.farthestAngle(V, G, 1.0);
        for (double r : new double[] {20, 40, 60, 80, 100, 120}) {
            double a = Gl54Ballistics.holdover(r).angle();
            assertTrue(a > last, "grows at " + r);
            assertTrue(a <= best, "the low arc at " + r);
            last = a;
        }
    }

    @Test
    void closeShotsNeedNoRaise() {
        var h = Gl54Ballistics.holdover(3);
        assertEquals(0.0, h.angle());
        assertTrue(h.reachable());
    }

    @Test
    void beyondReachItIsTheFarthestAngle() {
        double reach = Gl54Ballistics.maxRange();
        var h = Gl54Ballistics.holdover(reach + 10);
        assertFalse(h.reachable());
        assertEquals(Gl54Ballistics.farthestAngle(V, G, 1.0), h.angle(), 1e-12);
        assertTrue(Gl54Ballistics.holdover(reach - 1).reachable());
    }

    @Test
    void theFarthestShotIsTheSteepestOneThatLandsWithinTheGrenadesLife() {
        // Earth's drop (0.0245 blocks a tick, a tick) at 4.7 blocks a tick: 45 degrees would carry 900 blocks but take 270 ticks of
        // the 200 the grenade lives, so the farthest is the angle that lands as its life ends
        double a = Gl54Ballistics.farthestAngle(V, G, 1.0);
        assertEquals(0.0245, G * V, 1e-9);
        assertTrue(Math.toDegrees(a) > 28 && Math.toDegrees(a) < 35, "angle " + Math.toDegrees(a));
        assertTrue(Gl54Ballistics.maxRange() > 700 && Gl54Ballistics.maxRange() < 850, "range " + Gl54Ballistics.maxRange());
        assertTrue(Gl54Ballistics.range(a + Math.toRadians(0.5), 0.0) == 0.0, "steeper it is gone before it lands");
    }

    @Test
    void theFloorUnderThePlayerIsNearerThanTheEyesHeight() {
        double a = Math.toRadians(5);
        assertTrue(Gl54Ballistics.range(a, 1.62) > Gl54Ballistics.range(a, 0.0));
    }
}
