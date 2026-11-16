package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ReaimTimes;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;

/**
 * A trench read off the cells as a building's rooms and ways (stage 6, T6), so that it is gone into and searched as a
 * building is (AiEntry, AiClearing): a run of cells one wide under the open sky with a wall at head height on both
 * sides. A straight stretch of it is a room; a turn (a cell gone into one way and out of another) is a way between the
 * two stretches it joins, and where three or more stretches meet, the cell they meet at is a room of its own with a
 * way at the mouth of each. From a stretch, beside its turn, the next one is round the corner behind the wall: they
 * stack up there, one steps out into the turn, throws a grenade down the next stretch and they go in, as at a doorway.
 * No block is read, only cells and voxel bytes, so it runs off the server thread as AiRooms does.
 */
public final class AiTrenches {
    /** the server setting: AI soldiers go into and search a trench stretch after stretch, as a building's rooms */
    public static final String KEY = "aiTrenches";
    /** off only for the stands' "before" */
    static boolean on = true;
    /** how far from the cell asked about the trench is read, in blocks; the most cells read; a trench has this many cells at least */
    static final int REACH = 24, MAX_CELLS = 400, LEAST = 6;
    private static final int[] CARDINAL = {Dirs.N, Dirs.E, Dirs.S, Dirs.W};

    private AiTrenches() {
    }

    static boolean on() {
        return on && ReaimTimes.flag(KEY, true);
    }

    /** the building the cell is in; not under a roof, the trench it is in (with trenches on); or null */
    static AiRooms.@Nullable Plan place(CellSource src, long seed) {
        AiRooms.Plan p = AiRooms.of(src, seed);
        return p != null || !on() ? p : of(src, seed);
    }

    /** the trench the cell is in, or null; a trench that cannot be read is none (a line in the debug log) */
    public static AiRooms.@Nullable Plan of(CellSource src, long seed) {
        try {
            return read(src, seed, REACH, MAX_CELLS);
        } catch (RuntimeException e) {
            AiRooms.fault("reading a trench", seed, e);
            return null;
        }
    }

    /** a wall at head height the way d from the cell: no mob walks through it at the head, none sees through it */
    private static boolean wall(CellSource src, int x, int y, int z, int d) {
        int v = src.voxel(x + Dirs.DX[d], y + 1, z + Dirs.DZ[d]);
        return !Voxel.open(v) && Voxel.sight(v);
    }

    /**
     * a cell of a trench one wide: stood in, under the open sky, and at each of its four corners a wall at head height,
     * at the corner or at one side of it (a stretch, a turn, where three or four meet, the cell past a turn: the trench
     * goes on only straight out of it); on open ground, by one wall, at a house's corner, in a trench two wide a
     * corner is open with both its sides
     */
    static boolean sunk(PathSearch.Reader r, int x, int y, int z) {
        if (!r.has(x, y, z) || (r.flags(x, y, z) & SectionCells.ROOF) != 0) return false;
        for (int d = Dirs.NE; d < 8; d += 2)
            if (!wall(r.src, x, y, z, d) && !wall(r.src, x, y, z, d - 1) && !wall(r.src, x, y, z, (d + 1) & 7)) return false;
        return true;
    }

