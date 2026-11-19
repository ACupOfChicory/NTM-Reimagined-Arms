package acup.gunfeel.ai;

import acup.gunfeel.GameRandom;
import acup.gunfeel.PosLong;
import acup.gunfeel.Vec3d;

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
     * block key (PosLong.block, as BlockPos.asLong), or Long.MIN_VALUE if none is found within the rays.
     */
    public static long pick(CellSource map, VoxelRay.Source voxels, Vec3d eye, double lx, double ly, double lz, double vx, double vz,
            GameRandom rng) {
        return walk(map, voxels, eye, lx, ly, lz, vx, vz, rng);
    }

    /**
     * The same for one of a squad whose mates watch {@code taken} already: the walk's exit if none of them watches that
     * corner (within {@link #TAKEN_DEG} of it, seen from the eye), else another corner of the cover between the eye and
     * where the target was lost ({@link #corner}); the walk's exit still if there is no other. No exit by the walk (lost
     * behind the middle of a long wall, its ends past the walk's reach): a corner.
     */
    public static long pick(CellSource map, VoxelRay.Source voxels, Vec3d eye, double lx, double ly, double lz, double vx, double vz,
            GameRandom rng, java.util.List<Vec3d> taken) {
        long walked = walk(map, voxels, eye, lx, ly, lz, vx, vz, rng);
        if (walked != Long.MIN_VALUE && !taken(taken, eye, PosLong.blockX(walked), PosLong.blockZ(walked))) return walked;
        java.util.List<Vec3d> more = walked == Long.MIN_VALUE ? taken : new java.util.ArrayList<>(taken);
        if (walked != Long.MIN_VALUE) more.add(new Vec3d(PosLong.blockX(walked) + 0.5, PosLong.blockY(walked) + 1.1, PosLong.blockZ(walked) + 0.5));
        long other = corner(map, voxels, eye, lx, ly, lz, more);
        return other != Long.MIN_VALUE ? other : walked;
    }

    /** an exit one of its own watches is the same corner seen from the eye this near in angle, degrees */
    static final double TAKEN_DEG = 30;

    static boolean taken(java.util.List<Vec3d> taken, Vec3d eye, int x, int z) {
        double ax = x + 0.5 - eye.x(), az = z + 0.5 - eye.z(), a = Math.sqrt(ax * ax + az * az), cos = Math.cos(Math.toRadians(TAKEN_DEG));
        for (Vec3d t : taken) {
            double bx = t.x() - eye.x(), bz = t.z() - eye.z(), b = Math.sqrt(bx * bx + bz * bz);
            if (a < 1e-6 || b < 1e-6 || (ax * bx + az * bz) / (a * b) >= cos) return true;
        }
        return false;
    }

    /**
     * the fan of looks for corners: all round the eye (one in cover against a wall sees that wall's ends along it, far
     * off the way to where the target was lost), a look every so many degrees
     */
    static final double FAN_STEP = 4;
    /** a look this much longer than the one beside it went past the end of the cover the other stopped at, blocks */
    static final double JUMP = 3;
    /** how far past the lost place the looks go, blocks; how far past the cover's edge the exit is looked for along the look */
    static final double FAN_PAST = 12, PAST_EDGE = 4;
    /** the edge between two looks found closer by halving the angle this many times */
    static final int EDGE_HALVES = 6;

    /**
     * A corner of the cover between the eye and where the target was lost, round which it may come out: a fan of looks
     * all round the eye at a target's chest height; where a look goes on JUMP or more past the one beside it, and JUMP
     * past the lost place at least, the shorter one stopped at the cover's end (the edge between them found closer by
     * halves), and the first cell to stand on in plain sight along the longer look just past that end is the exit. Of
     * those, the nearest to the lost place that none of {@code taken} watches (within TAKEN_DEG), else the nearest
     * watched already (a corner twice rather than the cover between); Long.MIN_VALUE if none. 360 / FAN_STEP looks, EDGE_HALVES more an edge, and a few for the cells.
     */
    static long corner(CellSource map, VoxelRay.Source voxels, Vec3d eye, double lx, double ly, double lz, java.util.List<Vec3d> taken) {
        double cy = ly + 1.0, toLost = Math.hypot(lx - eye.x(), lz - eye.z()), reach = toLost + FAN_PAST;
        if (toLost < 1e-3) return Long.MIN_VALUE;
        double base = Math.atan2(lz - eye.z(), lx - eye.x());
        int looks = (int) Math.round(360 / FAN_STEP);
        double[] dist = new double[looks];
        for (int k = 0; k < looks; k++) {
            double a = base + Math.toRadians(k * FAN_STEP);
            double t = VoxelRay.blocked(voxels, eye.x(), eye.y(), eye.z(), eye.x() + Math.cos(a) * reach, cy, eye.z() + Math.sin(a) * reach, Voxel.SIGHT);
            dist[k] = t < 0 ? reach : t * reach;
        }
        long best = Long.MIN_VALUE, bestTaken = Long.MIN_VALUE;
        double bestD = Double.MAX_VALUE, takenD = Double.MAX_VALUE;
        for (int i = 0; i < looks; i++) {
            int j = (i + 1) % looks, far, near;
            if (dist[j] - dist[i] >= JUMP) {
                far = j;
                near = i;
            } else if (dist[i] - dist[j] >= JUMP) {
                far = i;
                near = j;
            } else continue;
            // the cover the near look stopped at stands between the eye and the lost place, not past it, and the far look
            // goes on past it as far as the lost place at least (a look along a wall the eye is against stops at the wall
            // further and further, in jumps, with no end of it between)
            if (dist[near] > toLost + 2 || dist[far] < toLost + JUMP) continue;
            // the edge between the two looks, closer by halves: the look on the near side stops at the cover's very end
            double an = base + Math.toRadians(near * FAN_STEP), af = an + Math.toRadians(far == (near + 1) % looks ? FAN_STEP : -FAN_STEP);
            double dn = dist[near];
            for (int h = 0; h < EDGE_HALVES; h++) {
                double am = (an + af) / 2, t = VoxelRay.blocked(voxels, eye.x(), eye.y(), eye.z(), eye.x() + Math.cos(am) * reach, cy, eye.z() + Math.sin(am) * reach, Voxel.SIGHT);
                double dm = t < 0 ? reach : t * reach;
                // past the cover as the far look is (one along a wall the eye is against stops further and further)
                if (dm > dist[far] - JUMP) af = am;
                else {
                    an = am;
                    dn = dm;
                }
            }
            double a = af;
            // the first cell along the look past the edge (or one beside it, a step nearer the eye: the look grazes the
            // cover, the cell's middle may be just behind it) a target stands on in plain sight
            long found = Long.MIN_VALUE;
            int sx = (int) Math.signum(eye.x() - (eye.x() + Math.cos(a))), sz = (int) Math.signum(eye.z() - (eye.z() + Math.sin(a)));
            for (double d = dn + 0.5; d <= Math.min(dist[far], dn + PAST_EDGE) && found == Long.MIN_VALUE; d += 0.5) {
                int px = (int) Math.floor(eye.x() + Math.cos(a) * d), pz = (int) Math.floor(eye.z() + Math.sin(a) * d);
                int[][] tries = {{px, pz}, {px, pz + sz}, {px + sx, pz}, {px + sx, pz + sz}};
                for (int[] c : tries) {
                    int y = step(map, c[0], (int) Math.floor(ly + 0.01), c[1]);
                    if (y == Integer.MIN_VALUE) continue;
                    if (!VoxelRay.clear(voxels, eye.x(), eye.y(), eye.z(), c[0] + 0.5, y + 1.0, c[1] + 0.5, Voxel.SIGHT)) continue;
                    found = PosLong.block(c[0], y, c[1]);
                    break;
                }
            }
            if (found == Long.MIN_VALUE) continue;
            double fromLost = Math.hypot(PosLong.blockX(found) + 0.5 - lx, PosLong.blockZ(found) + 0.5 - lz);
            if (taken(taken, eye, PosLong.blockX(found), PosLong.blockZ(found))) {
                if (fromLost < takenD) {
                    takenD = fromLost;
                    bestTaken = found;
                }
            } else if (fromLost < bestD) {
                bestD = fromLost;
                best = found;
            }
        }
        // every corner watched already: one of them twice rather than the cover between
        return best != Long.MIN_VALUE ? best : bestTaken;
    }

    /** the walk out from where the target was lost (the exit for a mob with no squad mates watching one) */
    private static long walk(CellSource map, VoxelRay.Source voxels, Vec3d eye, double lx, double ly, double lz, double vx, double vz,
            GameRandom rng) {
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
                boolean seen = VoxelRay.clear(voxels, eye.x(), eye.y(), eye.z(), x + 0.5, y + 1.0, z + 0.5, Voxel.SIGHT);
                if (!seen) hidden = true;
                else if (hidden && d >= MIN) return PosLong.block(x, y, z);
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
        SectionCells c = map.cells(PosLong.section(x >> 4, y >> 4, z >> 4));
        return c != null && c.find(x & 15, y & 15, z & 15) >= 0;
    }

    /**
     * A target standing in the cell is in plain sight from the eye (its chest), and one step from it back towards
     * where it was lost it is not: the cell is where it comes back into sight
     */
    static boolean edge(VoxelRay.Source voxels, Vec3d eye, long cell, double lx, double lz) {
        int x = PosLong.blockX(cell), y = PosLong.blockY(cell), z = PosLong.blockZ(cell);
        if (!VoxelRay.clear(voxels, eye.x(), eye.y(), eye.z(), x + 0.5, y + 1.0, z + 0.5, Voxel.SIGHT)) return false;
        double bx = lx - (x + 0.5), bz = lz - (z + 0.5), b = Math.sqrt(bx * bx + bz * bz);
        if (b < 1e-6) return false;
        int nx = x + (int) Math.round(bx / b), nz = z + (int) Math.round(bz / b);
        return !VoxelRay.clear(voxels, eye.x(), eye.y(), eye.z(), nx + 0.5, y + 1.0, nz + 0.5, Voxel.SIGHT);
    }
}
