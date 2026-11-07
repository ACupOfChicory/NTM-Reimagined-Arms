package acup.gunfeel.gl54;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Gl54RangeTest {
    @Test
    void theShiftIsHeldWithinThreeBlocksEitherWay() {
        assertEquals(3, Gl54Range.clamp(7));
        assertEquals(-3, Gl54Range.clamp(-4));
        assertEquals(2, Gl54Range.clamp(2));
        assertEquals(0, Gl54Range.clamp(0));
    }

    @Test
    void theShiftStartsPastCoverForABlockAndAtTheCreatureForAnEntity() {
        assertEquals(1, Gl54Range.defaultOffset(false));
        assertEquals(0, Gl54Range.defaultOffset(true));
    }

    @Test
    void theMeasureIsOffByHalfABlockAtMostEitherWay() {
        assertEquals(39.5, Gl54Range.noisy(40.0, 0.0), 1e-12);
        assertEquals(40.5, Gl54Range.noisy(40.0, 1.0), 1e-12);
        assertEquals(40.0, Gl54Range.noisy(40.0, 0.5), 1e-12);
        java.util.Random random = new java.util.Random(7);
        double low = 1e9, high = -1e9;
        for (int i = 0; i < 100000; i++) {
            double v = Gl54Range.noisy(40.0, random.nextDouble());
            assertTrue(v >= 39.5 && v <= 40.5, "noise " + v);
            low = Math.min(low, v);
            high = Math.max(high, v);
        }
        // both ends are reached, so it is uniform over the whole span and not narrower
        assertTrue(low < 39.51 && high > 40.49, low + " .. " + high);
    }

    @Test
    void theMeasureNeverGoesBelowZero() {
        assertEquals(0.0, Gl54Range.noisy(0.2, 0.0), 1e-12);
    }

    @Test
    void theBurstIsTheRangePlusTheShift() {
        assertEquals(22.0, Gl54Fuse.distance(20F, 2));
    }
}
