package acup.gunfeel.shock.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import acup.gunfeel.shock.ShockServerBase;

// The shock's edges (ShockArcs): the vignette and the discharges follow the glitch's strength down to nothing as the
// shock wears off, and a discharge starts at an edge and stays on the screen.
class ShockArcsTest {
    @Test
    void fadesWithTheShock() {
        int full = 40;
        float lastV = Float.MAX_VALUE;
        int lastN = Integer.MAX_VALUE;
        for (int t = 0; t <= full + ShockServerBase.FADE_TICKS + 5; t++) {
            float k = ShockServerBase.envelope(t, full);
            float v = ShockArcs.vignette(k);
            int n = ShockArcs.arcs(k);
            assertTrue(v <= lastV + 1e-6F && n <= lastN, "only less at " + t);
            lastV = v;
            lastN = n;
        }
        assertEquals(0F, ShockArcs.vignette(0F));
        assertEquals(0, ShockArcs.arcs(0F));
        assertEquals(ShockArcs.VIGNETTE, ShockArcs.vignette(1F), 1e-6F);
        assertEquals(ShockArcs.ARCS_MAX, ShockArcs.arcs(1F));
        assertEquals(ShockArcs.ARCS_MIN, ShockArcs.arcs(0.01F));
    }

    @Test
    void arcsStartAtAnEdgeAndStayOnScreen() {
        Random r = new Random(7);
        float w = 427, h = 240;
        for (int i = 0; i < 500; i++) {
            float[] p = ShockArcs.arc(r, w, h);
            int points = (p.length - 1) / 2;
            assertTrue(points >= 6 && points <= 9, "5 to 8 jags");
            assertTrue(p[0] == 0F || p[0] == w || p[1] == 0F || p[1] == h, "from an edge");
            for (int j = 0; j < points; j++) assertTrue(p[j * 2] >= 0 && p[j * 2] <= w && p[j * 2 + 1] >= 0 && p[j * 2 + 1] <= h, "on the screen");
            float end = (float) Math.hypot(p[points * 2 - 2] - p[0], p[points * 2 - 1] - p[1]);
            assertTrue(end <= ShockArcs.LENGTH_MAX * Math.min(w, h) + 1e-3F, "no longer than its length");
            float b = p[p.length - 1];
            assertTrue(b >= 0.5F && b <= 1F);
        }
    }
}
