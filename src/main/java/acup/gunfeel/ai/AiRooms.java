package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

/**
 * The rooms of a building and the ways between them, read off the cells (stage 4.5b): the cells under a roof
 * ({@link SectionCells#ROOF}) joined to the cell asked about, cut where a mob gets through one wide (a doorway, a
 * door, the mouth of a corridor) and where the roof ends. A way knows the rooms at its two ends and the cells beside
 * the opening at each end, out of the line through it: where a squad stacks up before it goes in. No block is read,
 * only cells and voxel bytes, so it runs off the server thread as the path search does.
 */
public final class AiRooms {
    private AiRooms() {
    }

    /** how far from the cell asked about the building is read, in blocks; the most cells under the roof read */
    static final int REACH = 20, MAX_CELLS = 900;
    /** a passage one wide this long or shorter is one way (a doorway in a thick wall); longer, a corridor with a way at each mouth */
    static final int WAY_LONG = 3;
    /** a door's frame is this wide at most, and this many rows thick; a passage two wide and longer is a corridor */
    static final int WIDE = 3, WIDE_LONG = 2;
    /** a room of so many cells at most at the end of a way one wide with no door is no room: the way is none */
    static final int TINY = 2;
    /** a wild cell (AiRooms.wild) this many steps from a sign that someone lives there is a building's */
    static final int DWELT = 8;
    /** not under the roof */
    public static final int OUTSIDE = -1;
    private static final int[] CARDINAL = {Dirs.N, Dirs.E, Dirs.S, Dirs.W};

    /** A way from room a to room b (or out from under the roof). */
    public static final class Way {
        /** the opening's cells: one wide, along it from a to b; an open side of the roof, the row of a's cells along the edge */
        public final long[] cells;
        /** the direction from a to b, {@link Dirs#N}, E, S or W */
        public final int dir;
        /** the rooms at its ends; b may be {@link #OUTSIDE} */
        public final int a, b;
        /** cells across the opening */
        public final int width;
        /** a door or a gate stands in it */
        public final boolean door;
        /** the cells beside the opening at each end with the wall between them and the other end: out of the line through it */
        public final long[] besideA, besideB;
        /** for each of those, the cell one further from the opening along the wall, behind it as well; the same cell where there is none */
        public long[] farA = {}, farB = {};
        /** and one further again than those, the same way */
        public long[] far2A = {}, far2B = {};
        /** it goes over a sill: its cells stand a step higher than the cell before it at an end (a window climbed through) */
        public boolean sill;

        Way(long[] cells, int dir, int a, int b, int width, boolean door, long[] besideA, long[] besideB) {
            this.cells = cells;
            this.dir = dir;
            this.a = a;
            this.b = b;
            this.width = width;
            this.door = door;
            this.besideA = besideA;
            this.besideB = besideB;
        }

        /** the room at the other end from room r */
        public int other(int r) {
            return r == a ? b : a;
        }

        /** the cells beside the opening at room r's end */
        public long[] beside(int r) {
            return r == a ? besideA : besideB;
        }

        /** the cells one further from the opening than those beside it at room r's end, one for each of them */
        public long[] far(int r) {
            long[] near = beside(r), far = r == a ? farA : farB;
            // a way made with none worked out (an open side): those beside it themselves
            return far.length == near.length ? far : near;
        }

        /** two further from the opening than those beside it at room r's end, one for each of them */
        public long[] far2(int r) {
            long[] far = far(r), far2 = r == a ? far2A : far2B;
            return far2.length == far.length ? far2 : far;
        }

        private Way far(PathSearch.Reader r, long[] rowA, long[] rowB) {
            farA = further(r, rowA, besideA, dir);
            farB = further(r, rowB, besideB, Dirs.opposite(dir));
            far2A = further(r, besideA, farA, dir);
            far2B = further(r, besideB, farB, Dirs.opposite(dir));
            return this;
        }

        private Way flipped() {
            Way w = new Way(reverse(cells), Dirs.opposite(dir), b, a, width, door, besideB, besideA);
            w.farA = farB;
            w.farB = farA;
            w.far2A = far2B;
            w.far2B = far2A;
            return w;
        }
    }

    /** A window of a room: the wall's block at head height, the direction out of the room, the room, glass or bars in it. */
    public record Window(long block, int dir, int room, boolean glazed) {
    }

