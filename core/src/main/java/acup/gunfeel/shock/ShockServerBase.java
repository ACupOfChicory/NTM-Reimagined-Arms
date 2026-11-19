package acup.gunfeel.shock;

import acup.gunfeel.ReaimTimes;

/**
 * The shock's numbers (ShockServer in 26.2: who is shocked, the slowness, the discharge): its strength over time and
 * the server's settings, alike on the server and the client.
 */
public class ShockServerBase {
    ShockServerBase() {
    }

    /** after its shockSeconds at full a shock wears off over these game ticks (the client's glitch, shake, drift too) */
    public static final int FADE_TICKS = 60;
    /** the share of the speed a shock takes at full: a stun, far past vanilla's Slowness II (0.3) */
    public static final float SLOW = 0.8F;
    /**
     * the share of a player's view a shock takes at full (GunWeight.fov). The game would narrow it by the speed, to
     * x0.6 at SLOW 0.8, a tunnel; the shock's own narrowing is this instead, fading with the shock
     */
    public static final float FOV = 0.2F;
    /** a hit in a shock or in its immunity does this share of a slug of the same gun (with the balance) */
    public static final float REPEAT = 0.9F;

    /**
     * The shock's strength, 0 to 1, {@code t} game ticks into a shock of {@code full} ticks at full: 1 for those, then
     * down to 0 over FADE_TICKS along a half cosine (no step at either end). Server and client alike.
     */
    public static float envelope(float t, int full) {
        if (t < 0F) return 0F;
        if (t < full) return 1F;
        float u = (t - full) / FADE_TICKS;
        return u >= 1F ? 0F : 0.5F * (1F + (float) Math.cos(Math.PI * u));
    }

    // ---- the server's settings (ntm-gunfeel-server.properties; a client reads the server's while connected)

    public static boolean on() {
        return ReaimTimes.flag("shock", true);
    }

    public static float seconds() {
        return Math.clamp(ReaimTimes.number("shockSeconds", 2F), 0.25F, 10F);
    }

    public static float immunitySeconds() {
        return Math.clamp(ReaimTimes.number("shockImmunity", 3F), 0F, 60F);
    }

    /** NTM's spread units (a shot's direction off by about this many radians each way): buckshot's is 0.035 */
    public static float spread() {
        return Math.clamp(ReaimTimes.number("shockSpread", 0.12F), 0F, 1F);
    }

    /** the most the HUD glitches and shakes, 0 to 1 (each player may take less, down to shockGlitchMin) */
    public static float glitch() {
        return Math.clamp(ReaimTimes.number("shockGlitch", 1F), 0F, 1F);
    }

    /** the least share of shockGlitch a player may turn their own glitch down to */
    public static float glitchMin() {
        return Math.clamp(ReaimTimes.number("shockGlitchMin", 0.5F), 0F, 1F);
    }

    /** how fast the view turns about on its own at most, degrees a second (0: it does not) */
    public static float cameraDeg() {
        return Math.clamp(ReaimTimes.number("shockCamera", 45F), 0F, 360F);
    }

    /** the view's small shake at most, degrees */
    public static float shakeDeg() {
        return Math.clamp(ReaimTimes.number("shockShake", 0.6F), 0F, 5F);
    }
}
