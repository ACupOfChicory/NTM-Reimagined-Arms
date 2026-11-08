package acup.gunfeel.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Where a target that went out of sight may come out again (notes: research §5.1, the owner's "half wait where I went,
 * half where I may come out"): walking out from where it was lost, the way it was going first, the first cell of the
 * tactical map the mob sees from where it watches after cells it does not (the edge of what it sees, where the target
 * steps back into sight), a few blocks off; the walk may start in sight for {@link #PREFIX}. Cells only: a wall ends
 * the walk that way. Server thread (rays over the level's voxels), {@link #PER_TICK} picks a tick for all mobs.
 */
public final class ExitSpots {
    /** how far from where it was lost, blocks */
    static final double MIN = 3, MAX = 16;
    /** the ways tried round from the one it was going */
    static final int WAYS = 16;
    /** its motion counts as a way it was going from this speed, blocks a tick */
    static final double MOVING = 0.03;
    /**
     * the walk may start in sight this far (the watcher's eye out at its peek may see where the target was lost, which
     * it saw last from its cover, and a little past), blocks
     */
    static final double PREFIX = 3;
    /** rays spent at most, and on one way at most */
    static final int RAYS = 120, RAYS_WAY = 20;
    /** picks a tick at most, for all mobs (a crowd losing one target at once) */
    static final int PER_TICK = 4;
    private static final int[] STEPS = {0, 1, -1, -2};
    private static long pickTick = Long.MIN_VALUE;
    private static int picks;

    private ExitSpots() {
    }

    /** one more pick may be made this tick (server thread); if not, the mob asks again the next tick */
    static boolean mayPick(long tick) {
        if (tick != pickTick) {
            pickTick = tick;
            picks = 0;
        }
        if (picks >= PER_TICK) return false;
        picks++;
        return true;
    }

    /**
     * The exit for a mob watching from {@code eye} for a target lost at (lx, ly, lz) (feet) going (vx, vz), as a
     * BlockPos long, or Long.MIN_VALUE if none is found within the rays.
     */
    public static long pick(CellSource map, VoxelRay.Source voxels, Vec3 eye, double lx, double ly, double lz, double vx, double vz,
            RandomSource rng) {
        double speed = Math.sqrt(vx * vx + vz * vz);
        // the ways in turn: the way it was going, then nearer it first, side by side; not going anywhere: any way first
        double first = speed >= MOVING ? Math.atan2(vz, vx) : rng.nextDouble() * 2 * Math.PI;
        int side = rng.nextBoolean() ? 1 : -1;
        int rays = RAYS;
        for (int k = 0; k < WAYS; k++) {
            int turn = (k + 1) / 2 * (k % 2 == 1 ? side : -side);
            double a = first + turn * 2 * Math.PI / WAYS, ax = Math.cos(a), az = Math.sin(a);
            int y = (int) Math.floor(ly + 0.01);
            boolean hidden = false;
            int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE, way = RAYS_WAY;
            for (double d = 1; d <= MAX && way > 0; d += 0.7) {
                int x = (int) Math.floor(lx + ax * d), z = (int) Math.floor(lz + az * d);
                if (x == lastX && z == lastZ) continue;
                lastX = x;
                lastZ = z;
                int at = step(map, x, y, z);
                // no cell to stand in: it cannot go on that way
                if (at == Integer.MIN_VALUE) break;
                y = at;
                if (rays-- <= 0) return Long.MIN_VALUE;
                way--;
                boolean seen = VoxelRay.clear(voxels, eye.x, eye.y, eye.z, x + 0.5, y + 1.0, z + 0.5, Voxel.SIGHT);
                if (!seen) hidden = true;
                else if (hidden && d >= MIN) return BlockPos.asLong(x, y, z);
                // in sight past the start that way: it would have been seen going there; no exit this way
                else if (!hidden && d > PREFIX) break;
            }
        }
        return Long.MIN_VALUE;
    }

    /** the feet of a cell at x z one up, level, one or two down from y, or Integer.MIN_VALUE */
    private static int step(CellSource map, int x, int y, int z) {
        for (int dy : STEPS)
            if (has(map, x, y + dy, z)) return y + dy;
        return Integer.MIN_VALUE;
    }

    static boolean has(CellSource map, int x, int y, int z) {
        SectionCells c = map.cells(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
        return c != null && c.find(x & 15, y & 15, z & 15) >= 0;
    }

    /**
     * A target standing in the cell is in plain sight from the eye (its chest), and one step from it back towards
     * where it was lost it is not: the cell is where it comes back into sight
     */
    static boolean edge(VoxelRay.Source voxels, Vec3 eye, long cell, double lx, double lz) {
        int x = BlockPos.getX(cell), y = BlockPos.getY(cell), z = BlockPos.getZ(cell);
        if (!VoxelRay.clear(voxels, eye.x, eye.y, eye.z, x + 0.5, y + 1.0, z + 0.5, Voxel.SIGHT)) return false;
        double bx = lx - (x + 0.5), bz = lz - (z + 0.5), b = Math.sqrt(bx * bx + bz * bz);
        if (b < 1e-6) return false;
        int nx = x + (int) Math.round(bx / b), nz = z + (int) Math.round(bz / b);
        return !VoxelRay.clear(voxels, eye.x, eye.y, eye.z, nx + 0.5, y + 1.0, nz + 0.5, Voxel.SIGHT);
    }
}