    /** a room's kind: a room, or a corridor (long and narrow) */
    public static final byte ROOM = 0, CORRIDOR = 1;
    /** a corridor is this wide at most; one a cell wide is this many cells at least, one two wide twice that */
    static final int CORRIDOR_WIDE = 2, CORRIDOR_LONG = 4;

    /** A building's rooms and ways. */
    public static final class Plan {
        /** a room's cells counted, and the cell nearest its middle */
        public final int[] size;
        public final long[] mid;
        public final Way[] ways;
        /** the roof went on past REACH or MAX_CELLS: the plan is a part of the building */
        public final boolean cut;
        /** each room's kind, ROOM or CORRIDOR */
        public final byte[] kind;
        /** the cells of its stairs (a step up or down to the next cell) and of its ladders (climbed) */
        public final long[] stairs, ladders;
        public final Window[] windows;
        private final Long2IntOpenHashMap roomOf;

        Plan(int[] size, long[] mid, Way[] ways, boolean cut, Long2IntOpenHashMap roomOf, byte[] kind, long[] stairs, long[] ladders, Window[] windows) {
            this.size = size;
            this.mid = mid;
            this.ways = ways;
            this.cut = cut;
            this.roomOf = roomOf;
            this.kind = kind;
            this.stairs = stairs;
            this.ladders = ladders;
            this.windows = windows;
        }

        /** every cell of its rooms and the room it is in (the ways' cells are in none) */
        public it.unimi.dsi.fastutil.longs.Long2IntMap roomCells() {
            return it.unimi.dsi.fastutil.longs.Long2IntMaps.unmodifiable(roomOf);
        }

        public int rooms() {
            return size.length;
        }

        /** the room a cell is in; {@link #OUTSIDE} for a cell not under this roof or in a way */
        public int room(long cell) {
            return roomOf.get(cell);
        }

        /** the cells of each room (the ways' cells are no room's) */
        public long[][] cells() {
            LongArrayList[] out = new LongArrayList[size.length];
            for (int i = 0; i < out.length; i++) out[i] = new LongArrayList(size[i]);
            for (var e : roomOf.long2IntEntrySet()) out[e.getIntValue()].add(e.getLongKey());
            long[][] cells = new long[out.length][];
            for (int i = 0; i < out.length; i++) cells[i] = out[i].toLongArray();
            return cells;
        }

        /** the ways out of room r */
        public List<Way> waysOf(int r) {
            List<Way> out = new ArrayList<>();
            for (Way w : ways) if (w.a == r || w.b == r) out.add(w);
            return out;
        }
    }

    /** the building the cell is in, or null for a cell not under a roof */
    public static @Nullable Plan of(CellSource src, long seed) {
        return of(src, seed, null);
    }

    /** the same; the cells under the roof round the seed that are no building's (wild) are put into wildOut (the debug view) */
    static @Nullable Plan of(CellSource src, long seed, @Nullable LongOpenHashSet wildOut) {
        return of(src, seed, wildOut, REACH, MAX_CELLS);
    }

