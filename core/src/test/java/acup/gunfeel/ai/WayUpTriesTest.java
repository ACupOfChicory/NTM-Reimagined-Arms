package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// A soldier's walks to the way up a shaft (WayUpTries), without Minecraft: its foot at y 3, the foe lost up it.
class WayUpTriesTest {
    static final int FOE = 7;
    static final double FOOT = 3;

    /** its walk begun at tick 0, failed at `at`: the ticks it waits after, till it begins it again (the tick returned) */
    static long failAndWait(WayUpTries w, long at) {
        for (long t = at; t < at + 1000; t++) {
            assertTrue(w.tried(FOE, 0));
            if (w.step(t, true, true, false, FOOT)) return t;
            assertTrue(w.stuck(), "stuck from its miss on, its pause through");
        }
        return -1;
    }

    @Test
    void aFailedWalkIsBegunAgainAfterAPause() {
        WayUpTries w = new WayUpTries();
        assertTrue(w.tried(FOE, 0));
        assertTrue(w.step(0, false, false, false, FOOT), "its walk to the way up begun");
        for (long t = 1; t < 10; t++) assertFalse(w.step(t, true, false, false, FOOT), "on its way: no new walk");
        assertFalse(w.stuck());
        assertFalse(w.step(10, true, true, false, FOOT), "failed: not at once again");
        assertTrue(w.missed);
        assertEquals(1, w.misses());
        assertTrue(w.stuck(), "no hunter from its miss");
        assertEquals(10 + WayUpTries.PAUSE, failAndWait(w, 11), "again after the pause");
        // its walk again (the failed one stopped, a new one begun): no hunter still, till it is up the ladder a way
        for (long t = 51; t < 70; t++) {
            assertFalse(w.step(t, true, false, false, FOOT));
            assertTrue(w.stuck(), "no hunter while it walks again");
        }
    }

    @Test
    void threeMissesOnALossGiveTheWayUpUp() {
        WayUpTries w = new WayUpTries();
        w.tried(FOE, 0);
        w.step(0, false, false, false, FOOT);
        long t = 10;
        for (int miss = 1; miss < WayUpTries.TRIES; miss++) {
            assertTrue(w.tried(FOE, 0));
            assertFalse(w.step(t, true, true, false, FOOT));
            assertEquals(miss, w.misses());
            t = failAndWait(w, t + 1);
            assertTrue(t > 0, "again after miss " + miss);
            t += 10;
        }
        assertTrue(w.tried(FOE, 0));
        assertFalse(w.step(t, true, true, false, FOOT), "the third miss");
        assertEquals(WayUpTries.TRIES, w.misses());
        assertFalse(w.tried(FOE, 0), "given up for this loss");
        assertTrue(w.stuck(), "and no hunter: it searches from the foot");
    }

    @Test
    void stuckTillUpTheLadderAWayNotOnItsFootRung() {
        WayUpTries w = new WayUpTries();
        w.tried(FOE, 0);
        w.step(0, false, false, false, FOOT);
        // its foot is a rung's cell: on it, the walk failing still, it stays stuck and its pause runs (no miss each tick)
        assertFalse(w.step(10, true, true, true, FOOT));
        for (long t = 11; t < 10 + WayUpTries.PAUSE; t++) {
            assertFalse(w.step(t, true, true, true, FOOT));
            assertFalse(w.missed, "one miss, not one each tick");
            assertTrue(w.stuck());
        }
        assertTrue(w.step(10 + WayUpTries.PAUSE, true, true, true, FOOT), "again after the pause");
        // up the rungs: stuck till it is CLIMBED over where it missed
        assertFalse(w.step(60, true, false, true, FOOT + 1));
        assertTrue(w.stuck(), "a block up: stuck still");
        assertFalse(w.step(61, true, false, true, FOOT + WayUpTries.CLIMBED));
        assertTrue(w.stuck(), "CLIMBED up, not over it: stuck still");
        assertFalse(w.step(62, true, false, true, FOOT + WayUpTries.CLIMBED + 0.5));
        assertFalse(w.stuck(), "over it: a hunter again");
        assertEquals(0, w.misses());
    }

    @Test
    void aNewLossIsCountedAfresh() {
        WayUpTries w = new WayUpTries();
        w.tried(FOE, 0);
        w.step(0, false, false, false, FOOT);
        long t = 10;
        for (int miss = 0; miss < WayUpTries.TRIES; miss++) {
            w.tried(FOE, 0);
            w.step(t, true, true, false, FOOT);
            t += WayUpTries.PAUSE + 1;
            w.tried(FOE, 0);
            w.step(t, true, true, false, FOOT);
            t += 5;
        }
        assertFalse(w.tried(FOE, 0), "given up for the loss at 0");
        // seen again and lost again at 500: tried afresh
        assertTrue(w.tried(FOE, 500));
        assertFalse(w.stuck());
        assertEquals(0, w.misses());
        // so for another target
        w.step(600, true, true, false, FOOT);
        assertTrue(w.stuck());
        assertTrue(w.tried(FOE + 1, 500));
        assertFalse(w.stuck());
    }

    @Test
    void aWayUpAnotherEndsThePause() {
        WayUpTries w = new WayUpTries();
        w.tried(FOE, 0);
        w.step(0, false, false, false, FOOT);
        w.step(10, true, true, false, FOOT);
        // its way up looked for again in the pause, another cell now: begun at once, its miss counted
        assertTrue(w.step(20, false, false, false, FOOT));
        assertTrue(w.stuck());
        assertFalse(w.step(30, true, true, false, FOOT), "failed again: a pause");
        assertTrue(w.missed);
        assertEquals(2, w.misses());
    }
}
