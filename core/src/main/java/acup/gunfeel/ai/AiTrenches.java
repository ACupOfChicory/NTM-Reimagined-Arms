package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.PosLong;
import acup.gunfeel.ReaimTimes;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * A trench read off the cells as a building's rooms and ways (stage 6, T6), so that it is gone into and searched as a
 * building is (AiEntry, AiClearing): cells one or two wide under the open sky, sunk below the ground round them. A
 * straight stretch of it is a room, with the fire steps along it; a turn (where the trench goes on both east-west and
 * north-south: a cell in a trench one wide, a square of four in one two wide) is a way between the stretches it joins,
 * each two of them where three or four meet. From a stretch, beside its turn, the next one is round the corner behind
 * the wall: they stack up there, one steps out into the turn, throws a grenade down the next stretch and they go in,
 * as at a doorway.
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

    /** a wall at this column: at the cell's head height 2 (no mob walks through it at the head, none sees through it), at its feet only 1 (a fire step), none 0 */
    private static int wall(CellSource src, int x, int y, int z) {
        int v = src.voxel(x, y + 1, z);
        if (!Voxel.open(v) && Voxel.sight(v)) return 2;
        v = src.voxel(x, y, z);
        return !Voxel.open(v) && Voxel.sight(v) ? 1 : 0;
    }

    /** of the 24 columns round a cell (five by five), walls worth this much at least (a wall at head height 2, at the feet 1); next to such a cell, this much */
    static final int WALLED = 22, EDGED = 14;

    /**
     * a cell of a trench one or two wide: stood in, under the open sky, sunk below the ground round it (the columns round
     * it walled at its head height, a fire step at its feet half: a stretch one wide about 40, two wide 30, two wide with
     * a fire step 25, a turn or where three meet 22 to 34; open ground none, beside a wall 10, in an alley three wide 20,
     * at the foot of a slope one up a block 15); or less sunk (the inside corner of a turn two wide, with a fire step
     * by it 20) next to such a cell
     */
    static boolean sunk(PathSearch.Reader r, int x, int y, int z) {
        int w = walls(r, x, y, z);
        if (w >= WALLED) return true;
        if (w < EDGED) return false;
        for (int d : CARDINAL) if (walls(r, x + Dirs.DX[d], y, z + Dirs.DZ[d]) >= WALLED) return true;
        return false;
    }

    /** the walls round a cell stood in under the open sky (as sunk counts them); -1 for no such cell */
    private static int walls(PathSearch.Reader r, int x, int y, int z) {
        if (!r.has(x, y, z) || (r.flags(x, y, z) & SectionCells.ROOF) != 0) return -1;
        int walls = 0;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                if (dx != 0 || dz != 0) walls += wall(r.src, x + dx, y, z + dz);
        return walls;
    }

    /** a cell one up from the trench's floor the way d with a wall at its feet beyond it: a fire step, fired from over the wall */
    private static boolean step(PathSearch.Reader r, long from, long next, int d) {
        int nx = PosLong.blockX(next), ny = PosLong.blockY(next), nz = PosLong.blockZ(next);
        if (ny != PosLong.blockY(from) + 1 || (r.flags(nx, ny, nz) & SectionCells.ROOF) != 0) return false;
        int v = r.src.voxel(nx + Dirs.DX[d], ny, nz + Dirs.DZ[d]);
        return !Voxel.open(v) && Voxel.sight(v);
    }

    /** the reading itself: what it throws goes to the caller (the tests) */
    static AiRooms.@Nullable Plan read(CellSource src, long seed, int reach, int most) {
        PathSearch.Reader r = new PathSearch.Reader(src);
        int sx = PosLong.blockX(seed), sy = PosLong.blockY(seed), sz = PosLong.blockZ(seed);
        if (!sunk(r, sx, sy, sz)) return null;
        // the trench's cells joined to the seed; the fire steps beside them
        LongArrayList cells = new LongArrayList();
        Long2IntOpenHashMap index = new Long2IntOpenHashMap();
        index.defaultReturnValue(-1);
        cells.add(seed);
        index.put(seed, 0);
        Long2IntOpenHashMap steps = new Long2IntOpenHashMap(); // a fire step: the trench's cell it is stepped up to from
        steps.defaultReturnValue(-1);
        boolean cut = false;
        for (int head = 0; head < cells.size(); head++) {
            long c = cells.getLong(head);
            int x = PosLong.blockX(c), y = PosLong.blockY(c), z = PosLong.blockZ(c);
            for (int d : CARDINAL) {
                long next = PathSearch.step(r, x, y, z, d);
                if (next == PathSearch.NONE || index.get(next) >= 0) continue;
                int nx = PosLong.blockX(next), ny = PosLong.blockY(next), nz = PosLong.blockZ(next);
                if (!sunk(r, nx, ny, nz)) {
                    if (step(r, c, next, d) && !steps.containsKey(next)) steps.put(next, head);
                    continue;
                }
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
        // each cell's neighbours in the trench, by CARDINAL
        int[][] near = new int[total][4];
        for (int i = 0; i < total; i++) {
            long c = cells.getLong(i);
            int x = PosLong.blockX(c), y = PosLong.blockY(c), z = PosLong.blockZ(c);
            for (int k = 0; k < 4; k++) {
                long next = PathSearch.step(r, x, y, z, CARDINAL[k]);
                near[i][k] = next == PathSearch.NONE ? -1 : index.get(next);
            }
        }
        // three wide anywhere (a square of nine): no trench this reads
        for (int i = 0; i < total; i++) {
            long c = cells.getLong(i);
            boolean nine = true;
            for (int a = 0; a < 3 && nine; a++)
                for (int b = 0; b < 3 && nine; b++) nine = index.get(PosLong.block(PosLong.blockX(c) + a, PosLong.blockY(c), PosLong.blockZ(c) + b)) >= 0;
            if (nine) return null;
        }

        // a turn's cells: the trench goes on three cells at least through the cell both east-west and north-south (where
        // one stretch turns into another, or three or four meet: one cell in a trench one wide, a square of four in one
        // two wide); a stretch's cells go on so one way only (across a stretch two wide is two)
        boolean[] turn = new boolean[total];
        for (int i = 0; i < total; i++) turn[i] = run(near, i, 1) >= 3 && run(near, i, 0) >= 3;

        // the rooms: the cells in no turn, joined; the turns: their cells joined
        int[] room = new int[total], bend = new int[total];
        int rooms = flood(near, turn, false, room), bends = flood(near, turn, true, bend);
        // the stretches each turn joins, and from where: {room, the room's cell, the turn's cell, the direction from it into the room}
        java.util.List<java.util.List<int[]>> joins = new java.util.ArrayList<>();
        for (int t = 0; t < bends; t++) joins.add(new java.util.ArrayList<>());
        for (int i = 0; i < total; i++) {
            if (!turn[i]) continue;
            for (int k = 0; k < 4; k++) {
                int n = near[i][k];
                if (n >= 0 && !turn[n]) joins.get(bend[i]).add(new int[] {room[n], n, i, CARDINAL[k]});
            }
        }
        // a turn that joins one stretch only (the end of a trench, round a loop): its cells are the stretch's
        for (int i = 0; i < total; i++) {
            if (!turn[i]) continue;
            java.util.List<int[]> j = joins.get(bend[i]);
            int only = j.isEmpty() ? AiRooms.OUTSIDE : j.get(0)[0];
            boolean one = !j.isEmpty();
            for (int[] e : j) one &= e[0] == only;
            if (one) room[i] = only;
        }
        if (rooms == 0) return null;

        int[] size = new int[rooms];
        long[] sumX = new long[rooms], sumY = new long[rooms], sumZ = new long[rooms];
        for (int i = 0; i < total; i++) {
            int id = room[i];
            if (id < 0) continue;
            long at = cells.getLong(i);
            size[id]++;
            sumX[id] += PosLong.blockX(at);
            sumY[id] += PosLong.blockY(at);
            sumZ[id] += PosLong.blockZ(at);
        }
        long[] mid = new long[rooms];
        double[] best = new double[rooms];
        java.util.Arrays.fill(best, Double.MAX_VALUE);
        for (int i = 0; i < total; i++) {
            int id = room[i];
            if (id < 0) continue;
            long at = cells.getLong(i);
            double dx = PosLong.blockX(at) - (double) sumX[id] / size[id], dy = PosLong.blockY(at) - (double) sumY[id] / size[id],
                    dz = PosLong.blockZ(at) - (double) sumZ[id] / size[id];
            double d = dx * dx + dy * dy * 4 + dz * dz;
            if (d < best[id]) {
                best[id] = d;
                mid[id] = at;
            }
        }

        java.util.List<AiRooms.Way> ways = new java.util.ArrayList<>();
        for (int t = 0; t < bends; t++) {
            java.util.List<int[]> j = joins.get(t);
            java.util.TreeSet<Integer> by = new java.util.TreeSet<>();
            for (int[] e : j) by.add(e[0]);
            if (by.size() < 2) continue;
            LongArrayList at = new LongArrayList();
            for (int i = 0; i < total; i++) if (turn[i] && bend[i] == t && room[i] < 0) at.add(cells.getLong(i));
            Integer[] rs = by.toArray(new Integer[0]);
            for (int x = 0; x < rs.length; x++)
                for (int y = x + 1; y < rs.length; y++) {
                    AiRooms.Way w = way(j, rs[x], rs[y], at.toLongArray(), near, cells, index, room);
                    if (w != null) ways.add(w);
                }
        }
        // a trench is gone into from above anywhere: each stretch has a way out, with no place beside it (no stacking up
        // at it), so the search reaches every stretch from outside
        for (int id = 0; id < rooms; id++) ways.add(new AiRooms.Way(new long[] {mid[id]}, Dirs.N, id, AiRooms.OUTSIDE, 1, false, new long[0], new long[0]));

        byte[] kind = new byte[rooms];
        for (int id = 0; id < rooms; id++) kind[id] = size[id] >= AiRooms.CORRIDOR_LONG ? AiRooms.CORRIDOR : AiRooms.ROOM;
        Long2IntOpenHashMap roomOf = new Long2IntOpenHashMap();
        roomOf.defaultReturnValue(AiRooms.OUTSIDE);
        for (int i = 0; i < total; i++) if (room[i] >= 0) roomOf.put(cells.getLong(i), room[i]);
        // the fire steps: the stretch's of the trench's cells next to it, below (the least numbered of them, whichever
        // cell it was found from)
        for (long st : steps.keySet()) {
            if (roomOf.containsKey(st)) continue;
            int id = Integer.MAX_VALUE;
            for (int d : CARDINAL) {
                int i = index.get(PosLong.block(PosLong.blockX(st) + Dirs.DX[d], PosLong.blockY(st) - 1, PosLong.blockZ(st) + Dirs.DZ[d]));
                if (i >= 0 && room[i] >= 0) id = Math.min(id, room[i]);
            }
            if (id == Integer.MAX_VALUE) continue;
            roomOf.put(st, id);
            size[id]++;
        }
        return new AiRooms.Plan(size, mid, ways.toArray(new AiRooms.Way[0]), cut, roomOf, kind, new long[0], new long[0], new AiRooms.Window[0]);
    }

    /** the cells in a row through cell i along an axis (0 north-south, 1 east-west), it with them */
    private static int run(int[][] near, int i, int axis) {
        int n = 1;
        for (int k = axis; k < 4; k += 2)
            for (int c = near[i][k], guard = 0; c >= 0 && guard < near.length; c = near[c][k], guard++) n++;
        return n;
    }

    /** the cells that are (in) or are not turns, joined, numbered into id (-1 for the others); how many */
    private static int flood(int[][] near, boolean[] turn, boolean in, int[] id) {
        java.util.Arrays.fill(id, AiRooms.OUTSIDE);
        int[] stack = new int[near.length];
        int count = 0;
        for (int i = 0; i < near.length; i++) {
            if (turn[i] != in || id[i] != AiRooms.OUTSIDE) continue;
            int top = 0;
            stack[top++] = i;
            id[i] = count;
            while (top > 0) {
                int c = stack[--top];
                for (int k = 0; k < 4; k++) {
                    int n = near[c][k];
                    if (n < 0 || turn[n] != in || id[n] != AiRooms.OUTSIDE) continue;
                    id[n] = count;
                    stack[top++] = n;
                }
            }
            count++;
        }
        return count;
    }

    /**
     * The way through a turn from stretch a to stretch b: its cells (in order along it, from a); beside it at each end
     * the stretch's cells next to the turn, then on back along the stretch; the direction into it from a, and out of it
     * into b where that is another (a turn), with the cell of it one steps out to to throw into each.
     */
    private static AiRooms.@Nullable Way way(java.util.List<int[]> joins, int a, int b, long[] at, int[][] near, LongArrayList cells, Long2IntOpenHashMap index, int[] room) {
        LongArrayList besideA = new LongArrayList(), besideB = new LongArrayList();
        int dir = -1, out = -1;
        for (int[] e : joins) {
            if (e[0] == a && (dir < 0 || Dirs.opposite(e[3]) == dir)) {
                dir = Dirs.opposite(e[3]);
                if (!besideA.contains(cells.getLong(e[1]))) besideA.add(cells.getLong(e[1]));
            } else if (e[0] == b && (out < 0 || e[3] == out)) {
                out = e[3];
                if (!besideB.contains(cells.getLong(e[1]))) besideB.add(cells.getLong(e[1]));
            }
        }
        if (dir < 0 || out < 0 || at.length == 0) return null;
        // its cells from a's end to b's
        final int d0 = dir;
        long[] order = java.util.Arrays.stream(at).boxed()
                .sorted(java.util.Comparator.comparingInt(c -> PosLong.blockX(c) * Dirs.DX[d0] + PosLong.blockZ(c) * Dirs.DZ[d0])).mapToLong(Long::longValue).toArray();
        AiRooms.Way w = new AiRooms.Way(order, dir, a, b, Math.max(besideA.size(), besideB.size()), false, besideA.toLongArray(), besideB.toLongArray());
        w.farA = further(w.besideA, Dirs.opposite(dir), near, cells, index, room);
        w.far2A = further(w.farA, Dirs.opposite(dir), near, cells, index, room);
        w.farB = further(w.besideB, out, near, cells, index, room);
        w.far2B = further(w.farB, out, near, cells, index, room);
        if (out != dir) {
            w.turn = out;
            w.frontB = front(at, w.besideA, Dirs.opposite(dir), out, b, index, room);
            w.frontA = front(at, w.besideB, out, Dirs.opposite(dir), a, index, room);
        }
        return w;
    }

    /**
     * Of a turn's cells, the one to throw from into the room the way into: next to the other room's cells beside it
     * (one steps out to it from there), with that room straight on from it the way in, the nearest of them to it; the
     * first next to them where none is.
     */
    private static long front(long[] at, long[] stack, int back, int into, int target, Long2IntOpenHashMap index, int[] room) {
        long first = at[0], best = AiRooms.Way.NONE;
        boolean found = false;
        int nearest = Integer.MAX_VALUE;
        for (long c : at) {
            int x = PosLong.blockX(c), y = PosLong.blockY(c), z = PosLong.blockZ(c);
            long behind = PosLong.block(x + Dirs.DX[back], y, z + Dirs.DZ[back]);
            boolean next = false;
            for (long s : stack) next |= s == behind;
            if (!next) continue;
            if (!found) {
                first = c;
                found = true;
            }
            for (int k = 1; k <= 3; k++) {
                int i = index.get(PosLong.block(x + Dirs.DX[into] * k, y, z + Dirs.DZ[into] * k));
                if (i < 0) break;
                if (room[i] == target && k < nearest) {
                    nearest = k;
                    best = c;
                }
            }
        }
        return best != AiRooms.Way.NONE ? best : first;
    }

    /** for each cell, the next one on the way d in the same room, or itself where there is none */
    private static long[] further(long[] from, int d, int[][] near, LongArrayList cells, Long2IntOpenHashMap index, int[] room) {
        long[] out = from.clone();
        for (int i = 0; i < out.length; i++) {
            int c = index.get(from[i]);
            if (c < 0) continue;
            int n = near[c][d / 2];
            if (n >= 0 && room[n] == room[c]) out[i] = cells.getLong(n);
        }
        return out;
    }
}
