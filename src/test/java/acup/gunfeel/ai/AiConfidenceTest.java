package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// A bot's confidence: falls with hits and losses, rises with its own, drifts back, shared with its squad.
class AiConfidenceTest {
    @BeforeEach
    @AfterEach
    void on() {
        AiConfidence.on = true;
    }

    @Test
    void hurtAndLossesHoldItBackAndItComesBack() {
        AiConfidence c = new AiConfidence();
        assertEquals(AiConfidence.BASE, c.value(0), 1e-6);
        c.hurt(0.5F, 0);
        c.add(-AiConfidence.FRIEND_DOWN, 0);
        float low = AiConfidence.BASE - AiConfidence.HURT * 0.5F - AiConfidence.FRIEND_DOWN;
        assertEquals(low, c.value(0), 1e-6);
        assertTrue(c.holdsBack(0));
        assertEquals(AiConfidence.HOLD_RANGE, c.range(0), 1e-9);
        // BACK a tick towards BASE, and no farther
        assertEquals(low + AiConfidence.BACK * 200, c.value(200), 1e-5);
        assertEquals(AiConfidence.BASE, c.value(10_000), 1e-6);
        assertFalse(c.holdsBack(10_000));
    }

    @Test
    void hitsOfOneTickCountAsOne() {
        AiConfidence c = new AiConfidence();
        // eight pellets of a twentieth each: as one hit of two fifths
        for (int i = 0; i < 8; i++) c.hurt(0.05F, 0);
        assertEquals(AiConfidence.BASE - AiConfidence.HURT * 0.4F, c.value(0), 1e-5);
        // a scratch takes HURT_MIN, once a tick
        AiConfidence d = new AiConfidence();
        d.hurt(0.001F, 0);
        d.hurt(0.001F, 0);
        assertEquals(AiConfidence.BASE - AiConfidence.HURT_MIN, d.value(0), 1e-6);
        d.hurt(0.001F, 1);
        assertEquals(AiConfidence.BASE - 2 * AiConfidence.HURT_MIN + AiConfidence.BACK, d.value(1), 1e-6);
    }

    @Test
    void hitsAndFoesDownPressItOn() {
        AiConfidence c = new AiConfidence();
        c.add(AiConfidence.FOE_DOWN, 0);
        // a burst in one tick counts once
        for (int i = 0; i < 5; i++) c.hit(0);
        assertEquals(AiConfidence.BASE + AiConfidence.FOE_DOWN + AiConfidence.HIT, c.value(0), 1e-6);
        assertTrue(c.presses(0));
        assertEquals(AiConfidence.PRESS_RANGE, c.range(0), 1e-9);
    }

    @Test
    void pinnedCountsOnceAndTheSquadShares() {
        AiConfidence c = new AiConfidence();
        c.tick(true, 0);
        c.tick(true, 1);
        assertEquals(AiConfidence.BASE - AiConfidence.PINNED + AiConfidence.BACK, c.value(1), 1e-6);
        AiConfidence d = new AiConfidence();
        d.share(0.1F, 0);
        assertEquals(AiConfidence.BASE + AiConfidence.SHARE * (0.1F - AiConfidence.BASE), d.value(0), 1e-6);
    }

    @Test
    void offItNeitherPressesNorHoldsBack() {
        AiConfidence c = new AiConfidence();
        AiConfidence.on = false;
        c.add(-1F, 0);
        assertFalse(c.holdsBack(0));
        assertEquals(1.0, c.range(0), 1e-9);
    }
}
