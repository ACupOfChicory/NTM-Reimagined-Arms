package acup.gunfeel.ai;

import acup.gunfeel.GameMath;
import acup.gunfeel.Vec3d;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.WorldView;

/**
 * A slide into cover and a roll under fire, on a run to cover (notes: pose-contract §4). The slide: the last blocks to
 * low cover, running fast, the run dying away over the slide; the roll: a shot close past it on a run in the open, a
 * forward roll at half its step. Either lasts {@link #TICKS}, crouched from its first tick (the crouch's box and eye:
 * the server's eye and muzzle are the crouch's, AiPoseGeometry by the stance), with no shot; the client plays it on the
 * whole body (BotPoser), the head off the eye for those ticks only. Not again within {@link #AGAIN}. Server thread.
 * The mob and its level come as its Body and the level's WorldView (26.2's AiDash passes its own).
 */
public class AiDashBase {
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
    /** a shot past it within this many ticks may make it roll (a second, as AiProne's pinning window, AiProne.WINDOW) */
    static final int NEAR_MISS = 20;
    /** the share of the chances it takes (a slide, a roll), so not every run ends in one */
    static final float SLIDE_SHARE = 0.6F, ROLL_SHARE = 0.5F;
    /** the roll's step, a share of the run's (AiMover.slow) */
    static final double ROLL_STEP = 0.5;

    private byte kind = PoseAction.NONE;
    private long since = Long.MIN_VALUE / 2, nearMiss = Long.MIN_VALUE / 2;
    /** where it was when last seen on a run, and when */
    private double lastX, lastZ;
    private long lastAt = Long.MIN_VALUE;
    /** the cover and the near miss its chance was last taken for: one chance an approach, one a shot */
    private long slideChanceFor = Long.MIN_VALUE, rollChanceFor = Long.MIN_VALUE;
    /** for the stands */
    public int slides, rolls;

    /** the slide or roll under way this tick, or PoseAction.NONE */
    public byte kind(long tick) {
        return tick - since < TICKS ? kind : PoseAction.NONE;
    }

    /** the tick it began */
    public long since() {
        return since;
    }

    /** under way this tick: crouched, no shot */
    public boolean on(long tick) {
        return kind(tick) != PoseAction.NONE;
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
        if (k == PoseAction.ROLL) return ROLL_STEP;
        if (k != PoseAction.SLIDE) return 1.0;
        // the share is of the step (AiMover.modifier takes its root): the step dies away straight
        return 1.0 - 0.9 * (tick - since) / (double) TICKS;
    }

    /**
     * On a run to cover ({@code goal} its middle): slides if near and low cover, rolls if a shot just passed it and the
     * ground ahead lets it. Returns the kind begun, or NONE.
     */
    byte consider(WorldView level, Body mob, long cover, double goalX, double goalY, double goalZ, boolean lowCover, boolean busy, long tick) {
        double atX = mob.x(), atY = mob.y(), atZ = mob.z();
        // away from the cover again: the next approach has its chance
        if (Math.hypot(goalX - atX, goalZ - atZ) > SLIDE_FAR + 0.5) slideChanceFor = Long.MIN_VALUE;
        // its step from where it was the tick before (its motion is not the game's: the mob's own deltaMovement and
        // xo are no use to the brain), none if not seen then
        Vec3d step = lastAt == tick - 1 ? new Vec3d(atX - lastX, 0, atZ - lastZ) : Vec3d.ZERO;
        lastX = atX;
        lastZ = atZ;
        lastAt = tick;
        if (busy || on(tick) || tick - since < AGAIN || !mob.onGround() || mob.inWater() || mob.passenger()) return PoseAction.NONE;
        double speed = step.horizontalDistance();
        if (speed < FAST) return PoseAction.NONE;
        double dx = goalX - atX, dz = goalZ - atZ, dist = Math.sqrt(dx * dx + dz * dz);
        byte k = PoseAction.NONE;
        if (lowCover && dist >= SLIDE_NEAR && dist <= SLIDE_FAR && Math.abs(goalY - atY) < 0.5 && heading(step, dx, dz)
                && flat(level, mob, dx / dist, dz / dist, dist) && chance(true, cover, SLIDE_SHARE, mob))
            k = PoseAction.SLIDE;
        else if (tick - nearMiss <= NEAR_MISS && dist >= ROLL_AHEAD + 1.0 && flat(level, mob, step.x() / speed, step.z() / speed, ROLL_AHEAD)
                && chance(false, nearMiss, ROLL_SHARE, mob))
            k = PoseAction.ROLL;
        if (k != PoseAction.NONE) begin(k, tick);
        return k;
    }

