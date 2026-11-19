package acup.gunfeel.ai;

import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.CoreServices;
import acup.gunfeel.SuppressionScale;
import acup.gunfeel.SuppressionSettings;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.BodyControl;
import acup.gunfeel.host.WorldView;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;

/**
 * An AI mob lying down, on the same terms as a player (Prone: the server's prone key; on the ground, out of water, not
 * riding): the crawling pose, a crawling player's size (MixinAiProneSize), laid down on the client
 * (MixinAiProneRender), a third of the speed, and the spread Spread gives anyone crawling (spreadCrawl). The mind asks
 * for it each tick it wants it ({@link #want}), and only to fire lying or for a long reload; once down it stays down
 * {@link #MIN_LIE} ticks, unless its mind sends it running; getting up takes {@link #RISE} ticks before a shot standing.
 *
 * <p>It is suppressed as a player is (the same SuppressionScale and the server's forces; AiUnderFire tells it the shots
 * past it, those stopping near it and the blasts): from {@link #PIN} up it is pinned down, till it is under {@link #UNPIN}. With aiSuppression off,
 * three shots close past it within a second pin it down for three, as before.
 *
 * <p>The mob is its Body, its level a WorldView, its pose set through the version's BodyControl; the server's prone
 * key comes from the version ({@code proneOn}: 26.2's Prone.enabled), asked where it was. 26.2's AiProne passes its own.
 */
public class AiProneBase {
    private static final BodyControl CONTROL = CoreServices.load(BodyControl.class, (b, s) -> {
        throw new UnsupportedOperationException("no BodyControl here");
    });

    /** ticks from getting up to a shot standing */
    static final int RISE = 6;
    /** once down, it stays down this long at least: no dropping for a moment and up again */
    static final int MIN_LIE = 60;
    /** a reload this long or longer is one to lie down for, ticks */
    static final int LONG_RELOAD = 40;
    /** its walking speed lying, a share of standing */
    static final double CRAWL_SPEED = 0.3;
    /** a shot passing this close to its chest is a near miss, blocks */
    static final double NEAR = 2.0;
    /** near misses within WINDOW ticks that pin it down, and for how long */
    static final int PINNING = 3, WINDOW = 20, PINNED = 60;
    /** its scale (0 to 100) from which it is pinned down, and till it is under which (not in and out with every shot) */
    static final float PIN = 50F, UNPIN = 30F;
    /** near misses kept at most (those of the last WINDOW ticks; its tick takes the old ones away) */
    static final int MISSES = 64;

    private final Body mob;
    private final BooleanSupplier proneOn;
    private boolean want;
    private long changed = Long.MIN_VALUE / 2;
    private final LongArrayFIFOQueue misses = new LongArrayFIFOQueue();
    /** its suppression: the players' scale, fed by AiUnderFire */
    private final SuppressionScale scale = new SuppressionScale();
    private @Nullable Body missBy;
    private boolean newMiss, under;
    private long pinnedUntil = Long.MIN_VALUE;
    /** for the stands: shortLies, up within MIN_LIE of going down, not sent running; briefLies, down less than BRIEF */
    public int downs, nearMisses, shortLies, briefLies;
    static final int BRIEF = 40;

    protected AiProneBase(Body mob, BooleanSupplier proneOn) {
        this.mob = mob;
        this.proneOn = proneOn;
    }

    public boolean lying() {
        return mob.stance() == Stance.PRONE;
    }

    /** the mind wants it lying this tick */
    public void want() {
        want = true;
    }

    /** down less than MIN_LIE: the mind lets it lie its time out rather than send it off */
    public boolean fresh(long tick) {
        return lying() && tick - changed < MIN_LIE;
    }

    /** standing, and up long enough to shoot */
    public boolean upright(long tick) {
        return !lying() && tick - changed >= RISE;
    }

    /** under fire: its scale at PIN or over (with the scale off: shots flew close past it lately) */
    public boolean pinned(long tick) {
        return tick < pinnedUntil || under;
    }

    /** the stands: pinned down from now, as by shots close past it */
    void pin(long tick) {
        pinnedUntil = tick + PINNED;
    }