    /** the same read this far from the cell and so many cells at most (the debug view reads a whole building) */
    static @Nullable Plan of(CellSource src, long seed, @Nullable LongOpenHashSet wildOut, int reach, int most) {
        PathSearch.Reader r = new PathSearch.Reader(src);
        int sx = BlockPos.getX(seed), sy = BlockPos.getY(seed), sz = BlockPos.getZ(seed);
        if ((r.flags(sx, sy, sz) & SectionCells.ROOF) == 0 || !r.has(sx, sy, sz)) return null;
        // the cells under the roof that are the wild's: a cave is no building
        LongOpenHashSet wild = AiBlockKinds.shell ? wild(src, r, seed, reach, most) : null;
        if (wild != null && wildOut != null) wildOut.addAll(wild);
        if (wild != null && wild.contains(seed)) return null;

        // the cells under the roof joined to the seed, and the cells just out from under it
        LongArrayList cells = new LongArrayList();
        Long2IntOpenHashMap index = new Long2IntOpenHashMap();
        index.defaultReturnValue(-1);
        cells.add(seed);
        index.put(seed, 0);
        int inside = 1;
        boolean cut = false;
        // the first `roofed` entries of nb belong to cells under the roof, read in the order they were found
        List<int[]> nb = new ArrayList<>();
        LongArrayList under = new LongArrayList();
        under.add(seed);
        java.util.BitSet roofed = new java.util.BitSet();
        roofed.set(0);
        for (int head = 0; head < under.size(); head++) {
            long c = under.getLong(head);
            int x = BlockPos.getX(c), y = BlockPos.getY(c), z = BlockPos.getZ(c);
            int[] n = {-1, -1, -1, -1};
            for (int k = 0; k < 4; k++) {
                long next = PathSearch.step(r, x, y, z, CARDINAL[k]);
                if (next == PathSearch.NONE) continue;
                int i = index.get(next);
                if (i < 0) {
                    int nx = BlockPos.getX(next), nz = BlockPos.getZ(next);
                    boolean roof = (r.flags(nx, BlockPos.getY(next), nz) & SectionCells.ROOF) != 0 && (wild == null || !wild.contains(next));
                    if (roof && (Math.abs(nx - sx) > reach || Math.abs(nz - sz) > reach || inside >= most)) {
                        cut = true;
                        continue;
                    }
                    i = cells.size();
                    cells.add(next);
                    index.put(next, i);
                    if (roof) {
                        roofed.set(i);
                        under.add(next);
                        inside++;
                    }
                }
                n[k] = i;
            }
            nb.add(n);
        }
        // nb is by the order in `under`; by cell index instead
        int total = cells.size();
        int[][] near = new int[total][];
        for (int h = 0; h < under.size(); h++) near[index.get(under.getLong(h))] = nb.get(h);

        // one wide: a way through on one axis, walls on the other. axis[i]: 0 north-south, 1 east-west, -1 neither
        byte[] axis = new byte[total];
        boolean[] door = new boolean[total];
        for (int i = 0; i < total; i++) {
            axis[i] = -1;
            if (near[i] == null) continue;
            int[] n = near[i];
            if (n[0] >= 0 && n[2] >= 0 && n[1] < 0 && n[3] < 0) axis[i] = 0;
            else if (n[1] >= 0 && n[3] >= 0 && n[0] < 0 && n[2] < 0) axis[i] = 1;
            long c = cells.getLong(i);
            door[i] = (src.voxel(BlockPos.getX(c), BlockPos.getY(c), BlockPos.getZ(c)) & Voxel.DOOR) != 0;
        }
        // runs of them along their axis; a short run is one way, a long one a corridor with a way at each mouth and at each door
        boolean[] inWay = new boolean[total];
        List<int[]> runs = new ArrayList<>();
        boolean[] seen = new boolean[total];
        for (int i = 0; i < total; i++) {
            if (axis[i] < 0 || seen[i]) continue;
            int back = axis[i] == 0 ? 0 : 3, on = axis[i] == 0 ? 2 : 1;
            int start = i;
            for (int guard = 0; guard < total; guard++) {
                int p = near[start][back];
                if (p < 0 || near[p] == null || axis[p] != axis[i] || p == i) break;
                start = p;
            }
            List<Integer> run = new ArrayList<>();
            for (int c = start; c >= 0 && near[c] != null && axis[c] == axis[i] && !seen[c]; c = near[c][on]) {
                seen[c] = true;
                run.add(c);
            }
            if (run.size() <= WAY_LONG) {
                runs.add(run.stream().mapToInt(Integer::intValue).toArray());
            } else {
                for (int k = 0; k < run.size(); k++)
                    if (k == 0 || k == run.size() - 1 || door[run.get(k)]) runs.add(new int[] {run.get(k)});
            }
        }
        for (int[] run : runs) for (int c : run) inWay[c] = true;

        // two or three wide: a door's frame in a wall's line (a row of cells across, a wall at each end, gone through
        // by every one of them), one or two rows thick, the place wider past it on both sides (or out from under the
        // roof); a passage two wide and longer is a corridor with such a way at each mouth where it widens
        List<int[][]> wides = new ArrayList<>(); // {{passage axis, width}, cells row by row from back to on}
        for (int ax = 0; ax < 2; ax++) {
            int back = ax == 0 ? 0 : 3, on = ax == 0 ? 2 : 1, left = ax == 0 ? 3 : 0, right = ax == 0 ? 1 : 2;
            // each cell's row across: its index, or -1 for a row that is no frame's
            int[] rowOf = new int[total];
            java.util.Arrays.fill(rowOf, -2);
            List<int[]> rows = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                if (near[i] == null || rowOf[i] != -2) continue;
                int start = i;
                for (int guard = 0; guard < total; guard++) {
                    int p = near[start][left];
                    if (p < 0 || near[p] == null) break;
                    start = p;
                }
                LongArrayList row = new LongArrayList();
                // walls at both ends (not the roof's edge), every cell gone through both ways, none a way one wide
                boolean frame = near[start][left] < 0;
                int last = start;
                for (int c = start, guard = 0; c >= 0 && near[c] != null && guard < total; c = near[c][right], guard++) {
                    row.add(c);
                    last = c;
                    frame &= near[c][back] >= 0 && near[c][on] >= 0 && !inWay[c];
                }
                frame &= near[last][right] < 0 && row.size() >= 2 && row.size() <= WIDE;
                int id = frame ? rows.size() : -1;
                int[] cellsOf = new int[row.size()];
                for (int k = 0; k < cellsOf.length; k++) {
                    cellsOf[k] = (int) row.getLong(k);
                    rowOf[cellsOf[k]] = id;
                }
                if (frame) rows.add(cellsOf);
            }
            boolean[] used = new boolean[rows.size()];
            for (int s0 = 0; s0 < rows.size(); s0++) {
                if (used[s0]) continue;
                int w = rows.get(s0).length;
                // back to the passage's first row, then on through it
                int first = s0;
                for (int guard = 0; guard < rows.size(); guard++) {
                    int prev = before(near, rowOf, rows, first, back);
                    if (prev < 0 || used[prev] || prev == s0) break;
                    first = prev;
                }
                List<int[]> passage = new ArrayList<>();
                for (int at = first; at >= 0 && !used[at]; at = before(near, rowOf, rows, at, on)) {
                    used[at] = true;
                    passage.add(rows.get(at));
                }
                boolean widerA = wider(near, passage.get(0), back, left, right), widerB = wider(near, passage.get(passage.size() - 1), on, left, right);
                if (passage.size() <= WIDE_LONG) {
                    if (!widerA || !widerB) continue;
                    int[][] way = new int[passage.size() + 1][];
                    way[0] = new int[] {ax, w};
                    for (int k = 0; k < passage.size(); k++) way[k + 1] = passage.get(k);
                    wides.add(way);
                } else if (w == 2) {
                    if (widerA) wides.add(new int[][] {{ax, w}, passage.get(0)});
                    if (widerB) wides.add(new int[][] {{ax, w}, passage.get(passage.size() - 1)});
                }
            }
        }
        for (int[][] way : wides) for (int k = 1; k < way.length; k++) for (int c : way[k]) inWay[c] = true;

