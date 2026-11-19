package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.PosLong;
import acup.gunfeel.Vec3d;
import acup.gunfeel.host.Body;

/**
 * Running low: on its way to cover, where something low stands between it and the threat it runs from (the threat
 * would see a standing mob's head and chest, but not a crouching one's chest), it crouches and goes slower, so only
 * its head shows. In the open, or out of sight anyway, it runs upright: down low there it would only be slower under
 * fire. Two rays a cell it steps into. Server thread.
 * The mob is its Body, the threat's eye a Vec3d (26.2's AiStoop passes its own).
 */
public class AiStoopBase {
    /** its speed crouched, a share of its run's step (AiMover.slow) */
    static final double SHARE = Stride.STOOP;
    /** the standing head and a crouching mob's chest (AiPose.CROUCH_SIZE, 1.5 high), above its feet */
    static final double HEAD = 1.6, LOW_CHEST = 0.85;
    /** off only for the stands' "before" */
    static boolean on = true;
    /** how far the threat moves before its eye is taken again, blocks (each new eye costs the rays of the cell again) */
    static final double FOLLOW = 2;
    /** ticks it stays low after the last cell that wanted it: no up and down on the edge of the cover */
    static final int HOLD = 8;

    private @Nullable Vec3d from;
    private long cell = Long.MIN_VALUE;
    private boolean low;
    private long lowUntil = Long.MIN_VALUE;
    /** this tick's answer */
    boolean down;
    /** for the stands: ticks it ran low */
    public int ticks;

    /** the eye of the threat it runs from; null: none (it runs upright) */
    void fromEye(@Nullable Vec3d eye) {
        if (eye == null ? from == null : eye.equals(from)) return;
        from = eye;
        cell = Long.MIN_VALUE;
    }

    /** the threat it runs from, where it is now: taken if it moved FOLLOW blocks or more */
    void followEye(Vec3d eye) {
        Vec3d f = from;
        if (f != null && f.distanceToSqr(eye) >= FOLLOW * FOLLOW) fromEye(eye);
    }

    /**
     * Whether it runs low this tick: on its way somewhere at a run, upright, with low cover from the threat here or
     * within the last {@link #HOLD} ticks.
     */
    boolean want(Body mob, Stride mover, boolean lying, VoxelRay.Source voxels, long tick) {
        Vec3d f = from;
        down = false;
        if (!on || f == null || lying || !mover.wantsRun()) {
            lowUntil = Long.MIN_VALUE;
            return false;
        }
        int x = mob.blockX(), y = mob.blockY(), z = mob.blockZ();
        long k = PosLong.block(x, y, z);
        if (k != cell) {
            cell = k;
            low = VoxelRay.clear(voxels, f.x(), f.y(), f.z(), x + 0.5, y + HEAD, z + 0.5, Voxel.SIGHT)
                    && !VoxelRay.clear(voxels, f.x(), f.y(), f.z(), x + 0.5, y + LOW_CHEST, z + 0.5, Voxel.SIGHT);
        }
        if (low) lowUntil = tick + HOLD;
        down = tick < lowUntil;
        if (down) ticks++;
        return down;
    }
}
