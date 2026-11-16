package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** AiSquads.apart: a base of fire stays one of its squad's twice as far as two riflemen */
class AiSquadsApartTest {
    private static boolean near(double blocks, boolean base) {
        return AiSquads.apart(blocks * blocks, base) <= AiSquads.NEAR * AiSquads.NEAR;
    }

    @Test
    void riflemenWithinNear() {
        assertTrue(near(16, false));
        assertFalse(near(16.5, false));
    }

    @Test
    void baseOfFireTwiceAsFar() {
        // the base stage's riflemen gone round the side: 16 to 18 blocks off the machine gunner
        assertTrue(near(18.4, true));
        assertTrue(near(32, true));
        assertFalse(near(32.5, true));
    }
}
