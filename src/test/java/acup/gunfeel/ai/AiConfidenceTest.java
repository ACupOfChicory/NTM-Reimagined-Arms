package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// A bot's confidence: falls with hits and losses, rises with its own, drifts back, shared with its squad.
class AiConfidenceTest {
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
    void keptUnderFireItDoesNotGetOverIt() {
        // a full scale: each tick takes twice the drift back, so it falls by the drift; half a scale undoes the drift
        AiConfidence c = new AiConfidence();
        c.tick(false, 0F, 0);
        for (int t = 1; t <= 100; t++) c.tick(false, 1F, t);
        assertEquals(AiConfidence.BASE - 100 * (AiConfidence.UNDER - AiConfidence.BACK), c.value(100), 1e-3);
        AiConfidence d = new AiConfidence();
        d.add(-0.2F, 0);
        for (int t = 1; t <= 100; t++) d.tick(false, 0.5F, t);
        assertEquals(AiConfidence.BASE - 0.2F, d.value(100), 1e-4);
    }

    @Test
    void pinnedCountsOnceAndTheSquadShares() {
        AiConfidence c = new AiConfidence();
        c.tick(true, 0F, 0);
        c.tick(true, 0F, 1);
        assertEquals(AiConfidence.BASE - AiConfidence.PINNED + AiConfidence.BACK, c.value(1), 1e-6);
        AiConfidence d = new AiConfidence();
        d.share(0.1F, 0);
        assertEquals(AiConfidence.BASE + AiConfidence.SHARE * (0.1F - AiConfidence.BASE), d.value(0), 1e-6);
    }

    @Test
    void itFallsBackFromBreakUntilBackAtLow() {
        AiConfidence c = new AiConfidence();
        c.add(-(AiConfidence.BASE - AiConfidence.BREAK) - 0.01F, 0);
        assertTrue(c.fallsBack(0));
        // past BREAK on its way back up, still falling back; at LOW it stops
        long past = (long) Math.ceil(0.03F / AiConfidence.BACK);
        assertTrue(c.value(past) > AiConfidence.BREAK);
        assertTrue(c.fallsBack(past));
        float start = AiConfidence.BREAK - 0.01F;
        long under = (long) ((AiConfidence.LOW - 0.002F - start) / AiConfidence.BACK), over = (long) ((AiConfidence.LOW + 0.002F - start) / AiConfidence.BACK);
        assertTrue(c.fallsBack(under));
        assertTrue(c.holdsBack(under));
        assertFalse(c.fallsBack(over));
        // above BREAK from the start: no fall back
        AiConfidence d = new AiConfidence();
        d.add(-(AiConfidence.BASE - AiConfidence.BREAK) + 0.02F, 0);
        assertFalse(d.fallsBack(0));
        assertTrue(d.holdsBack(0));
    }
}
