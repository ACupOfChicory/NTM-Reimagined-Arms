package acup.gunfeel.ai;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/**
 * A* over the cells of the tactical map, off the server thread. From a cell a mob walks to the cell beside it on the
 * same level, steps up one block (with room over its head to jump, or onto a slab), or drops up to three blocks
 * where the column it walks into is open; diagonally only on the level and with both blocks either side open (no
 * cutting corners). A step costs its length, more up, a little more down, double in water, plus what the caller's
 * {@link Cost} adds (exposure to a threat, say). Short of nodes it returns the path to the cell nearest the goal
 * (the mob walks it and asks again from there). Sections it met with no cells are listed, so the map can build them.
 */
public final class PathSearch {
    /** the most a mob drops in one step */
    static final int MAX_DROP = 3;
    static final float UP = 0.6F, DOWN = 0.3F, DIAGONAL = 1.4142135F;
    static final long NONE = Long.MIN_VALUE;

    private PathSearch() {
    }

    /** what a caller adds to entering a cell, at least 0 */
    @FunctionalInterface
    public interface Cost {
        float extra(int x, int y, int z);

        /** the cost one search uses (one that keeps a cache gives each search its own) */
        default Cost forSearch() {
            return this;
        }

        /** the cost as things are now, taken on the server thread before each search (one that follows the world) */
        default Cost now() {
            return this;
        }

        Cost NONE = (x, y, z) -> 0F;

        /** both on a step (each search its own of each) */
        static Cost sum(Cost a, Cost b) {
            if (a == NONE) return b;
            if (b == NONE) return a;
            return new Cost() {
                @Override
                public float extra(int x, int y, int z) {
                    return a.extra(x, y, z) + b.extra(x, y, z);
                }

                @Override
                public Cost forSearch() {
                    return sum(a.forSearch(), b.forSearch());
                }

                @Override
                public Cost now() {
                    return sum(a.now(), b.now());
                }
            };
        }
    }

    public static final class Result {
        /** cells from the start to the end, as BlockPos longs (the start included) */
        public final long[] cells;
        /** the end is within the goal radius; else it is the cell nearest the goal the search got to */
        public final boolean reached;
        public final int expanded;
        public final float cost;
        /** sections met on the way without published cells */
        public final long[] missing;
        /** the sections the path runs through and their build stamps then */
        public final long[] keys;
        public final int[] stamps;
        public final long nanos;

        Result(long[] cells, boolean reached, int expanded, float cost, long[] missing, long[] keys, int[] stamps, long nanos) {
            this.cells = cells;
            this.reached = reached;
            this.expanded = expanded;
            this.cost = cost;
            this.missing = missing;
            this.keys = keys;
            this.stamps = stamps;
            this.nanos = nanos;
        }

        public boolean found() {
            return cells.length > 0;
        }

        static Result none(int expanded, long[] missing, long nanos) {
            return new Result(new long[0], false, expanded, 0F, missing, new long[0], new int[0], nanos);
        }
    }

    // ------------------------------------------------------------------ reading cells, cached per search

    static final class Reader {
        final CellSource src;
        final Long2ObjectOpenHashMap<SectionCells> sections = new Long2ObjectOpenHashMap<>();
        final LongOpenHashSet missing = new LongOpenHashSet();
        private static final SectionCells ABSENT = new SectionCells(0, -1, new short[0], new int[0], new byte[0], new short[257], 0);

        Reader(CellSource src) {
            this.src = src;
        }

        SectionCells section(int x, int y, int z) {
            long key = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
            SectionCells c = sections.get(key);
            if (c == null) {
                c = src.cells(key);
                if (c == null) {
                    missing.add(key);
                    c = ABSENT;
                }
                sections.put(key, c);
            }
            return c;
        }

        /** the cell's index in its section, or -1 */
        int cell(int x, int y, int z) {
            SectionCells c = section(x, y, z);
            return c == ABSENT ? -1 : c.find(x & 15, y & 15, z & 15);
        }

        boolean has(int x, int y, int z) {
            return cell(x, y, z) >= 0;
        }

        int flags(int x, int y, int z) {
            SectionCells c = section(x, y, z);
            int i = c == ABSENT ? -1 : c.find(x & 15, y & 15, z & 15);
            return i < 0 ? 0 : c.flags(i);
        }

        boolean open(int x, int y, int z) {
            return Voxel.open(src.voxel(x, y, z));
        }
    }

    // ------------------------------------------------------------------ the cell a mob stands in

