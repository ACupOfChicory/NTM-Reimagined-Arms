package acup.gunfeel.ai;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * An AI mob lying down, on the same terms as a player (Prone: the server's prone key; on the ground, out of water, not
 * riding): the crawling pose, a crawling player's size (MixinAiProneSize), laid down on the client
 * (MixinAiProneRender), a third of the speed, and the spread Spread gives anyone crawling (spreadCrawl). The mind asks
 * for it each tick it wants it ({@link #want}); getting up takes {@link #RISE} ticks before a shot standing.
 *
 * <p>It also counts the shots that fly past it ({@link #watchShots}): three within a second pin it down for three.
 */
public final class AiProne {
    /** a crawling player's size (Player's crawling pose) */
    public static final EntityDimensions SIZE = EntityDimensions.scalable(0.6F, 0.6F).withEyeHeight(0.4F);
    /** ticks from getting up to a shot standing */
    static final int RISE = 6;
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
    // for the stands
    public int downs, nearMisses;

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

    /** standing, and up long enough to shoot */
    public boolean upright(long tick) {
        return !lying() && tick - changed >= RISE;
    }

    /** shots flew past it lately */
    public boolean pinned(long tick) {
        return tick < pinnedUntil;
    }

    /** lies down if the mind asked this tick and it may, else gets up where there is room; then the speed */
    void tick(ServerLevel level, AiMover mover, long tick) {
        boolean lie = want && may(mob);
        want = false;
        if (lie != lying() && (lie || roomToStand(level))) {
            mob.setPose(lie ? Pose.SWIMMING : Pose.STANDING);
            changed = tick;
            if (lie) downs++;
        }
        mover.slow(lying() ? CRAWL_SPEED : 1.0);
    }

    /** on the terms a player lies down on */
    static boolean may(Mob mob) {
        return acup.gunfeel.Prone.enabled() && mob.isAlive() && mob.onGround() && !mob.isInWater() && !mob.isPassenger() && !mob.isSleeping();
    }

    private boolean roomToStand(ServerLevel level) {
        return level.noCollision(mob, mob.getType().getDimensions().makeBoundingBox(mob.position()).deflate(1.0E-7));
    }

    /**
     * Shots in flight round it this tick (not its own, not another AI mob's): one whose step this tick passes within
     * NEAR of its chest is a near miss, counted once.
     */
    void watchShots(ServerLevel level, long tick) {
        if (tick - seenSince > 200) {
            seen.clear();
            seenSince = tick;
        }
        Vec3 c = mob.position().add(0, mob.getBbHeight() * 0.6, 0);
        for (Projectile p : level.getEntitiesOfClass(Projectile.class, mob.getBoundingBox().inflate(WATCH), Entity::isAlive)) {
            Entity owner = p.getOwner();
            if (owner == mob || owner instanceof Mob m && AiBots.get(m) != null) continue;
            Vec3 a = p.position(), v = p.getDeltaMovement();
            double vv = v.lengthSqr();
            if (vv < 0.25) continue;
            double t = Mth.clamp(c.subtract(a).dot(v) / vv, 0, 1);
            if (a.add(v.scale(t)).distanceToSqr(c) < NEAR * NEAR && seen.add(p.getId())) {
                misses.enqueue(tick);
                nearMisses++;
            }
        }
        while (!misses.isEmpty() && tick - misses.firstLong() > WINDOW) misses.dequeueLong();
        if (misses.size() >= PINNING) pinnedUntil = tick + PINNED;
    }
}
