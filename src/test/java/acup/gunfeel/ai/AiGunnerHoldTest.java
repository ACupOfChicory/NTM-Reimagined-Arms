package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The ticks a gunner holds fire for one of its own in the line (AiGunner.held): counted on through the pauses of its
 * bursts, so a soldier with short bursts steps aside too (AiSoldier.BLOCKED_MOVE). Before, a pause put the count back
 * to none, a burst shorter than BLOCKED_MOVE never got there, and the soldier stood behind its own.
 */
class AiGunnerHoldTest {
    /** bursts of 12 ticks, held for the friend, pauses of 20 between: the count passes BLOCKED_MOVE in the second burst */
    @Test
    void shortBurstsAddUpThroughPauses() {
        int count = 0, tick = 0, passedAt = -1;
        for (int burst = 0; burst < 3; burst++) {
            for (int i = 0; i < 12; i++, tick++) {
                count = AiGunner.held(count, true, false, false);
                if (passedAt < 0 && count > AiSoldier.BLOCKED_MOVE) passedAt = tick;
            }
            for (int i = 0; i < 20; i++, tick++) count = AiGunner.held(count, false, false, false);
        }
        assertTrue(passedAt >= 0 && passedAt < 12 + 20 + 12, "passed at " + passedAt);
    }

    @Test
    void aShotEndsTheCount() {
        assertEquals(0, AiGunner.held(15, false, true, false));
    }

    @Test
    void noTargetOrALongPauseEndsTheCount() {
        assertEquals(0, AiGunner.held(15, false, false, true));
        assertEquals(16, AiGunner.held(15, true, false, true));
    }

    /** the pause between bursts is shorter than HOLD_PAUSE: it keeps the count */
    @Test
    void theBurstPauseIsShorterThanTheHoldPause() {
        assertTrue(AiSoldier.BURST_PAUSE < AiGunner.HOLD_PAUSE);
    }
}