    /** the reading itself: what it throws goes to the caller (the tests) */
    static AiRooms.@Nullable Plan read(CellSource src, long seed, int reach, int most) {
        PathSearch.Reader r = new PathSearch.Reader(src);
        int sx = BlockPos.getX(seed), sy = BlockPos.getY(seed), sz = BlockPos.getZ(seed);
        if (!sunk(r, sx, sy, sz)) return null;
        // the trench's cells joined to the seed
        LongArrayList cells = new LongArrayList();
        Long2IntOpenHashMap index = new Long2IntOpenHashMap();
        index.defaultReturnValue(-1);
        cells.add(seed);
        index.put(seed, 0);
        boolean cut = false;
        for (int head = 0; head < cells.size(); head++) {
            long c = cells.getLong(head);
            int x = BlockPos.getX(c), y = BlockPos.getY(c), z = BlockPos.getZ(c);
            for (int d : CARDINAL) {
                long next = PathSearch.step(r, x, y, z, d);
                if (next == PathSearch.NONE || index.get(next) >= 0) continue;
                int nx = BlockPos.getX(next), ny = BlockPos.getY(next), nz = BlockPos.getZ(next);
                if (!sunk(r, nx, ny, nz)) continue;
                if (Math.abs(nx - sx) > reach || Math.abs(nz - sz) > reach || cells.size() >= most) {
                    cut = true;
                    continue;
                }
                index.put(next, cells.size());
                cells.add(next);
            }
        }
        int total = cells.size();
        if (total < LEAST) return null;
        // each cell's neighbours in the trench, by CARDINAL; and no trench two wide (a square of four cells): that is read
        // as none here (a stretch two wide has no turn to stack up beside)
        int[][] near = new int[total][4];
        int[] deg = new int[total];
        for (int i = 0; i < total; i++) {
            long c = cells.getLong(i);
            int x = BlockPos.getX(c), y = BlockPos.getY(c), z = BlockPos.getZ(c);
            for (int k = 0; k < 4; k++) {
                long next = PathSearch.step(r, x, y, z, CARDINAL[k]);
                near[i][k] = next == PathSearch.NONE ? -1 : index.get(next);
                if (near[i][k] >= 0) deg[i]++;
            }
        }
        for (int i = 0; i < total; i++)
            for (int k = 0; k < 4; k++) {
                int a = near[i][k], b = near[i][(k + 1) & 3];
                if (a >= 0 && b >= 0 && (near[a][(k + 1) & 3] >= 0 || near[b][k] >= 0)) return null;
            }

        // the ways: a turn (two neighbours, not across from each other); a cell straight on next to where three or more
        // stretches meet (the mouth of a stretch there). A way's two sides: its two neighbours
        boolean[] way = new boolean[total];
        int[][] sides = new int[total][];
        for (int i = 0; i < total; i++) {
            if (deg[i] != 2) continue;
            int p = -1, q = -1, kp = -1, kq = -1;
            for (int k = 0; k < 4; k++) {
                if (near[i][k] < 0) continue;
                if (p < 0) {
                    p = near[i][k];
                    kp = k;
                } else {
                    q = near[i][k];
                    kq = k;
                }
            }
            boolean turn = (kq - kp) != 2;
            boolean mouth = !turn && (deg[p] >= 3 || deg[q] >= 3);
            if (!turn && !mouth) continue;
            way[i] = true;
            sides[i] = new int[] {p, q};
        }
        // a way with a way at a side (a zigzag of turns one after another, a mouth at a turn) is no way: all such cells go
        // into the stretches at once, so the trench is cut the same whichever cell it was read from
        boolean[] drop = new boolean[total];
        for (int i = 0; i < total; i++) drop[i] = way[i] && (way[sides[i][0]] || way[sides[i][1]]);
        for (int i = 0; i < total; i++) if (drop[i]) way[i] = false;

        // the rooms: the cells in no way, joined
        int[] room = new int[total];
        java.util.Arrays.fill(room, AiRooms.OUTSIDE);
        java.util.List<int[]> sum = new java.util.ArrayList<>(); // {count, sum x, sum y, sum z}
        int[] stack = new int[total];
        for (int i = 0; i < total; i++) {
            if (way[i] || room[i] != AiRooms.OUTSIDE) continue;
            int id = sum.size(), top = 0;
            int[] s = new int[4];
            stack[top++] = i;
            room[i] = id;
            while (top > 0) {
                int c = stack[--top];
                long at = cells.getLong(c);
                s[0]++;
                s[1] += BlockPos.getX(at);
                s[2] += BlockPos.getY(at);
                s[3] += BlockPos.getZ(at);
                for (int k = 0; k < 4; k++) {
                    int n = near[c][k];
                    if (n < 0 || way[n] || room[n] != AiRooms.OUTSIDE) continue;
                    room[n] = id;
                    stack[top++] = n;
                }
            }
            sum.add(s);
        }
        int rooms = sum.size();
        int[] size = new int[rooms];
        long[] mid = new long[rooms];
        double[] best = new double[rooms];
        java.util.Arrays.fill(best, Double.MAX_VALUE);
        for (int i = 0; i < total; i++) {
            int id = room[i];
            if (id < 0) continue;
            int[] s = sum.get(id);
            size[id] = s[0];
            long at = cells.getLong(i);
            double dx = BlockPos.getX(at) - (double) s[1] / s[0], dy = BlockPos.getY(at) - (double) s[2] / s[0], dz = BlockPos.getZ(at) - (double) s[3] / s[0];
            double d = dx * dx + dy * dy * 4 + dz * dz;
            if (d < best[id]) {
                best[id] = d;
                mid[id] = at;
            }
        }

        java.util.List<AiRooms.Way> ways = new java.util.ArrayList<>();
        for (int i = 0; i < total; i++) {
            if (!way[i]) continue;
            int p = sides[i][0], q = sides[i][1], a = room[p], b = room[q];
            if (a == b) {
                // round a loop: it parts nothing, its cell is the stretch's
                room[i] = a;
                size[a]++;
                continue;
            }
            long at = cells.getLong(i), pa = cells.getLong(p), qa = cells.getLong(q);
            int dir = toward(pa, at), out = toward(at, qa);
            // the cells to stack up at: each side's own cell next to the way, then on back along its stretch
            long farA = back(near, cells, room, p, Dirs.opposite(dir)), farB = back(near, cells, room, q, out);
            long far2A = back(near, cells, room, index.get(farA), Dirs.opposite(dir)), far2B = back(near, cells, room, index.get(farB), out);
            AiRooms.Way w = new AiRooms.Way(new long[] {at}, dir, a, b, 1, false, new long[] {pa}, new long[] {qa});
            w.farA = new long[] {farA};
            w.farB = new long[] {farB};
            w.far2A = new long[] {far2A};
            w.far2B = new long[] {far2B};
            if (out != dir) w.turn = out;
            ways.add(w);
        }
        // a trench is gone into from above anywhere: each stretch has a way out, with no place beside it (no stacking up
        // at it), so the search reaches every stretch from outside
        for (int id = 0; id < rooms; id++) ways.add(new AiRooms.Way(new long[] {mid[id]}, Dirs.N, id, AiRooms.OUTSIDE, 1, false, new long[0], new long[0]));

        byte[] kind = new byte[rooms];
        for (int id = 0; id < rooms; id++) kind[id] = size[id] >= AiRooms.CORRIDOR_LONG ? AiRooms.CORRIDOR : AiRooms.ROOM;
        Long2IntOpenHashMap roomOf = new Long2IntOpenHashMap();
        roomOf.defaultReturnValue(AiRooms.OUTSIDE);
        for (int i = 0; i < total; i++) if (room[i] >= 0) roomOf.put(cells.getLong(i), room[i]);
        return new AiRooms.Plan(size, mid, ways.toArray(new AiRooms.Way[0]), cut, roomOf, kind, new long[0], new long[0], new AiRooms.Window[0]);
    }

    /** the cardinal direction from a cell to the one next to it */
    private static int toward(long from, long to) {
        int dx = BlockPos.getX(to) - BlockPos.getX(from), dz = BlockPos.getZ(to) - BlockPos.getZ(from);
        for (int d : CARDINAL) if (Dirs.DX[d] == dx && Dirs.DZ[d] == dz) return d;
        throw new IllegalStateException("not next to each other: " + BlockPos.of(from) + " " + BlockPos.of(to));
    }

    /** the cell after cell i the way d in the same room, or cell i itself where there is none */
    private static long back(int[][] near, LongArrayList cells, int[] room, int i, int d) {
        int k = d / 2, n = near[i][k];
        return n >= 0 && room[n] == room[i] ? cells.getLong(n) : cells.getLong(i);
    }
}
