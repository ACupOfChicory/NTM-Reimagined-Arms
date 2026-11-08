package acup.gunfeel.ai;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * An AI mob lying down, on the same terms as a player (Prone: the server's prone key; on the ground, out of water, not
 * riding): the crawling pose, a crawling player's size (MixinAiProneSize), laid down on the client
 * (MixinAiProneRender), a third of the speed, and the spread Spread gives anyone crawling (spreadCrawl). The mind asks
 * for it each tick it wants it ({@link #want}), and only to fire lying or for a long reload; once down it stays down
 * {@link #MIN_LIE} ticks, unless its mind sends it running; getting up takes {@link #RISE} ticks before a shot standing.
 *
 * <p>It also counts the shots that fly past it ({@link #watchShots}): three within a second pin it down for three.
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
    /** how far out it looks for shots in flight (a bullet flies 10 blocks a tick) */
    static final double WATCH = 12;

    private final Mob mob;
    private boolean want;
    private long changed = Long.MIN_VALUE / 2;
    private final LongArrayFIFOQueue misses = new LongArrayFIFOQueue();
    private final IntOpenHashSet seen = new IntOpenHashSet();
    private long seenSince, pinnedUntil = Long.MIN_VALUE;
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

    /** shots flew past it lately */
    public boolean pinned(long tick) {
        return tick < pinnedUntil;
    }

    /** the stands: pinned down from now, as by shots close past it */
    void pin(long tick) {
        pinnedUntil = tick + PINNED;
    }

    /**
     * Lies down if the mind asked this tick and it may, else gets up where there is room, but not within MIN_LIE of
     * going down unless the mind sends it running; then the speed (crawling, running low past low cover: AiStoop).
     */
    void tick(ServerLevel level, AiMover mover, long tick, boolean stooped) {
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
        mover.slow(lying() ? CRAWL_SPEED : stooped ? AiStoop.SHARE : 1.0);
    }

    /** on the terms a player lies down on */
    static boolean may(Mob mob) {
        return acup.gunfeel.Prone.enabled() && mob.isAlive() && mob.onGround() && !mob.isInWater() && !mob.isPassenger() && !mob.isSleeping();
    }

    private boolean roomToStand(ServerLevel level) {
        return level.noCollision(mob, mob.getType().getDimensions().makeBoundingBox(mob.position()).deflate(1.0E-7));
    }

    /**
     * Shots in flight round it this tick (not its own, not its own side's, AiTeams, not a creative or spectating
     * player's): one whose step this tick passes within NEAR of its chest is a near miss, counted once. Returns who
     * fired the last new near miss this tick, or null.
     */
    @org.jspecify.annotations.Nullable Entity watchShots(ServerLevel level, long tick) {
        Entity shooter = null;
        if (tick - seenSince > 200) {
            seen.clear();
            seenSince = tick;
        }
        Vec3 c = mob.position().add(0, mob.getBbHeight() * 0.6, 0);
        for (Projectile p : level.getEntitiesOfClass(Projectile.class, mob.getBoundingBox().inflate(WATCH), Entity::isAlive)) {
            Entity owner = p.getOwner();
            if (owner == mob || owner instanceof LivingEntity o && AiTeams.own(mob, o)
                    || owner instanceof net.minecraft.world.entity.player.Player pl && (pl.isCreative() || pl.isSpectator())) continue;
            Vec3 a = p.position(), v = p.getDeltaMovement();
            double vv = v.lengthSqr();
            if (vv < 0.25) continue;
            double t = Mth.clamp(c.subtract(a).dot(v) / vv, 0, 1);
            if (a.add(v.scale(t)).distanceToSqr(c) < NEAR * NEAR && seen.add(p.getId())) {
                misses.enqueue(tick);
                nearMisses++;
                if (owner != null) shooter = owner;
            }
        }
        while (!misses.isEmpty() && tick - misses.firstLong() > WINDOW) misses.dequeueLong();
        if (misses.size() >= PINNING) pinnedUntil = tick + PINNED;
        return shooter;
    }
}
