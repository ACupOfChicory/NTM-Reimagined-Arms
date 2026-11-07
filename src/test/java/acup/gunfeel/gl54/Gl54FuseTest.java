package acup.gunfeel.gl54;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Gl54FuseTest {
    @Test
    void noMeasurementNoFuse() {
        assertTrue(Double.isNaN(Gl54Fuse.distance(Float.NaN)));
    }

    @Test
    void theFuseIsTheMeasuredRangeButNotUnderTheArmingDistance() {
        assertEquals(87.0, Gl54Fuse.distance(87F));
        assertEquals(Gl54Grenade.ARM_DISTANCE, Gl54Fuse.distance(1.5F));
    }

    @Test
    void theStepThatCrossesTheFuseIsCutWhereItCrosses() {
        // 4.7 blocks a tick: from 85 to 89.7 with a fuse at 87 is 2 blocks in, 2/4.7 of the step
        assertEquals(2.0 / 4.7, Gl54Fuse.crossing(85.0, 89.7, 87.0), 1e-12);
        assertEquals(1.0, Gl54Fuse.crossing(86.0, 87.0, 87.0), 1e-12);
    }

    @Test
    void stepsBeforeOrAfterItAreNone() {
        assertTrue(Double.isNaN(Gl54Fuse.crossing(80.0, 84.7, 87.0)));
        assertTrue(Double.isNaN(Gl54Fuse.crossing(88.0, 92.7, 87.0)));
        assertTrue(Double.isNaN(Gl54Fuse.crossing(0.0, 4.7, Double.NaN)));
    }

    @Test
    void aGrenadeFlownThroughTheFuseBurstsAtItsRangeToTheBlock() {
        // a flat shot walked in steps of the grenade's speed: the first step to cross the fuse is cut exactly at it
        double f = Gl54Fuse.distance(87F), x = 0;
        while (true) {
            double t = Gl54Fuse.crossing(x, x + Gl54Grenade.SPEED, f);
            if (!Double.isNaN(t)) {
                assertEquals(f, x + Gl54Grenade.SPEED * t, 1e-9);
                break;
            }
            x += Gl54Grenade.SPEED;
        }
    }
}
