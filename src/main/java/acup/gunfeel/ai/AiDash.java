package acup.gunfeel.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A slide into cover and a roll under fire, on a run to cover (notes: pose-contract §4). The slide: the last blocks to
 * low cover, running fast, the run dying away over the slide; the roll: a shot close past it on a run in the open, a
 * forward roll at half its step. Either lasts {@link #TICKS}, crouched from its first tick (the crouch's box and eye:
 * the server's eye and muzzle are the crouch's, AiPoseGeometry by the stance), with no shot; the client plays it on the
 * whole body (BotPoser), the head off the eye for those ticks only. Not again within {@link #AGAIN}. Server thread.
 */
public final class AiDash {
    /** ticks a slide or a roll lasts (AiPose.actionTicks) */
    public static final int TICKS = 16;
    /** ticks from one to the next at least */
    static final int AGAIN = 100;
    /** the slide starts this far from the cover's middle, blocks, straight on flat ground */
    static final double SLIDE_NEAR = 2.4, SLIDE_FAR = 3.0;
    /** the roll wants this much flat straight ground ahead, blocks (half a run's step for TICKS: 2.6) */
    static final double ROLL_AHEAD = 3.0;
    /** only at a run: its step, blocks a tick (the AI's run is 0.32, run low past low cover 0.24: AiStoop; its walk 0.19) */
    static final double FAST = 0.22;
    /** a shot past it within this many ticks may make it roll (a second, as AiProne's pinning window) */
    static final int NEAR_MISS = AiProne.WINDOW;
    /** the share of the chances it takes (a slide, a roll), so not every run ends in one */
    static final float SLIDE_SHARE = 0.6F, ROLL_SHARE = 0.5F;
    /** the roll's step, a share of the run's (AiMover.slow) */
    static final double ROLL_STEP = 0.5;
    /** off only for the stands' "before" */
    static boolean on = true;

    private byte kind = AiPose.NONE;
    private long since = Long.MIN_VALUE / 2, nearMiss = Long.MIN_VALUE / 2;
    /** where it was when last seen on a run, and when */
    private double lastX, lastZ;
    private long lastAt = Long.MIN_VALUE;
    /** the cover and the near miss its chance was last taken for: one chance an approach, one a shot */
    private long slideChanceFor = Long.MIN_VALUE, rollChanceFor = Long.MIN_VALUE;
    /** for the stands */
    public int slides, rolls;

    /** the slide or roll under way this tick, or AiPose.NONE */
    public byte kind(long tick) {
        return tick - since < TICKS ? kind : AiPose.NONE;
    }

    /** the tick it began */
    public long since() {
        return since;
    }

    /** under way this tick: crouched, no shot */
    public boolean on(long tick) {
        return kind(tick) != AiPose.NONE;
    }

    /** a shot flew close past it this tick (AiProne.watchShots) */
    void nearMiss(long tick) {
        nearMiss = tick;
    }

    /**
     * Its step's share this tick (AiMover.slow): a slide's step dies away to a tenth over it (≈2.8 blocks from a run), a
     * roll goes at {@link #ROLL_STEP}; 1 with none.
     */
    double share(long tick) {
        byte k = kind(tick);
        if (k == AiPose.ROLL) return ROLL_STEP;
        if (k != AiPose.SLIDE) return 1.0;
        // the share is of the step (AiMover.modifier takes its root): the step dies away straight
        return 1.0 - 0.9 * (tick - since) / (double) TICKS;
    }

    /**
     * On a run to cover ({@code goal} its middle): slides if near and low cover, rolls if a shot just passed it and the
     * ground ahead lets it. Returns the kind begun, or NONE.
     */
    byte consider(ServerLevel level, Mob mob, long cover, Vec3 goal, boolean lowCover, boolean busy, long tick) {
        Vec3 at = mob.position();
        // away from the cover again: the next approach has its chance
        if (Math.hypot(goal.x - at.x, goal.z - at.z) > SLIDE_FAR + 0.5) slideChanceFor = Long.MIN_VALUE;
        // its step from where it was the tick before (its motion is not the game's: the mob's own deltaMovement and
        // xo are no use to the brain), none if not seen then
        Vec3 step = lastAt == tick - 1 ? new Vec3(at.x - lastX, 0, at.z - lastZ) : Vec3.ZERO;
        lastX = at.x;
        lastZ = at.z;
        lastAt = tick;
        if (!on || busy || on(tick) || tick - since < AGAIN || !mob.onGround() || mob.isInWater() || mob.isPassenger()) return AiPose.NONE;
        double speed = step.horizontalDistance();
        if (speed < FAST) return AiPose.NONE;
        double dx = goal.x - at.x, dz = goal.z - at.z, dist = Math.sqrt(dx * dx + dz * dz);
        byte k = AiPose.NONE;
        if (lowCover && dist >= SLIDE_NEAR && dist <= SLIDE_FAR && Math.abs(goal.y - at.y) < 0.5 && heading(step, dx, dz)
                && flat(level, mob, dx / dist, dz / dist, dist) && chance(true, cover, SLIDE_SHARE, mob))
            k = AiPose.SLIDE;
        else if (tick - nearMiss <= NEAR_MISS && dist >= ROLL_AHEAD + 1.0 && flat(level, mob, step.x / speed, step.z / speed, ROLL_AHEAD)
                && chance(false, nearMiss, ROLL_SHARE, mob))
            k = AiPose.ROLL;
        if (k != AiPose.NONE) begin(k, tick);
        return k;
    }

    /**
     * Its chance taken once an approach to this cover (a slide) or once for this near miss (a roll), at the first tick
     * it may: the share is of approaches and shots, not of the ticks it might (a few for a slide)
     */
    private boolean chance(boolean slide, long key, float share, Mob mob) {
        if ((slide ? slideChanceFor : rollChanceFor) == key) return false;
        if (slide) slideChanceFor = key;
        else rollChanceFor = key;
        return mob.getRandom().nextFloat() < share;
    }

    /** a slide or a roll from this tick on */
    void begin(byte k, long tick) {
        kind = k;
        since = tick;
        if (k == AiPose.SLIDE) slides++;
        else rolls++;
    }

    /** it runs toward the goal (within 30°): a slide goes on the way it runs */
    static boolean heading(Vec3 step, double dx, double dz) {
        double s = Math.sqrt(step.x * step.x + step.z * step.z), d = Math.sqrt(dx * dx + dz * dz);
        return s > 1e-6 && d > 1e-6 && (step.x * dx + step.z * dz) / (s * d) >= Mth.cos(30F * Mth.DEG_TO_RAD);
    }

    /**
     * The ground {@code length} blocks along (x, z) from it is flat: room for its crouching box at each half block, and
     * ground under each (no step up, no drop).
     */
    static boolean flat(ServerLevel level, Mob mob, double x, double z, double length) {
        AABB box = AiPose.CROUCH_SIZE.makeBoundingBox(mob.position()).deflate(1.0E-7);
        for (double d = 0.5; d <= length + 1e-6; d += 0.5) {
            AABB b = box.move(x * d, 0, z * d);
            if (!level.noCollision(mob, b)) return false;
            AABB under = new AABB(b.minX, b.minY - 0.2, b.minZ, b.maxX, b.minY - 0.01, b.maxZ);
            if (level.noCollision(mob, under)) return false;
        }
        return true;
    }
}
