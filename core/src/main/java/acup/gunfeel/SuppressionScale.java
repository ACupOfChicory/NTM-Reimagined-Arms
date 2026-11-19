package acup.gunfeel;

// The suppression scale of one player, 0 to 100, and what it gives: the numbers alone, no game (Suppression feeds it
// and draws it). An event adds its force to a target; the level shown climbs to the target quickly (at least 4 a tick,
// a fifth of the gap when that is more), then falls by `recovery` a tick (100 → 0 in 5 s), the target itself by twice
// that. Passes and hits close in time (each within 2 s of the last) make a burst: from the third on each one counts
// 1.35 times more than a single one, up to 1.35^4.
public final class SuppressionScale {
    public static final float MAX = 100F;
    // ticks between two events of one burst
    static final int BURST_TICKS = 40;
    static final float BURST_STEP = 1.35F;
    static final int BURST_MAX = 4;

    private float target, level;
    private long lastBurst = Long.MIN_VALUE / 2;
    private int burst;

    // ---- the forces (before the server's own multipliers)

    // a bullet passed `d` blocks from the eyes at `speed` blocks a tick; `range`: the farthest that still counts
    public static float pass(double d, double speed, double range) {
        double near = Math.max(0.35, 1.0 - d / 16.0);
        double fast = Math.min(1.8, 0.9 + 2.0 * speed);
        return d > range ? 0F : (float) (18.0 * near * fast);
    }

    // a bullet stopped `d` blocks from the eyes (in a block, a creature, water) at `speed` blocks a tick
    public static float impact(double d, double speed, double range) {
        if (d > range || range <= 0) return 0F;
        double near = Math.max(0.2, 1.0 - d / range);
        return (float) (24.0 * near * (1.0 + Math.min(1.5, 3.2 * speed)));
    }

    // how far a blast reaches, by the server's range for a usual one; size: how far it hurts (NTM's hand grenade 7.5,
    // TNT 8: twice vanilla's power), a bigger one is felt farther, up to three times
    public static double blastRange(float size, double range) {
        return range * Math.clamp(size / 8.0, 1.0, 3.0);
    }

    // a blast of `size` went off `d` blocks from the eyes
    public static float blast(double d, float size, double range) {
        double r = blastRange(size, range);
        if (d >= r || r <= 0) return 0F;
        double power = 1.3 + 0.225 * Math.clamp(size / 16.0, 0.0, 1.0);
        return (float) (38.0 * Math.pow(1.0 - d / r, 1.35) * power);
    }

    // ---- the scale

    // adds an event at `tick`; burst: a pass or a hit (a blast is not); returns the force added
    public float add(float force, boolean burstKind, long tick) {
        if (force <= 0F) return 0F;
        if (burstKind) {
            burst = tick - lastBurst <= BURST_TICKS ? burst + 1 : 0;
            lastBurst = tick;
            if (burst >= 2) force *= (float) Math.pow(BURST_STEP, Math.min(burst - 1, BURST_MAX));
        }
        target = Math.min(MAX, target + force);
        return force;
    }

    // one tick: `recovery` = how much the level falls a tick once it reached the target
    public void tick(float recovery) {
        if (level < target) level = Math.min(target, level + Math.max(4F, (target - level) / 5F));
        else level = Math.max(0F, level - recovery);
        target = Math.max(0F, target - 2F * recovery);
    }

    public float level() {
        return level;
    }

    public float target() {
        return target;
    }

    // the level as a share, 0 to 1
    public float share() {
        return level / MAX;
    }

    public void clear() {
        target = level = 0F;
        burst = 0;
        lastBurst = Long.MIN_VALUE / 2;
    }

    // ---- what it gives

    // the turn of the view of an event, degrees, by its force and the server's strength (`deg` at most ×3)
    public static float flinch(float force, float deg) {
        return (0.5F + 2.5F * Math.min(1F, force / 100F)) * deg;
    }

    // the most the screen darkens anywhere at a full scale and strength 1
    public static final float DARK_MAX = 0.40F;
    // of which the whole screen gets this, the edges the rest
    static final float DARK_FILL = 0.22F;

    // the darkness of the whole screen at share r (strength k, 0..1)
    public static float fill(float r, float k) {
        if (r <= 0.01F) return 0F;
        return DARK_FILL * k * r * r * (0.35F + 0.65F * r);
    }

    // the width of the edges' band, as a share of the screen's smaller side, and its rings
    public static float edgeWidth(float r) {
        return 0.20F + 0.15F * r;
    }

    public static int rings(float r) {
        return 15 + Math.round(15F * r);
    }

    // how dark (0..1, all told with the fill under it) ring i of n is, from the edge in; never past DARK_MAX × k
    public static float ringDark(int i, int n, float r, float k) {
        float f = fill(r, k);
        if (r < 0.1F) return f;
        float u = 1F - i / (float) n;
        float edge = (DARK_MAX - DARK_FILL) * k * r * u * u;
        return Math.min(DARK_MAX * k, f + edge);
    }

    // the alpha a ring is drawn with over the fill to come to its darkness
    public static float ringAlpha(int i, int n, float r, float k) {
        float f = fill(r, k);
        float d = ringDark(i, n, r, k);
        return f >= 1F ? 0F : Math.max(0F, (d - f) / (1F - f));
    }

    // the most the world's sounds go down, at a full scale and strength 1; the fight's sounds (shots, blasts, bullets)
    // go down FIGHT of that, so they never fall silent
    public static final float MUFFLE_MAX = 0.6F;
    public static final float FIGHT = 0.35F;

    // the volume factor of a sound at share r, strength k; fight: a shot's, a blast's or a bullet's sound
    public static float muffle(float r, float k, boolean fight) {
        float m = MUFFLE_MAX * Math.clamp(k, 0F, 1F) * Math.clamp(r, 0F, 1F);
        return 1F - (fight ? FIGHT * m : m);
    }
}
