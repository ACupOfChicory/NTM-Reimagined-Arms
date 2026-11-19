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
 * cutting corners). A step costs its length, more up, a little more down, {@link #WADE} times that wading,
 * {@link #SWIM} times swimming (a swim is taken where the way round is that much longer), plus what the caller's
 * {@link Cost} adds (exposure to a threat, say), {@link #SWIM_SEEN} times that where it swims (slow, and it does not
 * fire back). Kept dry (under fire), it begins no swim; one in water already swims out. Short of nodes it returns the
 * path to the cell nearest the goal (the mob walks it and asks again from there). Sections it met with no cells are
 * listed, so the map can build them.
 */
public final class PathSearch {
    /**
     * Whether a path may go through a shut door or gate: AI mobs open them on their way (the server setting aiDoors,
     * AiDoors.KEY; off for the stands' "before"). Set by AiDoors.tick every server tick, the only one that writes it
     * (AiRoomsTest besides); read by the path search on its own threads, by AiDoors (opening one ahead) and by AiHatch
     * (a hatch on the way up).
     */
    static volatile boolean doors = true;
    /** the most a mob drops in one step */
    static final int MAX_DROP = 3;
    /**
     * A drop this deep is not climbed back where it was made: a path to the cell nearest a goal there is no way to
     * stops before one unless there is a way back up round about, within {@link #BACK_NODES} cells (the first
     * {@link #BACK_CHECKS} such drops are looked at)
     */
    static final int NO_WAY_BACK = 2, BACK_NODES = 200, BACK_CHECKS = 3;
    static final float UP = 0.6F, DOWN = 0.3F, DIAGONAL = 1.4142135F;
    /** a rung climbed up or down a ladder: slow, and the mob on it can do nothing else */
    static final float CLIMB = 1F;
    /** a step wading, swimming costs this many times its length; what the caller adds counts this many times swimming */
    public static final float WADE = 2F, SWIM = 8F, SWIM_SEEN = 3F;
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
        /**
         * Every cell that can be walked to from the start was looked at and none is at the goal: no way there on this
         * map, however long the search (not so where it met sections without cells, or ran out of nodes).
         */
        public final boolean exhausted;
        /** exhausted: every section the search read and its build stamp then (the answer holds while they do); else empty */
        public final long[] readKeys;
        public final int[] readStamps;
        /** kept dry, it would have begun a swim: a way across water may be there (not exhausted, whatever else) */
        public boolean refusedSwim;

        Result(long[] cells, boolean reached, int expanded, float cost, long[] missing, long[] keys, int[] stamps, long nanos) {
            this(cells, reached, expanded, cost, missing, keys, stamps, nanos, false, new long[0], new int[0]);
        }

        Result(long[] cells, boolean reached, int expanded, float cost, long[] missing, long[] keys, int[] stamps, long nanos,
                boolean exhausted, long[] readKeys, int[] readStamps) {
            this.exhausted = exhausted;
            this.readKeys = readKeys;
            this.readStamps = readStamps;
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
        private static final SectionCells ABSENT = new SectionCells(0, -1, new short[0], new int[0], new short[0], new short[257], 0);

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
        return snap(new Reader(src), x, y, z);
    }

    private static final int[] SNAP_UP_DOWN = {0, 1, -1, -2};

    /** as {@link #snap(CellSource, double, double, double)}, with a reader kept by the caller (many snaps in a row) */
    static long snap(Reader r, double x, double y, double z) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y + 0.01), bz = (int) Math.floor(z);
        for (int dy : SNAP_UP_DOWN)
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

    /**
     * The cell under a place heard (a noise: AiBots.hear errs up and down too, its sigma up to two blocks): as {@link #snap}, else
     * lower down the column (a noise heard over its floor), else at the height of the one who heard it (flat ground).
     */
    public static long ground(CellSource src, double x, double y, double z, double ownY) {
        long cell = snap(src, x, y, z);
        if (cell == NONE) cell = snap(src, x, y - 3, z);
        if (cell == NONE) cell = snap(src, x, ownY, z);
        return cell;
    }

    // ------------------------------------------------------------------ the search

    /**
     * A path from the start cell to within goalRadius blocks across (and one up or down) of the goal cell, at most
     * maxNodes cells expanded.
     */
    public static Result find(CellSource src, long start, long goal, double goalRadius, int maxNodes, Cost cost) {
        return find(src, start, goal, goalRadius, maxNodes, cost, false);
    }

    /** as above; dry: no swim is begun from the dry (one that starts afloat swims on until it is out of the water) */
    public static Result find(CellSource src, long start, long goal, double goalRadius, int maxNodes, Cost cost, boolean dry) {
        return find(new Reader(src), start, goal, goalRadius, maxNodes, cost, true, dry);
    }

    private static Result find(Reader r, long start, long goal, double goalRadius, int maxNodes, Cost cost, boolean wayBack, boolean dry) {
        long t0 = System.nanoTime();
        cost = cost.forSearch();
        int gx = BlockPos.getX(goal), gy = BlockPos.getY(goal), gz = BlockPos.getZ(goal);
        double r2 = goalRadius * goalRadius;
        int sx = BlockPos.getX(start), sy = BlockPos.getY(start), sz = BlockPos.getZ(start);
        if (!r.has(sx, sy, sz)) return Result.none(0, r.missing.toLongArray(), System.nanoTime() - t0);
        boolean refused = false;

        Nodes n = new Nodes(Math.min(maxNodes * 9 + 16, 1 << 20));
        int first = n.add(start, 0F, heuristic(sx, sy, sz, gx, gy, gz), -1);
        int nearest = first;
        float nearestH = heuristic(sx, sz, gx, gz);
        int expanded = 0, reachedNode = -1;
        boolean cut = false;
        while (n.size() > 0) {
            int cur = n.pop();
            long p = n.pos[cur];
            int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
            double ex = x - gx, ez = z - gz;
            if (ex * ex + ez * ez <= r2 && Math.abs(y - gy) <= 1) {
                reachedNode = cur;
                break;
            }
            if (expanded++ >= maxNodes) {
                cut = true;
                break;
            }
            // the nearest across as before (a goal it cannot get to: the end nearest it on the map, not nearest its height)
            float h = heuristic(x, z, gx, gz);
            if (h < nearestH) {
                nearestH = h;
                nearest = cur;
            }
            int here = r.flags(x, y, z);
            for (int d = 0; d < 8; d++) {
                long next = step(r, x, y, z, d);
                if (next == NONE) continue;
                int nx = BlockPos.getX(next), ny = BlockPos.getY(next), nz = BlockPos.getZ(next);
                int there = r.flags(nx, ny, nz);
                boolean swim = ((here | there) & SectionCells.SWIM) != 0;
                // kept dry: no swim begun from the dry (one afloat at the start swims on till it is out)
                if (dry && (there & SectionCells.SWIM) != 0 && (here & SectionCells.SWIM) == 0) {
                    refused = true;
                    continue;
                }
                float c = (d & 1) == 1 ? DIAGONAL : 1F;
                if (ny > y) c += UP;
                else if (ny < y) c += DOWN * (y - ny);
                if (swim) c *= SWIM;
                else if (((here | there) & SectionCells.WATER) != 0) c *= WADE;
                float extra = cost.extra(nx, ny, nz);
                c += (there & SectionCells.SWIM) != 0 ? extra * SWIM_SEEN : extra;
                n.relax(next, n.g[cur] + c, heuristic(nx, ny, nz, gx, gy, gz), cur);
            }
            // up and down a ladder: a rung to the next one over or under it
            if ((here & SectionCells.CLIMB) != 0) for (int dy = -1; dy <= 1; dy += 2) {
                long next = climb(r, x, y, z, dy);
                if (next == NONE) continue;
                n.relax(next, n.g[cur] + CLIMB + cost.extra(x, y + dy, z), heuristic(x, y + dy, z, gx, gy, gz), cur);
            }
            // down a ladder past rungs knocked out (a grenade down the shaft): a drop straight down through the gap
            if ((here & SectionCells.CLIMB) != 0) {
                long next = gapDown(r, x, y, z);
                if (next != NONE) {
                    int fall = y - BlockPos.getY(next);
                    n.relax(next, n.g[cur] + (CLIMB + DOWN) * fall + cost.extra(x, y - fall, z), heuristic(x, y - fall, z, gx, gy, gz), cur);
                }
            }
            // up off a ladder's top rung through a shut hatch over it, onto the floor beside it
            if ((here & SectionCells.CLIMB) != 0) for (int d = 0; d < 8; d += 2) {
                long next = hatchUp(r, x, y, z, d);
                if (next == NONE) continue;
                int nx = BlockPos.getX(next), nz = BlockPos.getZ(next);
                n.relax(next, n.g[cur] + 2 * CLIMB + 1F + cost.extra(nx, y + 2, nz), heuristic(nx, y + 2, nz, gx, gy, gz), cur);
            }
        }
        int end = reachedNode >= 0 ? reachedNode : nearest;
        // kept out of water it meant to cross: not a dead end, only one for now
        boolean exhausted = reachedNode < 0 && !cut && n.size() == 0 && n.count < n.limit && r.missing.isEmpty() && !refused;
        Result res = path(r, n, end, reachedNode >= 0, expanded, exhausted, wayBack, t0);
        res.refusedSwim = refused;
        return res;
    }

    /**
     * The same with the height to go, never more than the way costs (the goal is reached a block over or under it: that
     * one is free). Down: every block down costs DOWN whatever the step, and a step (length 1 at least, a rung 1) goes
     * MAX_DROP down at most, so the steps are as many as the blocks across or a MAX_DROP-th of those down; up: a block
     * up costs UP_LEAST at least (a rung; a step up 1 + UP, through a hatch 3 for 2), and it may be along the way across.
     * A search for a cell far under (a cellar, a bunker down a shaft) does not first go over all the ground round about.
     */
    static float heuristic(int x, int y, int z, int gx, int gy, int gz) {
        int dy = Math.abs(y - gy) - 1;
        float across = heuristic(x, z, gx, gz);
        if (dy <= 0) return across;
        return y > gy ? DOWN * dy + Math.max(across, dy / (float) MAX_DROP) : Math.max(across, dy * UP_LEAST);
    }

    /** what a block up costs at the least */
    static final float UP_LEAST = Math.min(1F + UP, CLIMB);

    /** octile distance across: no step costs less than its length */
    static float heuristic(int x, int z, int gx, int gz) {
        int dx = Math.abs(x - gx), dz = Math.abs(z - gz);
        int lo = Math.min(dx, dz), hi = Math.max(dx, dz);
        return hi - lo + lo * DIAGONAL;
    }

    /** the rung over (dy 1) or under (dy -1) a cell on a ladder, both of them on it, or NONE */
    static long climb(Reader r, int x, int y, int z, int dy) {
        if ((r.flags(x, y, z) & SectionCells.CLIMB) == 0 || (r.flags(x, y + dy, z) & SectionCells.CLIMB) == 0) return NONE;
        return r.has(x, y + dy, z) ? BlockPos.asLong(x, y + dy, z) : NONE;
    }

    /** rungs knocked out of a ladder gone down past at most, blocks (the drop onto what is under them no more than MAX_DROP) */
    static final int GAP_MOST = MAX_DROP - 1;

    /**
     * Down a ladder from a rung over a gap in it (1 to GAP_MOST blocks open, no rung): the rung or the floor's cell under
     * the gap, straight down, or NONE (a drop of more is no way: past MAX_DROP it hurts, and up again there is none)
     */
    static long gapDown(Reader r, int x, int y, int z) {
        if ((r.flags(x, y, z) & SectionCells.CLIMB) == 0 || (r.flags(x, y - 1, z) & SectionCells.CLIMB) != 0) return NONE;
        for (int g = 1; g <= GAP_MOST; g++) {
            if (!r.open(x, y - g, z) || r.has(x, y - g, z)) return NONE;
            if (r.has(x, y - g - 1, z)) return BlockPos.asLong(x, y - g - 1, z);
        }
        return NONE;
    }

    /**
     * Up out of a shaft: from a rung with a hatch over its head, shut (AiDoors lifts it climbing up) or lifted, to the cell two up
     * beside it in direction d (straight only), the hatch's own cell and the one over it open but for the hatch
     * (the way down is a step off that cell and a drop through the hatch: step)
     */
    static long hatchUp(Reader r, int x, int y, int z, int d) {
        if ((r.flags(x, y, z) & SectionCells.CLIMB) == 0 || !hatch(r, x, y + 1, z) && !lifted(r, x, y + 1, z) || !r.open(x, y + 2, z)) return NONE;
        int tx = x + Dirs.DX[d], tz = z + Dirs.DZ[d];
        return r.has(tx, y + 2, tz) && r.open(tx, y + 3, tz) ? BlockPos.asLong(tx, y + 2, tz) : NONE;
    }

    /** a hatch lifted already: a door's block open to go through */
    private static boolean lifted(Reader r, int x, int y, int z) {
        int v = r.src.voxel(x, y, z);
        return (v & Voxel.DOOR) != 0 && Voxel.move(v) == Voxel.OPEN;
    }

    /** a shut hatch a hand opens (a trapdoor: AiDoors lifts it) on the way down */
    private static boolean hatch(Reader r, int x, int y, int z) {
        return doors && Voxel.shutDoor(r.src.voxel(x, y, z));
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
        if (r.has(tx, y, tz)) return !doors && Voxel.shutDoor(r.src.voxel(tx, y, tz)) ? NONE : BlockPos.asLong(tx, y, tz);
        // up a block: room to jump over the head, or a slab that is stepped onto
        if (r.has(tx, y + 1, tz) && (r.open(x, y + 2, z) || Voxel.move(r.src.voxel(tx, y, tz)) == Voxel.LOW))
            return BlockPos.asLong(tx, y + 1, tz);
        // down: the column walked into is open down to the cell's head (at the feet a shut hatch a hand opens is open:
        // AiDoors lifts it, the way goes down the ladder under it)
        if (!r.open(tx, y, tz) && !hatch(r, tx, y, tz) || !r.open(tx, y + 1, tz)) return NONE;
        for (int k = 1; k <= MAX_DROP; k++) {
            if (r.has(tx, y - k, tz)) return BlockPos.asLong(tx, y - k, tz);
            if (!r.open(tx, y - k, tz) && !hatch(r, tx, y - k, tz)) return NONE;
        }
        return NONE;
    }

    private static Result path(Reader r, Nodes n, int end, boolean reached, int expanded, boolean exhausted, boolean wayBack, long t0) {
        int len = 0;
        for (int i = end; i >= 0; i = n.parent[i]) len++;
        long[] cells = new long[len];
        int k = len;
        for (int i = end; i >= 0; i = n.parent[i]) cells[--k] = n.pos[i];
        // no way to the goal, the end is only the cell nearest it across: not worth a drop there is no climbing back
        // from (the bottom of a trench dug across the way is nearer than its edge). Out of nodes it is another matter:
        // the way may well go on down there
        if (exhausted && wayBack)
            for (int i = 0, checks = 0; i + 1 < cells.length && checks < BACK_CHECKS; i++) {
                if (BlockPos.getY(cells[i]) - BlockPos.getY(cells[i + 1]) < NO_WAY_BACK) continue;
                checks++;
                if (!find(r, cells[i + 1], cells[i], 0, BACK_NODES, Cost.NONE, false, false).reached) {
                    cells = java.util.Arrays.copyOf(cells, i + 1);
                    break;
                }
            }
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
        long[] readKeys = new long[0];
        int[] readStamps = new int[0];
        if (exhausted) {
            readKeys = r.sections.keySet().toLongArray();
            readStamps = new int[readKeys.length];
            for (int i = 0; i < readKeys.length; i++) readStamps[i] = r.sections.get(readKeys[i]).stamp;
        }
        return new Result(cells, reached, expanded, n.g[end], r.missing.toLongArray(), keys, st, System.nanoTime() - t0, exhausted,
                readKeys, readStamps);
    }

    /**
     * Whether a path found on an older map is still one on this map from its cell `from` on: each cell there, and each
     * step to the next one still a step ({@link #step}). A block changed in a section the path crosses seldom changes
     * the path.
     */
    public static boolean holds(CellSource src, long[] cells, int from) {
        Reader r = new Reader(src);
        for (int i = Math.max(0, from); i + 1 < cells.length; i++) {
            long a = cells[i], b = cells[i + 1];
            int x = BlockPos.getX(a), y = BlockPos.getY(a), z = BlockPos.getZ(a);
            int dx = BlockPos.getX(b) - x, dz = BlockPos.getZ(b) - z;
            if (dx == 0 && dz == 0) {
                // a rung up or down
                if (!r.has(x, y, z) || climb(r, x, y, z, BlockPos.getY(b) - y) != b) return false;
                continue;
            }
            if (BlockPos.getY(b) - y == 2) {
                // up off a ladder through a hatch
                boolean ok = false;
                for (int k = 0; k < 8; k += 2) if (Dirs.DX[k] == dx && Dirs.DZ[k] == dz && hatchUp(r, x, y, z, k) == b) ok = true;
                if (!ok) return false;
                continue;
            }
            int d = -1;
            for (int k = 0; k < 8; k++) if (Dirs.DX[k] == dx && Dirs.DZ[k] == dz) d = k;
            if (d < 0 || !r.has(x, y, z) || step(r, x, y, z, d) != b) return false;
        }
        return cells.length == 0 || r.has(BlockPos.getX(cells[cells.length - 1]), BlockPos.getY(cells[cells.length - 1]),
                BlockPos.getZ(cells[cells.length - 1]));
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
