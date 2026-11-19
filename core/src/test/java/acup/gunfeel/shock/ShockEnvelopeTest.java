package acup.gunfeel.shock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// The shock's strength over time (ShockServerBase.envelope): full for its time at full, then down smoothly over FADE_TICKS
// to nothing, the same on the server (spread, slowing) and the client (glitch, shake, drift).
class ShockEnvelopeTest {
    private static final int FULL = 40, FADE = ShockServerBase.FADE_TICKS;

    @Test
    void fullThenFadesToNothing() {
        assertEquals(0F, ShockServerBase.envelope(-1F, FULL));
        assertEquals(1F, ShockServerBase.envelope(0F, FULL));
        assertEquals(1F, ShockServerBase.envelope(FULL - 0.01F, FULL));
        assertEquals(1F, ShockServerBase.envelope(FULL, FULL), 1e-6F);
        assertEquals(0.5F, ShockServerBase.envelope(FULL + FADE / 2F, FULL), 1e-6F);
        assertEquals(0F, ShockServerBase.envelope(FULL + FADE, FULL));
        assertEquals(0F, ShockServerBase.envelope(FULL + FADE + 100, FULL));
    }

    @Test
    void fadeOnlyFallsAndHasNoStep() {
        float last = 1F;
        for (float t = FULL; t <= FULL + FADE; t += 0.25F) {
            float e = ShockServerBase.envelope(t, FULL);
            assertTrue(e <= last + 1e-6F, "falls at " + t);
            // a quarter tick takes off at most what the steepest point of the half cosine does
            assertTrue(last - e <= Math.PI / 2 / FADE * 0.25 + 1e-4, "no step at " + t);
            last = e;
        }
        // gentle at both ends: the first and the last tick of the fade change it by under 0.1 %
        assertTrue(1F - ShockServerBase.envelope(FULL + 1, FULL) < 0.001F);
        assertTrue(ShockServerBase.envelope(FULL + FADE - 1, FULL) < 0.001F);
    }

    @Test
    void slowIsAShare() {
        assertTrue(ShockServerBase.SLOW > 0F && ShockServerBase.SLOW < 1F);
    }

    @Test
    void viewNarrowsLessThanTheGameWould() {
        // the game's factor for such a slowing, standing: (speed share + 1) / 2
        float game = (1F - ShockServerBase.SLOW + 1F) / 2F;
        assertTrue(ShockServerBase.FOV > 0F && 1F - ShockServerBase.FOV > game, "x" + (1F - ShockServerBase.FOV) + " vs the game's x" + game);
    }
}