    /**
     * Its chance taken once an approach to this cover (a slide) or once for this near miss (a roll), at the first tick
     * it may: the share is of approaches and shots, not of the ticks it might (a few for a slide)
     */
    private boolean chance(boolean slide, long key, float share, Body mob) {
        if ((slide ? slideChanceFor : rollChanceFor) == key) return false;
        if (slide) slideChanceFor = key;
        else rollChanceFor = key;
        return mob.random().nextFloat() < share;
    }

    /** a slide or a roll from this tick on */
    void begin(byte k, long tick) {
        kind = k;
        since = tick;
        if (k == PoseAction.SLIDE) slides++;
        else rolls++;
    }

    /** it runs toward the goal (within 30°): a slide goes on the way it runs */
    static boolean heading(Vec3d step, double dx, double dz) {
        double s = Math.sqrt(step.x() * step.x() + step.z() * step.z()), d = Math.sqrt(dx * dx + dz * dz);
        return s > 1e-6 && d > 1e-6 && (step.x() * dx + step.z() * dz) / (s * d) >= GameMath.cos(30F * GameMath.DEG_TO_RAD);
    }

    /**
     * Its crouching box at its feet, shrunk by 1e-7 on every side, into out (min x, y, z, max x, y, z): the game's
     * AiPose.CROUCH_SIZE.makeBoundingBox(position).deflate(1.0E-7), by the same steps (EntityDimensions.makeBoundingBox:
     * half the width as a float; AABB keeps min and max; deflate is inflate by the negative)
     */
    static void crouchBox(double x, double y, double z, double[] out) {
        float f = Stance.CROUCH_WIDTH / 2F, h = Stance.CROUCH_HEIGHT;
        box(x - (double) f, y, z - (double) f, x + (double) f, y + (double) h, z + (double) f, out);
        double v = -1.0E-7;
        box(out[0] - v, out[1] - v, out[2] - v, out[3] + v, out[4] + v, out[5] + v, out);
    }

    /** the game's new AABB(x1, y1, z1, x2, y2, z2): each axis's lesser end first */
    private static void box(double x1, double y1, double z1, double x2, double y2, double z2, double[] out) {
        out[0] = Math.min(x1, x2);
        out[1] = Math.min(y1, y2);
        out[2] = Math.min(z1, z2);
        out[3] = Math.max(x1, x2);
        out[4] = Math.max(y1, y2);
        out[5] = Math.max(z1, z2);
    }

    /**
     * The ground {@code length} blocks along (x, z) from it is flat: room for its crouching box at each half block, and
     * ground under each (no step up, no drop).
     */
    static boolean flat(WorldView level, Body mob, double x, double z, double length) {
        double[] box = new double[6], b = new double[6];
        crouchBox(mob.x(), mob.y(), mob.z(), box);
        for (double d = 0.5; d <= length + 1e-6; d += 0.5) {
            double mx = x * d, mz = z * d;
            box(box[0] + mx, box[1] + 0.0, box[2] + mz, box[3] + mx, box[4] + 0.0, box[5] + mz, b);
            if (!level.free(mob, b[0], b[1], b[2], b[3], b[4], b[5])) return false;
            double[] under = new double[6];
            box(b[0], b[1] - 0.2, b[2], b[3], b[1] - 0.01, b[5], under);
            if (level.free(mob, under[0], under[1], under[2], under[3], under[4], under[5])) return false;
        }
        return true;
    }
}