    /** the cell at or next to a mob's feet (it may stand on a slab or a block's edge), or Long.MIN_VALUE */
    public static long snap(CellSource src, double x, double y, double z) {
        Reader r = new Reader(src);
        int bx = (int) Math.floor(x), by = (int) Math.floor(y + 0.01), bz = (int) Math.floor(z);
        for (int dy : new int[] {0, 1, -1, -2})
            if (r.has(bx, by + dy, bz)) return BlockPos.asLong(bx, by + dy, bz);
        // on the edge of a block: the nearest cell beside, at the same height
        double best = Double.MAX_VALUE;
        long found = NONE;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = 0; dy >= -1; dy--) {
                    if (dx == 0 && dz == 0 || !r.has(bx + dx, by + dy, bz + dz)) continue;
                    double ex = bx + dx + 0.5 - x, ez = bz + dz + 0.5 - z, d = ex * ex + ez * ez;
                    if (d < best) {
                        best = d;
                        found = BlockPos.asLong(bx + dx, by + dy, bz + dz);
                    }
                }
        return found;
    }

    // ------------------------------------------------------------------ the search

    /**
     * A path from the start cell to within goalRadius blocks across (and one up or down) of the goal cell, at most
     * maxNodes cells expanded.
     */
    public static Result find(CellSource src, long start, long goal, double goalRadius, int maxNodes, Cost cost) {
        long t0 = System.nanoTime();
        cost = cost.forSearch();
        Reader r = new Reader(src);
        int gx = BlockPos.getX(goal), gy = BlockPos.getY(goal), gz = BlockPos.getZ(goal);
        double r2 = goalRadius * goalRadius;
        int sx = BlockPos.getX(start), sy = BlockPos.getY(start), sz = BlockPos.getZ(start);
        if (!r.has(sx, sy, sz)) return Result.none(0, r.missing.toLongArray(), System.nanoTime() - t0);

        Nodes n = new Nodes(Math.min(maxNodes * 9 + 16, 1 << 20));
        int first = n.add(start, 0F, heuristic(sx, sz, gx, gz), -1);
        int nearest = first;
        float nearestH = n.f[first];
        int expanded = 0, reachedNode = -1;
        while (n.size() > 0) {
            int cur = n.pop();
            long p = n.pos[cur];
            int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
            double ex = x - gx, ez = z - gz;
            if (ex * ex + ez * ez <= r2 && Math.abs(y - gy) <= 1) {
                reachedNode = cur;
                break;
            }
            if (expanded++ >= maxNodes) break;
            float h = n.f[cur] - n.g[cur];
            if (h < nearestH) {
                nearestH = h;
                nearest = cur;
            }
            boolean water = (r.flags(x, y, z) & SectionCells.WATER) != 0;
            for (int d = 0; d < 8; d++) {
                long next = step(r, x, y, z, d);
                if (next == NONE) continue;
                int nx = BlockPos.getX(next), ny = BlockPos.getY(next), nz = BlockPos.getZ(next);
                float c = (d & 1) == 1 ? DIAGONAL : 1F;
                if (ny > y) c += UP;
                else if (ny < y) c += DOWN * (y - ny);
                if (water || (r.flags(nx, ny, nz) & SectionCells.WATER) != 0) c *= 2F;
                c += cost.extra(nx, ny, nz);
                n.relax(next, n.g[cur] + c, heuristic(nx, nz, gx, gz), cur);
            }
        }
        int end = reachedNode >= 0 ? reachedNode : nearest;
        return path(r, n, end, reachedNode >= 0, expanded, System.nanoTime() - t0);
    }

    /** octile distance across: no step costs less than its length */
    static float heuristic(int x, int z, int gx, int gz) {
        int dx = Math.abs(x - gx), dz = Math.abs(z - gz);
        int lo = Math.min(dx, dz), hi = Math.max(dx, dz);
        return hi - lo + lo * DIAGONAL;
    }

    /** the cell one step in direction d from a cell, or NONE */
    static long step(Reader r, int x, int y, int z, int d) {
        int dx = Dirs.DX[d], dz = Dirs.DZ[d];
        int tx = x + dx, tz = z + dz;
        if ((d & 1) == 1) {
            // diagonal: on the level, both sides open at the feet and the head
            if (!r.open(tx, y, z) || !r.open(tx, y + 1, z) || !r.open(x, y, tz) || !r.open(x, y + 1, tz)) return NONE;
            return r.has(tx, y, tz) ? BlockPos.asLong(tx, y, tz) : NONE;
        }
        if (r.has(tx, y, tz)) return !AiDoors.pass && Voxel.shutDoor(r.src.voxel(tx, y, tz)) ? NONE : BlockPos.asLong(tx, y, tz);
        // up a block: room to jump over the head, or a slab that is stepped onto
        if (r.has(tx, y + 1, tz) && (r.open(x, y + 2, z) || Voxel.move(r.src.voxel(tx, y, tz)) == Voxel.LOW))
            return BlockPos.asLong(tx, y + 1, tz);
        // down: the column walked into is open down to the cell's head
        if (!r.open(tx, y, tz) || !r.open(tx, y + 1, tz)) return NONE;
        for (int k = 1; k <= MAX_DROP; k++) {
            if (r.has(tx, y - k, tz)) return BlockPos.asLong(tx, y - k, tz);
            if (!r.open(tx, y - k, tz)) return NONE;
        }
        return NONE;
    }

    private static Result path(Reader r, Nodes n, int end, boolean reached, int expanded, long nanos) {
        int len = 0;
        for (int i = end; i >= 0; i = n.parent[i]) len++;
        long[] cells = new long[len];
        int k = len;
        for (int i = end; i >= 0; i = n.parent[i]) cells[--k] = n.pos[i];
        Long2IntOpenHashMap stamps = new Long2IntOpenHashMap();
        for (long c : cells) {
            long key = SectionPos.asLong(BlockPos.getX(c) >> 4, BlockPos.getY(c) >> 4, BlockPos.getZ(c) >> 4);
            if (stamps.containsKey(key)) continue;
            SectionCells s = r.sections.get(key);
            stamps.put(key, s == null ? -1 : s.stamp);
        }
        long[] keys = stamps.keySet().toLongArray();
        int[] st = new int[keys.length];
        for (int i = 0; i < keys.length; i++) st[i] = stamps.get(keys[i]);
        return new Result(cells, reached, expanded, n.g[end], r.missing.toLongArray(), keys, st, nanos);
    }

    // ------------------------------------------------------------------ nodes and the open heap

    private static final class Nodes {
        final Long2IntOpenHashMap index = new Long2IntOpenHashMap();
        long[] pos;
        float[] g, f;
        int[] parent, heapAt;
        int[] heap;
        int count, heapSize;
        final int limit;

        Nodes(int limit) {
            this.limit = limit;
            int cap = Math.min(limit, 1024);
            pos = new long[cap];
            g = new float[cap];
            f = new float[cap];
            parent = new int[cap];
            heapAt = new int[cap];
            heap = new int[cap];
            index.defaultReturnValue(-1);
        }

        int size() {
            return heapSize;
        }

        int add(long p, float gv, float h, int from) {
            if (count == pos.length) grow();
            int i = count++;
            pos[i] = p;
            g[i] = gv;
            f[i] = gv + h;
            parent[i] = from;
            index.put(p, i);
            heapAt[i] = heapSize;
            heap[heapSize++] = i;
            up(heapSize - 1);
            return i;
        }

        /** a cheaper way to a known cell moves it up; a closed cell (heapAt -1) is not opened again */
        void relax(long p, float gv, float h, int from) {
            int i = index.get(p);
            if (i < 0) {
                if (count < limit) add(p, gv, h, from);
                return;
            }
            if (gv >= g[i] || heapAt[i] < 0) return;
            g[i] = gv;
            f[i] = gv + h;
            parent[i] = from;
            up(heapAt[i]);
        }

        int pop() {
            int top = heap[0];
            heapAt[top] = -1;
            heapSize--;
            if (heapSize > 0) {
                heap[0] = heap[heapSize];
                heapAt[heap[0]] = 0;
                down(0);
            }
            return top;
        }

        private void up(int k) {
            int i = heap[k];
            while (k > 0) {
                int pk = (k - 1) >> 1;
                int pi = heap[pk];
                if (f[pi] <= f[i]) break;
                heap[k] = pi;
                heapAt[pi] = k;
                k = pk;
            }
            heap[k] = i;
            heapAt[i] = k;
        }

        private void down(int k) {
            int i = heap[k];
            while (true) {
                int c = 2 * k + 1;
                if (c >= heapSize) break;
                if (c + 1 < heapSize && f[heap[c + 1]] < f[heap[c]]) c++;
                if (f[heap[c]] >= f[i]) break;
                heap[k] = heap[c];
                heapAt[heap[k]] = k;
                k = c;
            }
            heap[k] = i;
            heapAt[i] = k;
        }

        private void grow() {
            int cap = Math.min(limit, pos.length * 2);
            pos = java.util.Arrays.copyOf(pos, cap);
            g = java.util.Arrays.copyOf(g, cap);
            f = java.util.Arrays.copyOf(f, cap);
            parent = java.util.Arrays.copyOf(parent, cap);
            heapAt = java.util.Arrays.copyOf(heapAt, cap);
            heap = java.util.Arrays.copyOf(heap, cap);
        }
    }
}
