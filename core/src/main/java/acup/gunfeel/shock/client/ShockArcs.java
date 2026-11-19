package acup.gunfeel.shock.client;

import java.util.Random;

/**
 * Client: the shock seen at the screen's edges, its look (ShockHud.arcs draws it, a layer of the HUD, no pass over the
 * whole frame): a light bluish white vignette round the edges and a few discharges jagging in from them, a new picture
 * of them every other tick.
 * Both as strong as the HUD's glitch (ShockClient.glitch(): the shock's envelope times shockGlitch), so they wear off
 * with the shock and a player who turned the glitch down sees less of them. Not glitched itself (ShockHud.KEEP).
 */
public final class ShockArcs {
    private ShockArcs() {
    }

    /** the vignette's alpha at its edge at full */
    static final float VIGNETTE = 0.35F;
    /** the vignette's depth, a share of the screen's shorter side */
    static final float VIGNETTE_DEPTH = 0.22F;
    /** discharges at the least and the most strength */
    static final int ARCS_MIN = 3, ARCS_MAX = 6;
    /** a discharge's length, shares of the screen's shorter side */
    static final float LENGTH_MIN = 0.08F, LENGTH_MAX = 0.15F;
    /** a new picture every this many game ticks */
    static final int FRAME_TICKS = 2;

    /** the discharges' glow and core, the vignette's edge (RGB) */
    static final int GLOW = 0x6FB8FF, CORE = 0xF2F8FF, EDGE = 0xDDEEFF;

    /** the vignette's alpha at the edge for a glitch of {@code k} (0 to 1) */
    static float vignette(float k) {
        return k <= 0F ? 0F : VIGNETTE * Math.min(1F, k);
    }

    /** how many discharges for a glitch of {@code k}: none at 0, ARCS_MIN at once, ARCS_MAX at full */
    static int arcs(float k) {
        return k <= 0.001F ? 0 : ARCS_MIN + Math.round((ARCS_MAX - ARCS_MIN) * Math.min(1F, k));
    }

    /**
     * a discharge's points (x0, y0, x1, y1, ...) on a screen {@code w} by {@code h}: from a point of an edge inward,
     * 5 to 8 jags, kept on the screen; and its own brightness last (0.5 to 1)
     */
    static float[] arc(Random r, float w, float h) {
        int side = r.nextInt(4);
        float t = 0.05F + r.nextFloat() * 0.9F;
        float x = side == 0 || side == 1 ? t * w : side == 2 ? 0F : w;
        float y = side == 2 || side == 3 ? t * h : side == 0 ? 0F : h;
        // inward: down from the top, up from the bottom, right from the left, left from the right
        double a = (side == 0 ? Math.PI / 2 : side == 1 ? -Math.PI / 2 : side == 2 ? 0 : Math.PI) + (r.nextFloat() - 0.5F) * 1.2F;
        float len = (LENGTH_MIN + r.nextFloat() * (LENGTH_MAX - LENGTH_MIN)) * Math.min(w, h);
        int n = 5 + r.nextInt(4);
        float[] p = new float[(n + 1) * 2 + 1];
        p[0] = x;
        p[1] = y;
        for (int i = 1; i <= n; i++) {
            // each jag off the way by up to ±0.7 rad, the way itself turning a little
            a += (r.nextFloat() - 0.5F) * 0.5F;
            double j = a + (r.nextFloat() - 0.5F) * 1.4F;
            x = Math.clamp(x + (float) Math.cos(j) * len / n, 0F, w);
            y = Math.clamp(y + (float) Math.sin(j) * len / n, 0F, h);
            p[i * 2] = x;
            p[i * 2 + 1] = y;
        }
        p[p.length - 1] = 0.5F + r.nextFloat() * 0.5F;
        return p;
    }
}
