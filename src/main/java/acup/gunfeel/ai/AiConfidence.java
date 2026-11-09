package acup.gunfeel.ai;

import net.minecraft.util.Mth;

/**
 * A bot's confidence (Days Gone's squads, notes: research §1.1, stage3-plan 3.2): 0 to 1, BASE to start with. Hurt,
 * pinned down, one of its own down near it: it falls; a hit of its own, a foe down near it: it rises; it drifts back
 * to BASE as the fight goes quiet. Its squad shares it a little each time squads are made (AiSquads: panic spreads, so
 * does dash). High, it presses on: cover nearer the target, after a lost target sooner; low, it holds back: cover
 * farther off, no hunt and no flank. Server thread.
 */
public final class AiConfidence {
    static final float BASE = 0.5F;
    /** pressing on from HIGH, holding back from LOW down, falling back from BREAK down until it is back at LOW */
    static final float HIGH = 0.7F, LOW = 0.3F, BREAK = 0.15F;
    /** the hits taken in a tick (a shotgun's pellets, a burst): this times their share of its health, at least HURT_MIN */
    static final float HURT = 0.3F, HURT_MIN = 0.03F;
    /** pinned down (AiProne), one of its own down near, a foe down near, a tick it hit a foe in */
    static final float PINNED = 0.08F, FRIEND_DOWN = 0.15F, FOE_DOWN = 0.2F, HIT = 0.04F;
    /** a fall near it counts within this, blocks */
    static final double NEAR = 24;
    /** back towards BASE a tick (0.25 in 25 s) */
    static final float BACK = 0.0005F;
    /** each time squads are made, this share of the way to its squad's mean */
    static final float SHARE = 0.25F;
    /** cover this much nearer the target than its gun's best range pressing on, farther holding back (CoverQuery.fightAt) */
    static final double PRESS_RANGE = 0.7, HOLD_RANGE = 1.4;
    /** off only for the stands' "before" */
    static boolean on = true;

    private float value = BASE;
    private long at = Long.MIN_VALUE;
    private boolean wasPinned, falling;
    /** the tick of the last hits taken, their share of its health so far and what they took off; the tick of its last hit */
    private long hurtTick = Long.MIN_VALUE, hitTick = Long.MIN_VALUE;
    private float hurtShare, hurtTaken;
    /** ticks pressing on, holding back, falling back (the stands) */
    public int pressTicks, holdTicks, fallTicks;

    /** its confidence this tick */
    public float value(long tick) {
        drift(tick);
        return value;
    }

    void add(float d, long tick) {
        drift(tick);
        value = Mth.clamp(value + d, 0F, 1F);
    }

    /** a hit taken, its share of its health; the hits of one tick count as one */
    void hurt(float share, long tick) {
        if (tick != hurtTick) {
            hurtTick = tick;
            hurtShare = hurtTaken = 0;
        }
        hurtShare += share;
        float take = Math.max(HURT_MIN, HURT * hurtShare);
        add(-(take - hurtTaken), tick);
        hurtTaken = take;
    }

    /** a hit of its own on a foe; once a tick */
    void hit(long tick) {
        if (tick == hitTick) return;
        hitTick = tick;
        add(HIT, tick);
    }

    /** each tick of its brain: pinned down now (AiProne.pinned; the moment it becomes so counts), and the stands' ticks */
    void tick(boolean pinned, long tick) {
        if (pinned && !wasPinned) add(-PINNED, tick);
        wasPinned = pinned;
        if (presses(tick)) pressTicks++;
        else if (fallsBack(tick)) fallTicks++;
        else if (holdsBack(tick)) holdTicks++;
    }

    /** set to this (the stands: a squad shaken at once) */
    void set(float v, long tick) {
        drift(tick);
        value = Mth.clamp(v, 0F, 1F);
    }

    /** a share of the way to its squad's mean */
    void share(float mean, long tick) {
        drift(tick);
        value += SHARE * (mean - value);
    }

    private void drift(long tick) {
        if (at != Long.MIN_VALUE && tick > at) {
            float d = BACK * (tick - at);
            value = value > BASE ? Math.max(BASE, value - d) : Math.min(BASE, value + d);
        }
        if (tick > at || at == Long.MIN_VALUE) at = tick;
    }

    /** pressing on this tick */
    public boolean presses(long tick) {
        return on && value(tick) >= HIGH;
    }

    /** holding back this tick */
    public boolean holdsBack(long tick) {
        return on && value(tick) <= LOW;
    }

    /** falling back this tick (AiSoldier: to cover farther off, a squad in waves): from BREAK down, until back at LOW */
    public boolean fallsBack(long tick) {
        float v = value(tick);
        falling = on && (v <= BREAK || falling && v < LOW);
        return falling;
    }

    /** its cover's range from the target, a share of its gun's best (CoverQuery.fightAt) */
    double range(long tick) {
        return presses(tick) ? PRESS_RANGE : holdsBack(tick) ? HOLD_RANGE : 1.0;
    }
}
