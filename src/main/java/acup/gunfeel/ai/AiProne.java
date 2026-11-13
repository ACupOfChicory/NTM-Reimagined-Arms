package acup.gunfeel.ai;

import acup.gunfeel.SuppressionScale;
import acup.gunfeel.SuppressionServer;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;

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
 */
public final class AiProne {
    /** a crawling player's size (Player's crawling pose) */
    public static final EntityDimensions SIZE = EntityDimensions.scalable(0.6F, 0.6F).withEyeHeight(0.4F);
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

    private final Mob mob;
    private boolean want;
    private long changed = Long.MIN_VALUE / 2;
    private final LongArrayFIFOQueue misses = new LongArrayFIFOQueue();
    /** its suppression: the players' scale, fed by AiUnderFire */
    private final SuppressionScale scale = new SuppressionScale();
    private @org.jspecify.annotations.Nullable Entity missBy;
    private boolean newMiss, under;
    private long pinnedUntil = Long.MIN_VALUE;
    /** for the stands: shortLies, up within MIN_LIE of going down, not sent running; briefLies, down less than BRIEF */
    public int downs, nearMisses, shortLies, briefLies;
    static final int BRIEF = 40;

    AiProne(Mob mob) {
        this.mob = mob;
    }

    public boolean lying() {
        return mob.getPose() == Pose.SWIMMING;
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
    void tick(ServerLevel level, AiMover mover, long tick, boolean stooped, AiDash dash) {
        // not down in the middle of a slide or a roll: it is crouched for it (pose-contract §4)
        if (dash.on(tick)) want = false;
        // down BRIEF ticks at least whatever the mind wants (it crawls meanwhile), MIN_LIE unless sent running
        boolean hold = lying() && (tick - changed < BRIEF || tick - changed < MIN_LIE && !mover.wantsRun());
        // a fresh lie holds while a hit lifts it off the ground a moment
        boolean lie = want && may(mob) || hold && mob.isAlive() && !mob.isPassenger() && !mob.isInWater();
        want = false;
        if (lie != lying() && (lie || roomToStand(level))) {
            if (!lie && tick - changed < MIN_LIE && !mover.wantsRun()) shortLies++;
            if (!lie && tick - changed < BRIEF) briefLies++;
            mob.setPose(lie ? Pose.SWIMMING : Pose.STANDING);
            changed = tick;
            if (lie) downs++;
        }
        mover.slow(lying() ? CRAWL_SPEED : (stooped ? AiStoop.SHARE : 1.0) * dash.share(tick));
    }

    /** on the terms a player lies down on */
    static boolean may(Mob mob) {
        return acup.gunfeel.Prone.enabled() && mob.isAlive() && mob.onGround() && !mob.isInWater() && !mob.isPassenger() && !mob.isSleeping();
    }

    private boolean roomToStand(ServerLevel level) {
        return level.noCollision(mob, mob.getType().getDimensions().makeBoundingBox(mob.position()).deflate(1.0E-7));
    }

    /**
     * A shot passed `d` blocks from its eyes at `speed` blocks a tick (AiUnderFire): within NEAR a near miss, its shooter
     * kept for {@link #watchShots}; with the scale on, the pass a player's scale would count.
     */
    void pass(@org.jspecify.annotations.Nullable Entity owner, double d, double speed, boolean scaleOn, long tick) {
        if (d < NEAR) {
            if (misses.size() >= MISSES) misses.dequeueLong();
            misses.enqueue(tick);
            nearMisses++;
            if (owner != null) missBy = owner;
            newMiss = true;
        }
        if (scaleOn) feel(SuppressionScale.pass(d, speed, SuppressionServer.passRange()) * SuppressionServer.passShare(), true, tick);
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
    @org.jspecify.annotations.Nullable Entity watchShots(ServerLevel level, long tick) {
        boolean on = AiUnderFire.on();
        if (on) scale.tick(SuppressionScale.MAX / (SuppressionServer.recoverySeconds() * 20F));
        else if (scale.target() > 0F || scale.level() > 0F) scale.clear();
        under = on && (scale.level() >= PIN || under && scale.level() >= UNPIN);
        while (!misses.isEmpty() && tick - misses.firstLong() > WINDOW) misses.dequeueLong();
        if (!on && misses.size() >= PINNING && newMiss) pinnedUntil = tick + PINNED;
        Entity shooter = newMiss ? missBy : null;
        newMiss = false;
        missBy = null;
        return shooter;
    }

    /** its scale as a share, 0 to 1 (0 with the scale off) */
    public float suppressed() {
        return scale.share();
    }
}