    /**
     * Lies down if the mind asked this tick and it may, else gets up where there is room, but not within MIN_LIE of
     * going down unless the mind sends it running; then the speed (crawling, running low past low cover: AiStoop; a
     * slide's or a roll's, AiDash.share).
     */
    void tick(WorldView level, Stride mover, long tick, boolean stooped, AiDashBase dash) {
        // not down in the middle of a slide or a roll: it is crouched for it (pose-contract §4)
        if (dash.on(tick)) want = false;
        // down BRIEF ticks at least whatever the mind wants (it crawls meanwhile), MIN_LIE unless sent running
        boolean hold = lying() && (tick - changed < BRIEF || tick - changed < MIN_LIE && !mover.wantsRun());
        // a fresh lie holds while a hit lifts it off the ground a moment
        boolean lie = want && may(mob, proneOn.getAsBoolean()) || hold && mob.alive() && !mob.passenger() && !mob.inWater();
        want = false;
        if (lie != lying() && (lie || roomToStand(level))) {
            if (!lie && tick - changed < MIN_LIE && !mover.wantsRun()) shortLies++;
            if (!lie && tick - changed < BRIEF) briefLies++;
            CONTROL.stance(mob, lie ? Stance.PRONE : Stance.STAND);
            changed = tick;
            if (lie) downs++;
        }
        mover.slow(lying() ? CRAWL_SPEED : (stooped ? Stride.STOOP : 1.0) * dash.share(tick));
    }

    /** on the terms a player lies down on ({@code proneOn}: the server's prone key, 26.2's Prone.enabled) */
    static boolean may(Body mob, boolean proneOn) {
        return proneOn && mob.alive() && mob.onGround() && !mob.inWater() && !mob.passenger() && !mob.sleeping();
    }

    /**
     * Room for its type's standing box where it lies: the game's level.noCollision(mob,
     * getType().getDimensions().makeBoundingBox(position()).deflate(1.0E-7)), the deflate by AABB's own steps (inflate by
     * the negative, each axis's lesser end first)
     */
    private boolean roomToStand(WorldView level) {
        double[] b = new double[6];
        mob.typeBox(b);
        double v = -1.0E-7;
        double x0 = b[0] - v, y0 = b[1] - v, z0 = b[2] - v, x1 = b[3] + v, y1 = b[4] + v, z1 = b[5] + v;
        return level.free(mob, Math.min(x0, x1), Math.min(y0, y1), Math.min(z0, z1), Math.max(x0, x1), Math.max(y0, y1), Math.max(z0, z1));
    }

    /**
     * A shot passed `d` blocks from its eyes at `speed` blocks a tick (AiUnderFire): within NEAR a near miss, its shooter
     * kept for {@link #watchShots}; with the scale on, the pass a player's scale would count.
     */
    void pass(@Nullable Body owner, double d, double speed, boolean scaleOn, long tick) {
        if (d < NEAR) {
            if (misses.size() >= MISSES) misses.dequeueLong();
            misses.enqueue(tick);
            nearMisses++;
            if (owner != null) missBy = owner;
            newMiss = true;
        }
        if (scaleOn) feel(SuppressionScale.pass(d, speed, SuppressionSettings.passRange()) * SuppressionSettings.passShare(), true, tick);
    }

    /** an event of this force on its scale (AiUnderFire: a pass, a shot stopping near, a blast); burst: a pass or a hit */
    void feel(float force, boolean burst, long tick) {
        scale.add(force, burst, tick);
    }

    /**
     * Each tick of the mob: its scale's tick, and the near misses since the last (AiUnderFire tells them as the shots
     * fly). With the scale off, PINNING near misses within WINDOW pin it for PINNED. Returns who fired the last new
     * near miss, or null.
     */
    @Nullable Body watchShots(long tick) {
        boolean on = AiUnderFireBase.on();
        if (on) scale.tick(SuppressionScale.MAX / (SuppressionSettings.recoverySeconds() * 20F));
        else if (scale.target() > 0F || scale.level() > 0F) scale.clear();
        under = on && (scale.level() >= PIN || under && scale.level() >= UNPIN);
        while (!misses.isEmpty() && tick - misses.firstLong() > WINDOW) misses.dequeueLong();
        if (!on && misses.size() >= PINNING && newMiss) pinnedUntil = tick + PINNED;
        Body shooter = newMiss ? missBy : null;
        newMiss = false;
        missBy = null;
        return shooter;
    }

    /** its scale as a share, 0 to 1 (0 with the scale off) */
    public float suppressed() {
        return scale.share();
    }
}