        // rooms: the cells under the roof that are in no way, joined
        int[] room = new int[total];
        List<int[]> sums = new ArrayList<>(); // {count, sum x, sum y, sum z}
        int[] stack = new int[total];
        for (int pass = 0; pass < 2; pass++) {
            java.util.Arrays.fill(room, OUTSIDE);
            sums.clear();
            for (int i = 0; i < total; i++) {
                if (near[i] == null || inWay[i] || room[i] != OUTSIDE) continue;
                int id = sums.size(), top = 0;
                int[] sum = new int[4];
                stack[top++] = i;
                room[i] = id;
                while (top > 0) {
                    int c = stack[--top];
                    long at = cells.getLong(c);
                    sum[0]++;
                    sum[1] += BlockPos.getX(at);
                    sum[2] += BlockPos.getY(at);
                    sum[3] += BlockPos.getZ(at);
                    for (int k = 0; k < 4; k++) {
                        int n = near[c][k];
                        if (n < 0 || near[n] == null || inWay[n] || room[n] != OUTSIDE) continue;
                        room[n] = id;
                        stack[top++] = n;
                    }
                }
                sums.add(sum);
            }
            if (pass == 1) break;
            // a way one wide with no door that ends in a room of a cell or two is no way: a walk one wide with
            // openings along it (a gallery, a balcony) is one corridor, not a row of closets; read once more without
            boolean dropped = false;
            for (java.util.Iterator<int[]> it = runs.iterator(); it.hasNext(); ) {
                int[] run = it.next();
                int ax = axis[run[0]], back = ax == 0 ? 0 : 3, on = ax == 0 ? 2 : 1;
                boolean hasDoor = false;
                for (int c : run) hasDoor |= door[c];
                if (hasDoor) continue;
                int ea = near[run[0]][back], eb = near[run[run.length - 1]][on];
                if (!(room[ea] >= 0 && sums.get(room[ea])[0] <= TINY || room[eb] >= 0 && sums.get(room[eb])[0] <= TINY)) continue;
                for (int c : run) inWay[c] = false;
                it.remove();
                dropped = true;
            }
            if (!dropped) break;
        }
        int rooms = sums.size();
        int[] size = new int[rooms];
        long[] mid = new long[rooms];
        double[] best = new double[rooms];
        java.util.Arrays.fill(best, Double.MAX_VALUE);
        for (int i = 0; i < total; i++) {
            int id = room[i];
            if (id < 0) continue;
            int[] s = sums.get(id);
            size[id] = s[0];
            long at = cells.getLong(i);
            double dx = BlockPos.getX(at) - (double) s[1] / s[0], dy = BlockPos.getY(at) - (double) s[2] / s[0], dz = BlockPos.getZ(at) - (double) s[3] / s[0];
            double d = dx * dx + dy * dy * 4 + dz * dz;
            if (d < best[id]) {
                best[id] = d;
                mid[id] = at;
            }
        }

