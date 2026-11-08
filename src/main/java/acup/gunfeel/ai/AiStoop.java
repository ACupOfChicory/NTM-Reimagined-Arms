package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Running low: on its way to cover, where something low stands between it and the threat it runs from (the threat
 * would see a standing mob's head and chest, but not a crouching one's chest), it crouches and goes slower, so only
 * its head shows. In the open, or out of sight anyway, it runs upright: down low there it would only be slower under
 * fire. Two rays a cell it steps into. Server thread.
 */
public final class AiStoop {
    /** its speed crouched, a share of its run's step (AiMover.slow) */
    static final double SHARE = 0.75;
    /** the standing head and a crouching mob's chest (AiPose.CROUCH_SIZE, 1.5 high), above its feet */
    static final double HEAD = 1.6, LOW_CHEST = 0.85;
    /** off only for the stands' "before" */
    static boolean on = true;
    /** how far the threat moves before its eye is taken again, blocks (each new eye costs the rays of the cell again) */
    static final double FOLLOW = 2;
    /** ticks it stays low after the last cell that wanted it: no up and down on the edge of the cover */
    static final int HOLD = 8;

    private @Nullable Vec3 from;
    private long cell = Long.MIN_VALUE;
    private boolean low;
    private long lowUntil = Long.MIN_VALUE;
    /** this tick's answer */
    boolean down;
    /** for the stands: ticks it ran low */
    public int ticks;

    /** the eye of the threat it runs from; null: none (it runs upright) */
    void from(@Nullable Vec3 eye) {
        if (eye == null ? from == null : eye.equals(from)) return;
        from = eye;
        cell = Long.MIN_VALUE;
    }

    /** the threat it runs from, where it is now: taken if it moved FOLLOW blocks or more */
    void follow(Vec3 eye) {
        Vec3 f = from;
        if (f != null && f.distanceToSqr(eye) >= FOLLOW * FOLLOW) from(eye);
    }

    /**
     * Whether it runs low this tick: on its way somewhere at a run, upright, with low cover from the threat here or
     * within the last {@link #HOLD} ticks.
     */
    boolean want(Mob mob, AiMover mover, boolean lying, VoxelRay.Source voxels, long tick) {
        Vec3 f = from;
        down = false;
        if (!on || f == null || lying || !mover.wantsRun()) {
            lowUntil = Long.MIN_VALUE;
            return false;
        }
        int x = mob.getBlockX(), y = mob.getBlockY(), z = mob.getBlockZ();
        long k = BlockPos.asLong(x, y, z);
        if (k != cell) {
            cell = k;
            low = VoxelRay.clear(voxels, f.x, f.y, f.z, x + 0.5, y + HEAD, z + 0.5, Voxel.SIGHT)
                    && !VoxelRay.clear(voxels, f.x, f.y, f.z, x + 0.5, y + LOW_CHEST, z + 0.5, Voxel.SIGHT);
        }
        if (low) lowUntil = tick + HOLD;
        down = tick < lowUntil;
        if (down) ticks++;
        return down;
    }
}
