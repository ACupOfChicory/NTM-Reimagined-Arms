package acup.gunfeel.shock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// The shock's strength over time (ShockServer.envelope): full for its time at full, then down smoothly over FADE_TICKS
// to nothing, the same on the server (spread, slowing) and the client (glitch, shake, drift).
class ShockEnvelopeTest {
    private static final int FULL = 40, FADE = ShockServer.FADE_TICKS;

    @Test
    void fullThenFadesToNothing() {
        assertEquals(0F, ShockServer.envelope(-1F, FULL));
        assertEquals(1F, ShockServer.envelope(0F, FULL));
        assertEquals(1F, ShockServer.envelope(FULL - 0.01F, FULL));
        assertEquals(1F, ShockServer.envelope(FULL, FULL), 1e-6F);
        assertEquals(0.5F, ShockServer.envelope(FULL + FADE / 2F, FULL), 1e-6F);
        assertEquals(0F, ShockServer.envelope(FULL + FADE, FULL));
        assertEquals(0F, ShockServer.envelope(FULL + FADE + 100, FULL));
    }

    @Test
    void fadeOnlyFallsAndHasNoStep() {
        float last = 1F;
        for (float t = FULL; t <= FULL + FADE; t += 0.25F) {
            float e = ShockServer.envelope(t, FULL);
            assertTrue(e <= last + 1e-6F, "falls at " + t);
            // a quarter tick takes off at most what the steepest point of the half cosine does
            assertTrue(last - e <= Math.PI / 2 / FADE * 0.25 + 1e-4, "no step at " + t);
            last = e;
        }
        // gentle at both ends: the first and the last tick of the fade change it by under 0.1 %
        assertTrue(1F - ShockServer.envelope(FULL + 1, FULL) < 0.001F);
        assertTrue(ShockServer.envelope(FULL + FADE - 1, FULL) < 0.001F);
    }

    @Test
    void slowIsAShare() {
        assertTrue(ShockServer.SLOW > 0F && ShockServer.SLOW < 1F);
    }
}