        List<Way> ways = new ArrayList<>();
        // the ways one wide
        for (int[] run : runs) {
            int ax = axis[run[0]], back = ax == 0 ? 0 : 3, on = ax == 0 ? 2 : 1;
            int endA = near[run[0]][back], endB = near[run[run.length - 1]][on];
            int a = room[endA], b = room[endB];
            if (a == b) continue; // a way round a pillar, or both ends out from under the roof: it parts nothing
            boolean hasDoor = false;
            long[] at = new long[run.length];
            for (int k = 0; k < run.length; k++) {
                at[k] = cells.getLong(run[k]);
                hasDoor |= door[run[k]];
            }
            int dir = CARDINAL[on];
            long[] row = {cells.getLong(endA)}, rowB = {cells.getLong(endB)};
            Way w = new Way(at, dir, a, b, 1, hasDoor, beside(r, row, dir), beside(r, rowB, Dirs.opposite(dir))).far(r, row, rowB);
            // room a is never OUTSIDE
            Way made = a != OUTSIDE ? w : w.flipped();
            made.sill = BlockPos.getY(at[0]) > BlockPos.getY(cells.getLong(endA)) || BlockPos.getY(at[at.length - 1]) > BlockPos.getY(cells.getLong(endB));
            ways.add(made);
        }
        // the ways two and three wide
        for (int[][] way : wides) {
            int ax = way[0][0], back = ax == 0 ? 0 : 3, on = ax == 0 ? 2 : 1, w = way[0][1];
            int[] firstRow = way[1], lastRow = way[way.length - 1];
            long[] rowA = new long[w], rowB = new long[w];
            int a = OUTSIDE, b = OUTSIDE;
            for (int k = 0; k < w; k++) {
                int ea = near[firstRow[k]][back], eb = near[lastRow[k]][on];
                rowA[k] = cells.getLong(ea);
                rowB[k] = cells.getLong(eb);
                if (a == OUTSIDE) a = room[ea];
                if (b == OUTSIDE) b = room[eb];
            }
            if (a == b) continue;
            boolean hasDoor = false;
            LongArrayList at = new LongArrayList();
            for (int k = 1; k < way.length; k++)
                for (int c : way[k]) {
                    at.add(cells.getLong(c));
                    hasDoor |= door[c];
                }
            int dir = CARDINAL[on];
            Way wy = new Way(at.toLongArray(), dir, a, b, w, hasDoor, beside(r, rowA, dir), beside(r, rowB, Dirs.opposite(dir))).far(r, rowA, rowB);
            Way made = a != OUTSIDE ? wy : wy.flipped();
            made.sill = BlockPos.getY(cells.getLong(firstRow[0])) > BlockPos.getY(rowA[0]) || BlockPos.getY(cells.getLong(lastRow[0])) > BlockPos.getY(rowB[0]);
            ways.add(made);
        }
        // the open sides: a room's cells with a cell out from under the roof next to them, in rows along the edge
        boolean[][] edgeSeen = new boolean[4][total];
        for (int i = 0; i < total; i++) {
            if (room[i] < 0) continue;
            for (int k = 0; k < 4; k++) {
                if (edgeSeen[k][i] || !edge(near, i, k)) continue;
                int left = (k + 3) & 3, right = (k + 1) & 3;
                int start = i;
                for (int guard = 0; guard < total; guard++) {
                    int p = near[start][left];
                    if (p < 0 || room[p] != room[i] || !edge(near, p, k)) break;
                    start = p;
                }
                // the walk to the row's left end may end on a cell of a row made already (an edge that goes round, a
                // step whose way back is another cell): then the row starts at this cell
                if (edgeSeen[k][start]) start = i;
                LongArrayList in = new LongArrayList(), out = new LongArrayList();
                for (int c = start; c >= 0 && room[c] == room[i] && !edgeSeen[k][c] && edge(near, c, k); c = near[c][right]) {
                    edgeSeen[k][c] = true;
                    in.add(cells.getLong(c));
                    out.add(cells.getLong(near[c][k]));
                }
                if (in.isEmpty()) continue;
                int dir = CARDINAL[k];
                ways.add(new Way(in.toLongArray(), dir, room[i], OUTSIDE, in.size(), false, beside(r, in.toLongArray(), dir),
                        beside(r, out.toLongArray(), Dirs.opposite(dir))));
            }
        }

