package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// The war check's flaps (AiCheckWar.flapsAllowed): the most flaps of n squads' changes chance gives at the rate before
// C5 (10.7 %) with FLAP_LEVEL left; the numbers as a binomial's tail has them (worked out apart, 2026-11-16).
class AiCheckWarFlapsTest {
    @Test
    void allowedAsTheBinomialTail() {
        assertEquals(7, AiCheckWar.flapsAllowed(26));
        assertEquals(8, AiCheckWar.flapsAllowed(30));
        assertEquals(9, AiCheckWar.flapsAllowed(39));
        assertEquals(12, AiCheckWar.flapsAllowed(60));
        assertEquals(17, AiCheckWar.flapsAllowed(90));
    }

    @Test
    void neverLessThanTheRateAndGrowsWithN() {
        int last = -1;
        for (long n = 1; n <= 300; n++) {
            int k = AiCheckWar.flapsAllowed(n);
            assertTrue(k >= last, "grows at " + n);
            assertTrue(k >= Math.floor(n * AiCheckWar.FLAPS_BEFORE), "not under the rate at " + n);
            last = k;
        }
    }
}
