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

    // the near pairs ready in time (AiCheckWar.leastByChance at READY_BEFORE 0.8): none needed of 1 or 2, half of 15
    // as the check had before; the low tail worked out apart (2026-11-17)
    @Test
    void readyInTimeAsTheBinomialLowTail() {
        assertEquals(0, AiCheckWar.leastByChance(1, AiCheckWar.READY_BEFORE));
        assertEquals(0, AiCheckWar.leastByChance(2, AiCheckWar.READY_BEFORE));
        assertEquals(1, AiCheckWar.leastByChance(3, AiCheckWar.READY_BEFORE));
        assertEquals(5, AiCheckWar.leastByChance(10, AiCheckWar.READY_BEFORE));
        assertEquals(8, AiCheckWar.leastByChance(15, AiCheckWar.READY_BEFORE));
        assertEquals(12, AiCheckWar.leastByChance(20, AiCheckWar.READY_BEFORE));
    }

    // C4b's rounds against C3's (AiCheckWar.moreByChance): not more in the mean is 1; a clear rise is rare by chance, a
    // small one in a wide spread is not
    @Test
    void moreByChanceByPermutations() {
        java.util.List<Double> low = java.util.List.of(0.10, 0.12, 0.15, 0.11, 0.14, 0.13, 0.12, 0.10, 0.16, 0.11, 0.13, 0.12);
        java.util.List<Double> high = java.util.List.of(0.30, 0.28, 0.35, 0.31, 0.27, 0.33, 0.29, 0.32, 0.30, 0.34, 0.28, 0.31);
        assertEquals(1.0, AiCheckWar.moreByChance(low, high));
        assertTrue(AiCheckWar.moreByChance(high, low) < 0.001);
        java.util.List<Double> wide = java.util.List.of(0.0, 0.5, 0.1, 0.4, 0.2, 0.3, 0.0, 0.6, 0.1, 0.3, 0.2, 0.4);
        java.util.List<Double> wider = java.util.List.of(0.05, 0.55, 0.15, 0.45, 0.25, 0.35, 0.05, 0.65, 0.15, 0.35, 0.25, 0.45);
        assertTrue(AiCheckWar.moreByChance(wider, wide) > 0.2);
    }
}