        // a corridor: most of its cells have room for one or two across, and it is long for that
        byte[] kind = new byte[rooms];
        int[] one = new int[rooms], two = new int[rooms];
        for (int i = 0; i < total; i++) {
            int id = room[i];
            if (id < 0) continue;
            int ns = 1, ew = 1;
            for (int k = 0; k < 4; k++) {
                int len = 0;
                for (int c = near[i][k]; c >= 0 && near[c] != null && room[c] == id && len < CORRIDOR_WIDE; c = near[c][k]) len++;
                if ((k & 1) == 0) ns += len;
                else ew += len;
            }
            int across = Math.min(ns, ew);
            if (across == 1) one[id]++;
            if (across <= CORRIDOR_WIDE) two[id]++;
        }
        for (int id = 0; id < rooms; id++)
            kind[id] = one[id] * 5 >= size[id] * 4 && size[id] >= CORRIDOR_LONG || two[id] * 5 >= size[id] * 4 && size[id] >= CORRIDOR_LONG * 2 ? CORRIDOR : ROOM;

        // stairs: a flight, steps up one after another (a cell with a step up to the next and a step down to the one
        // before, and the cells at its two ends); one step alone is a sill, a seat, a carpet's edge. Ladders: a cell climbed
        LongArrayList stairs = new LongArrayList(), ladders = new LongArrayList();
        boolean[] stair = new boolean[total];
        // windows: a wall no mob steps through, seen through at head height (a hole over a sill, glass, bars)
        List<Window> windows = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            if (near[i] == null) continue;
            long c = cells.getLong(i);
            int x = BlockPos.getX(c), y = BlockPos.getY(c), z = BlockPos.getZ(c);
            int up = -1, down = -1;
            for (int k = 0; k < 4; k++) {
                int n = near[i][k];
                if (n >= 0) {
                    int ny = BlockPos.getY(cells.getLong(n));
                    if (ny > y) up = n;
                    else if (ny < y) down = n;
                    continue;
                }
                if (room[i] < 0) continue;
                int wx = x + Dirs.DX[CARDINAL[k]], wz = z + Dirs.DZ[CARDINAL[k]];
                int feet = src.voxel(wx, y, wz), head = src.voxel(wx, y + 1, wz);
                if (Voxel.sight(head) || ((feet | head) & Voxel.DOOR) != 0) continue;
                // a sill that hides under a hole, or glass (bars) at head height; a rail seen over is cover, not a window
                if (Voxel.open(head) && !Voxel.sight(feet)) continue;
                windows.add(new Window(BlockPos.asLong(wx, y + 1, wz), CARDINAL[k], room[i], !Voxel.open(head)));
            }
            if ((r.flags(x, y, z) & SectionCells.CLIMB) != 0) ladders.add(c);
            else if (up >= 0 && down >= 0) stair[i] = stair[up] = stair[down] = true;
        }
        for (int i = 0; i < total; i++) if (stair[i] && near[i] != null && (r.flags(BlockPos.getX(cells.getLong(i)), BlockPos.getY(cells.getLong(i)), BlockPos.getZ(cells.getLong(i))) & SectionCells.CLIMB) == 0) stairs.add(cells.getLong(i));

        Long2IntOpenHashMap roomOf = new Long2IntOpenHashMap();
        roomOf.defaultReturnValue(OUTSIDE);
        for (int i = 0; i < total; i++) if (room[i] >= 0) roomOf.put(cells.getLong(i), room[i]);
        return new Plan(size, mid, ways.toArray(new Way[0]), cut, roomOf, kind, stairs.toLongArray(), ladders.toLongArray(), windows.toArray(new Window[0]));
    }

    /**
     * The cells under the roof joined to the seed that are no building's (null: none). A cell's shell is the block under
     * it, the roof over it and the blocks of its walls (where a mob does not step on): of the ground as the world made
     * it more than of blocks put there (the roof counts most, then the floor), the cell is wild (a cave, a hollow under
     * a tree). A wild cell is a building's
     * all the same between two cells that are not wild (a doorway through rock), and within DWELT steps of a sign that someone lives there (AiBlockKinds.MARKER: a chest, a bed, a
     * machine) through wild cells, not through a door: a cellar dug out of the rock, and not the cave past its door.
     */
    private static @Nullable LongOpenHashSet wild(CellSource src, PathSearch.Reader r, long seed, int reach, int most) {
        int sx = BlockPos.getX(seed), sz = BlockPos.getZ(seed);
        LongArrayList cells = new LongArrayList();
        LongOpenHashSet seen = new LongOpenHashSet(), wild = new LongOpenHashSet();
        LongArrayList dwelt = new LongArrayList();
        cells.add(seed);
        seen.add(seed);
        for (int head = 0; head < cells.size() && cells.size() <= most; head++) {
            long c = cells.getLong(head);
            int x = BlockPos.getX(c), y = BlockPos.getY(c), z = BlockPos.getZ(c);
            int natural = 0, built = 0;
            boolean marker = false;
            int[] shell = new int[10];
            int n = 0;
            shell[n++] = src.kind(x, y - 1, z);
            for (int up = 2; up < 2 + CellBuilder.ROOF_REACH; up++) {
                int at = src.voxel(x, y + up, z);
                if (!Voxel.open(at) || Voxel.sight(at)) {
                    shell[n++] = src.kind(x, y + up, z);
                    break;
                }
            }
            int roofed = n;
            for (int k = 0; k < 4; k++) {
                long next = PathSearch.step(r, x, y, z, CARDINAL[k]);
                if (next == PathSearch.NONE) {
                    int wx = x + Dirs.DX[CARDINAL[k]], wz = z + Dirs.DZ[CARDINAL[k]];
                    shell[n++] = src.kind(wx, y, wz);
                    shell[n++] = src.kind(wx, y + 1, wz);
                    continue;
                }
                int nx = BlockPos.getX(next), nz = BlockPos.getZ(next);
                if (seen.contains(next) || Math.abs(nx - sx) > reach || Math.abs(nz - sz) > reach) continue;
                if ((r.flags(nx, BlockPos.getY(next), nz) & SectionCells.ROOF) == 0) continue;
                seen.add(next);
                cells.add(next);
            }
            for (int i = 0; i < n; i++) {
                // the roof counts for three, the floor for two, a wall's block for one: a roof put over bare ground is a
                // house, a floor laid under the rock's roof a path through a cave
                int w = i == 0 ? 2 : i < roofed ? 3 : 1;
                if (shell[i] == AiBlockKinds.NATURAL) natural += w;
                else if (shell[i] == AiBlockKinds.BUILT) built += w;
                else if (shell[i] == AiBlockKinds.MARKER) {
                    built += w;
                    marker = true;
                }
            }
            if (natural <= built) continue;
            wild.add(c);
            if (marker) dwelt.add(c);
        }
        if (wild.isEmpty()) return null;
        // a wild cell between two that are not (a doorway cut through rock between two built rooms: mostly wall) is the building's
        LongArrayList between = new LongArrayList();
        for (long c : wild) {
            int x = BlockPos.getX(c), y = BlockPos.getY(c), z = BlockPos.getZ(c), tame = 0;
            for (int k = 0; k < 4; k++) {
                long next = PathSearch.step(r, x, y, z, CARDINAL[k]);
                if (next != PathSearch.NONE && seen.contains(next) && !wild.contains(next)) tame++;
            }
            if (tame >= 2) between.add(c);
        }
        for (long c : between) wild.remove(c);
        // lived in: DWELT steps from a sign of it, through wild cells, a door's cell the last
        LongArrayList at = dwelt, then = new LongArrayList();
        for (long c : dwelt) wild.remove(c);
        for (int step = 0; step < DWELT && !at.isEmpty(); step++) {
            for (int i = 0; i < at.size(); i++) {
                long c = at.getLong(i);
                int x = BlockPos.getX(c), y = BlockPos.getY(c), z = BlockPos.getZ(c);
                if ((src.voxel(x, y, z) & Voxel.DOOR) != 0) continue;
                for (int k = 0; k < 4; k++) {
                    long next = PathSearch.step(r, x, y, z, CARDINAL[k]);
                    if (next != PathSearch.NONE && wild.remove(next)) then.add(next);
                }
            }
            LongArrayList t = at == dwelt ? new LongArrayList() : at;
            t.clear();
            at = then;
            then = t;
        }
        return wild.isEmpty() ? null : wild;
    }

    /** the frame's row next to this one in cardinal k: the row its cells step into, cell for cell, as wide; or -1 */
    private static int before(int[][] near, int[] rowOf, List<int[]> rows, int row, int k) {
        int[] c = rows.get(row);
        int to = near[c[0]][k];
        if (to < 0 || near[to] == null || rowOf[to] < 0) return -1;
        int[] next = rows.get(rowOf[to]);
        if (next.length != c.length) return -1;
        for (int i = 0; i < c.length; i++) if (near[c[i]][k] != next[i]) return -1;
        return rowOf[to];
    }

    /** past this row of a frame in cardinal k the place is wider than it (past either end), or out from under the roof */
    private static boolean wider(int[][] near, int[] row, int k, int left, int right) {
        int a = near[row[0]][k], b = near[row[row.length - 1]][k];
        for (int c : row) if (near[near[c][k]] == null) return true;
        return near[a][left] >= 0 || near[b][right] >= 0;
    }

    /** cell i of a room has, in cardinal k, a cell out from under the roof */
    private static boolean edge(int[][] near, int i, int k) {
        if (near[i] == null) return false;
        int n = near[i][k];
        return n >= 0 && near[n] == null;
    }

    private static long[] reverse(long[] a) {
        long[] out = new long[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[a.length - 1 - i];
        return out;
    }

    /**
     * The cells beside an opening at one end: a step sideways from either end of the row of cells just past it, kept
     * when the wall stands head high between the cell and the other end (direction `through`).
     */
    private static long[] beside(PathSearch.Reader r, long[] row, int through) {
        LongArrayList out = new LongArrayList(2);
        if (row.length == 0) return out.toLongArray();
        int left = (through + 6) & 7, right = (through + 2) & 7;
        side(r, row[0], left, through, out);
        side(r, row[row.length - 1], right, through, out);
        if (row.length > 1) {
            // the row's order is not known to run left to right: try both ends both ways
            side(r, row[0], right, through, out);
            side(r, row[row.length - 1], left, through, out);
        }
        return out.toLongArray();
    }

    /** for each cell beside an opening, the next one the same way from the row before it (behind the wall too), or itself */
    private static long[] further(PathSearch.Reader r, long[] row, long[] beside, int through) {
        long[] out = beside.clone();
        for (int i = 0; i < out.length; i++) {
            long c = out[i];
            int best = Integer.MAX_VALUE;
            long from = row[0];
            for (long q : row) {
                int d = Math.abs(BlockPos.getX(q) - BlockPos.getX(c)) + Math.abs(BlockPos.getZ(q) - BlockPos.getZ(c));
                if (d < best) {
                    best = d;
                    from = q;
                }
            }
            int dx = Integer.signum(BlockPos.getX(c) - BlockPos.getX(from)), dz = Integer.signum(BlockPos.getZ(c) - BlockPos.getZ(from));
            for (int d = 0; d < 8; d += 2) {
                if (Dirs.DX[d] != dx || Dirs.DZ[d] != dz) continue;
                LongArrayList one = new LongArrayList(1);
                side(r, c, d, through, one);
                if (!one.isEmpty()) out[i] = one.getLong(0);
            }
        }
        return out;
    }

    private static void side(PathSearch.Reader r, long from, int d, int through, LongArrayList out) {
        long c = PathSearch.step(r, BlockPos.getX(from), BlockPos.getY(from), BlockPos.getZ(from), d);
        if (c == PathSearch.NONE || out.contains(c)) return;
        int x = BlockPos.getX(c), y = BlockPos.getY(c), z = BlockPos.getZ(c);
        SectionCells s = r.section(x, y, z);
        int i = s.find(x & 15, y & 15, z & 15);
        if (i >= 0 && SectionCells.height(s.cover(i), through) == 2) out.add(c);
    }
}
