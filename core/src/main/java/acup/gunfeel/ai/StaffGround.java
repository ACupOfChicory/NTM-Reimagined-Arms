package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.PosLong;
import acup.gunfeel.host.WorldView;
import acup.gunfeel.water.LiquidPath;

/**
 * The ground the staff's soldiers go over by the blocks (26.2's AiCommander): asleep, a way round deep water
 * ({@link #detour}); awake, the cell to stand in at a leg's end ({@link #stand}). The level's tops by its heightmap
 * (MOTION_BLOCKING_NO_LEAVES), its liquids by the water's own port (LiquidPath.Fluids), the cells by the tactical map.
 */
public final class StaffGround {
    /** asleep, a step into deep water: a way round looked for over the blocks within DETOUR */
    public static final int DETOUR = 12;
    /** asleep, blocks up or down a step at most */
    public static final double CLIMB = 3;
    /** a place to stand looked for this far round x, z, blocks; this far down from the top the sky sees */
    public static final int SNAP = 2, DOWN = 4;
    /** a place this much over or under the soldier is at about its height, blocks */
    public static final int LEVEL = 2;

    private StaffGround() {
    }

    /** water a block deep (a ford) is walked; deeper water, or any other liquid, is not: the column's top at ny */
    private static boolean wet(LiquidPath.Fluids fluids, int bx, int ny, int bz) {
        LiquidPath.Cell f = fluids.at(bx, ny - 1, bz);
        return f != null && (f.liquid() != acup.gunfeel.water.LiquidEvents.Liquid.WATER || fluids.at(bx, ny - 2, bz) != null);
    }

    /**
     * asleep, deep water in its step: a way over the blocks within DETOUR of it (dry ground, or water a block deep: a
     * ford; up or down CLIMB at most a step) to the one nearest to tx, tz; null: none nearer by 2 at least. The way: each
     * block x << 32 | z, from the first step on.
     */
    public static long @Nullable [] detour(WorldView w, LiquidPath.Fluids fluids, int x0, int z0, int y0, double tx, double tz) {
        int side = DETOUR * 2 + 1;
        int[] from = new int[side * side], ys = new int[side * side];
        java.util.Arrays.fill(from, -2);
        int start = DETOUR * side + DETOUR;
        from[start] = -1;
        ys[start] = y0;
        int[] queue = new int[side * side];
        int head = 0, tail = 0, best = start;
        queue[tail++] = start;
        double bestD = Math.hypot(x0 + 0.5 - tx, z0 + 0.5 - tz);
        double startD = bestD;
        while (head < tail) {
            int c = queue[head++];
            int cx = c % side, cz = c / side;
            for (int d = 0; d < 4; d++) {
                int nx = cx + (d == 0 ? 1 : d == 1 ? -1 : 0), nz = cz + (d == 2 ? 1 : d == 3 ? -1 : 0);
                if (nx < 0 || nz < 0 || nx >= side || nz >= side) continue;
                int n = nz * side + nx;
                if (from[n] != -2) continue;
                int bx = x0 + nx - DETOUR, bz = z0 + nz - DETOUR;
                from[n] = -3;
                if (!w.hasChunk(bx >> 4, bz >> 4)) continue;
                int ny = w.height(bx, bz, WorldView.HEIGHT_MOTION_BLOCKING_NO_LEAVES);
                if (Math.abs(ny - ys[c]) > CLIMB) continue;
                if (wet(fluids, bx, ny, bz)) continue;
                from[n] = c;
                ys[n] = ny;
                queue[tail++] = n;
                double dd = Math.hypot(bx + 0.5 - tx, bz + 0.5 - tz);
                if (dd < bestD) {
                    bestD = dd;
                    best = n;
                }
            }
        }
        if (best == start || startD - bestD < 2) return null;
        int len = 0;
        for (int c = best; c != start; c = from[c]) len++;
        long[] path = new long[len];
        for (int c = best, i = len - 1; c != start; c = from[c], i--)
            path[i] = (long) (x0 + c % side - DETOUR) << 32 | (z0 + c / side - DETOUR) & 0xFFFFFFFFL;
        return path;
    }

    /**
     * The cell to stand in nearest x, z: in its column from the top the sky sees down to DOWN under the soldier's own
     * height (ownY) or under the top, at about its own height the nearest across, else the nearest across at any
     * height (nearest its own height), within SNAP that has one; the top of x, z itself where the map has not been built
     * there yet (the walk has it built); Long.MIN_VALUE: the map has those blocks and none of them is one to stand in.
     */
    public static long stand(WorldView w, TacticalMapBase map, int bx, int bz, int ownY) {
        int top0 = w.height(bx, bz, WorldView.HEIGHT_MOTION_BLOCKING_NO_LEAVES);
        boolean unknown = false;
        // at about its own height (a step up or down: a house's floor, the ground round a wall) the nearest across;
        // else the nearest across at any height (a hill's top), nearest its own height
        long near = Long.MIN_VALUE, any = Long.MIN_VALUE;
        double nearD = Double.MAX_VALUE, anyD = Double.MAX_VALUE;
        for (int dx = -SNAP; dx <= SNAP; dx++)
            for (int dz = -SNAP; dz <= SNAP; dz++) {
                int x = bx + dx, z = bz + dz;
                if (!w.hasChunk(x >> 4, z >> 4)) continue;
                int top = dx == 0 && dz == 0 ? top0 : w.height(x, z, WorldView.HEIGHT_MOTION_BLOCKING_NO_LEAVES);
                double across = dx * dx + dz * dz;
                for (int y = top, low = Math.max(w.minY(), Math.min(top, ownY) - DOWN); y >= low; y--) {
                    SectionCells c = map.cellsAt(x, y, z);
                    if (c == null) {
                        unknown = true;
                        break;
                    }
                    if (c.find(x & 15, y & 15, z & 15) < 0) continue;
                    int dy = Math.abs(y - ownY);
                    if (dy <= LEVEL && across < nearD) {
                        nearD = across;
                        near = PosLong.block(x, y, z);
                    }
                    double d = across * 1000 + dy;
                    if (d < anyD) {
                        anyD = d;
                        any = PosLong.block(x, y, z);
                    }
                }
            }
        if (near != Long.MIN_VALUE) return near;
        if (any != Long.MIN_VALUE) return any;
        return unknown ? PosLong.block(bx, top0, bz) : Long.MIN_VALUE;
    }
}
