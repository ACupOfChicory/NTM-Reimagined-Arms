package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A side's staff's map (stage 7, C1), with nothing of the game in it: the land round the side in cells of
 * {@link #CELL} x {@link #CELL} blocks, {@link #N} a side. Each cell: its middle height, the part of it with leaves
 * at a standing look's height (underwood, low crowns: "crowned" here), in water, in water to swim, how rough it is.
 * From where the side thinks its enemies are, what they see of each cell ({@link #sight}): a look over the heights cell
 * by cell, thinned by those leaves. A way between two
 * places ({@link #route}): A* over the cells, each costing its length and more where the enemy sees it, where it is
 * water or steep; the way then thinned to a few points, each at most {@link #LEG} blocks from the last.
 * Built off the server thread (AiHqMap), read anywhere after.
 */
public final class HqGrid {
    /** blocks a side of a cell; cells a side of the map */
    public static final int CELL = 8, N = 64;
    /** an enemy's eye over the ground; the part of a soldier that, seen, has it seen (its chest), blocks */
    static final double EYE = 1.6, SEEN_AT = 1.3;
    /** places round the edge of an enemy's circle it is looked from, besides its middle */
    static final int AROUND = 6;
    /**
     * leaves at a look's height in a cell (the part p of its columns): a look across it meets about CELL·p/CLUMP clumps
     * of them (bushes, low crowns, CLUMP blocks across), any one of which stops it; it gets through e^(-CELL·p/CLUMP)
     * of the time (underwood round every tree, p a fifth: about a half a cell; all leaves: hardly). A look thinned
     * under FADED ends.
     */
    static final double CLUMP = 3, FADED = 0.25;
    /** the enemy sees this far, blocks */
    public static final double RANGE = 128;
    /**
     * the cost of a cell on the enemy's sight (times its length, on top of the length); in water to swim (the soldiers
     * stop at deep water: a ford a dozen cells aside is the way, the war stand), to wade
     */
    public static final double SEEN = 3, SWIM = 60, WADE = 1;
    /** cells whose height differs by more than this from the last are steep, costing more; by more than CLIFF, no way */
    static final double STEEP = 2, CLIFF = 6;
    /** a cell nothing is known of costs this */
    static final double UNKNOWN = 0.5;
    /** a way more than this times the straight one: looked for again, the enemy's sight weighing half as much */
    public static final double DETOUR = 1.6;
    /** the most between two points of a way, blocks */
    public static final double LEG = 32;
    /** the last blocks of a way, in the enemy's own area (the widest of a group's circles): not counted in its part seen */
    public static final double END = 32;

    /** the map's corner, blocks (its cell 0, 0 starts there) */
    public final int x0, z0;
    final float[] h = new float[N * N], crown = new float[N * N], wet = new float[N * N], deep = new float[N * N];
    final boolean[] known = new boolean[N * N];
    /** what the enemies see of each cell, 0..1 (the most of any) */
    final float[] seen = new float[N * N];

    public HqGrid(int x0, int z0) {
        this.x0 = x0;
        this.z0 = z0;
    }

    /** a map of N cells a side round a place, blocks: its corner on the cells' grid */
    public static HqGrid around(double x, double z) {
        int cx = (int) Math.floor(x / CELL) - N / 2, cz = (int) Math.floor(z / CELL) - N / 2;
        return new HqGrid(cx * CELL, cz * CELL);
    }

    public static int index(int i, int k) {
        return k * N + i;
    }

    /** a cell's lie: middle height (blocks, of the ground or the water), parts in leaves at a look's height, in water, in water to swim */
    public void set(int i, int k, double height, double crowned, double inWater, double toSwim) {
        int c = index(i, k);
        h[c] = (float) height;
        crown[c] = (float) crowned;
        wet[c] = (float) inWater;
        deep[c] = (float) toSwim;
        known[c] = true;
    }

    public boolean inside(int i, int k) {
        return i >= 0 && k >= 0 && i < N && k < N;
    }

    /** the cell a place is in (-1: off the map) */
    public int cellX(double x) {
        int i = (int) Math.floor((x - x0) / CELL);
        return i < 0 || i >= N ? -1 : i;
    }

    public int cellZ(double z) {
        int k = (int) Math.floor((z - z0) / CELL);
        return k < 0 || k >= N ? -1 : k;
    }

    public double midX(int i) {
        return x0 + (i + 0.5) * CELL;
    }

    public double midZ(int k) {
        return z0 + (k + 0.5) * CELL;
    }

    public float seen(int i, int k) {
        return seen[index(i, k)];
    }

    public double height(int i, int k) {
        return h[index(i, k)];
    }

    // ------------------------------------------------------------------ sight

    /** what an enemy here sees, added to the map's (the most of any one's): rays to the edge of its reach */
    public void sight(double x, double z) {
        sight(x, z, 0);
    }

    /**
     * What an enemy somewhere in a circle sees, added to the map's (the most of any one's): of each cell, the part of
     * the places it may stand that see it (its middle and AROUND round its edge, each alike; of those on the map and
     * known).
     */
    public void sight(double x, double z, double r) {
        float[] part = new float[N * N], one = new float[N * N];
        int places = r <= 0 ? 1 : AROUND + 1, looked = 0;
        for (int p = 0; p < places; p++) {
            double a = 2 * Math.PI * p / AROUND;
            java.util.Arrays.fill(one, 0);
            if (!from(p == 0 ? x : x + r * Math.cos(a), p == 0 ? z : z + r * Math.sin(a), one)) continue;
            looked++;
            for (int c = 0; c < N * N; c++) part[c] += one[c];
        }
        // of the places on the map and known (one off it is left out, not taken for one that sees nothing)
        if (looked > 0) for (int c = 0; c < N * N; c++) if (part[c] / looked > seen[c]) seen[c] = part[c] / looked;
    }

    /** what one standing here sees, into to (false: off the map or on a cell not known, nothing seen) */
    private boolean from(double x, double z, float[] to) {
        int si = cellX(x), sk = cellZ(z);
        if (si < 0 || sk < 0 || !known[index(si, sk)]) return false;
        double eye = h[index(si, sk)] + EYE;
        int reach = (int) Math.ceil(RANGE / CELL);
        to[index(si, sk)] = 1;
        // rays from the middle of its cell to each cell on the edge of the square of its reach
        for (int e = -reach; e <= reach; e++) {
            ray(si, sk, eye, si + e, sk - reach, to);
            ray(si, sk, eye, si + e, sk + reach, to);
            ray(si, sk, eye, si - reach, sk + e, to);
            ray(si, sk, eye, si + reach, sk + e, to);
        }
        return true;
    }

    private void ray(int si, int sk, double eye, int ti, int tk, float[] seen) {
        double dx = ti - si, dz = tk - sk, len = Math.hypot(dx, dz);
        if (len == 0) return;
        // half a cell a step
        int steps = (int) Math.ceil(len * 2);
        double most = Double.NEGATIVE_INFINITY, lets = 1;
        int last = index(si, sk);
        for (int s = 1; s <= steps; s++) {
            double t = s / (double) steps;
            int i = (int) Math.floor(si + 0.5 + dx * t), k = (int) Math.floor(sk + 0.5 + dz * t);
            if (!inside(i, k)) return;
            int c = index(i, k);
            if (c == last) continue;
            last = c;
            double d = Math.hypot(i - si, k - sk) * CELL;
            if (d > RANGE) return;
            // nothing known of it: neither seen nor in the way
            if (!known[c]) continue;
            // what it sees of a soldier standing there: its chest over the highest look so far, thinned by the leaves
            // on the way and half of this cell's own
            double slope = (h[c] + SEEN_AT - eye) / d;
            if (slope >= most) {
                float v = (float) (lets * Math.exp(-CELL * crown[c] / CLUMP * 0.5));
                if (v > seen[c]) seen[c] = v;
            }
            most = Math.max(most, (h[c] - eye) / d);
            lets *= Math.exp(-CELL * crown[c] / CLUMP);
            if (lets < FADED) return;
        }
    }

    // ------------------------------------------------------------------ ways

    /** the cost of going a step from cell a into cell b, len cells long; infinite: no way */
    double step(int a, int b, double len, double seenWeight) {
        if (cliff(a, b)) return Double.POSITIVE_INFINITY;
        // aslant: not past the corner of a cliff either way round
        if (len > 1 && (cliff(a, index(b % N, a / N)) || cliff(a, index(a % N, b / N)))) return Double.POSITIVE_INFINITY;
        double rise = Math.abs(h[b] - h[a]);
        double c = cost(b, seenWeight);
        if (known[b] && rise > STEEP) c += (rise - STEEP) / 2;
        return c * len;
    }

    /** from one cell to the next a cliff: both known, their heights more than CLIFF apart */
    boolean cliff(int a, int b) {
        return known[a] && known[b] && Math.abs(h[b] - h[a]) > CLIFF;
    }

    /** a cell's cost a cell's length, steepness aside */
    double cost(int b, double seenWeight) {
        double c = 1 + seenWeight * seen[b];
        if (!known[b]) c += UNKNOWN;
        else if (deep[b] >= 0.5) c += SWIM;
        else if (wet[b] >= 0.5) c += WADE;
        return c;
    }

    /** a search's arrays, kept for the next on the same thread (the server thread's searches, a test's) */
    private static final class Buffers {
        final double[] g = new double[N * N];
        final int[] from = new int[N * N];
        final boolean[] closed = new boolean[N * N];
        final int[] heap = new int[N * N * 8 + 1];
        final double[] key = new double[N * N * 8 + 1];
    }

    private static final ThreadLocal<Buffers> BUFFERS = ThreadLocal.withInitial(Buffers::new);

    private static final int[] DI = {1, -1, 0, 0, 1, 1, -1, -1}, DK = {0, 0, 1, -1, 1, -1, 1, -1};

    /** the cheapest way over the cells, as cell indexes from a to b; null: none */
    int[] path(int ai, int ak, int bi, int bk, double seenWeight) {
        int start = index(ai, ak), goal = index(bi, bk);
        Buffers buf = BUFFERS.get();
        double[] g = buf.g;
        Arrays.fill(g, Double.POSITIVE_INFINITY);
        int[] from = buf.from;
        boolean[] closed = buf.closed;
        Arrays.fill(closed, false);
        // a binary heap of cells by g + h: a cell is pushed at most once for each of its 8 neighbours closed
        int[] heap = buf.heap;
        double[] key = buf.key;
        int size = 0;
        g[start] = 0;
        from[start] = -1;
        heap[size] = start;
        key[size++] = 0;
        while (size > 0) {
            int c = heap[0];
            size = pop(heap, key, size);
            if (closed[c]) continue;
            if (c == goal) break;
            closed[c] = true;
            int ci = c % N, ck = c / N;
            for (int d = 0; d < 8; d++) {
                int i = ci + DI[d], k = ck + DK[d];
                if (!inside(i, k)) continue;
                int b = index(i, k);
                if (closed[b]) continue;
                double ng = g[c] + step(c, b, d < 4 ? 1 : Math.sqrt(2), seenWeight);
                if (ng >= g[b]) continue;
                g[b] = ng;
                from[b] = c;
                int di = Math.abs(i - bi), dk = Math.abs(k - bk);
                // octile: no step costs less than its length
                double est = Math.max(di, dk) + (Math.sqrt(2) - 1) * Math.min(di, dk);
                size = push(heap, key, size, b, ng + est);
            }
        }
        if (g[goal] == Double.POSITIVE_INFINITY) return null;
        int len = 0;
        for (int c = goal; c != -1; c = from[c]) len++;
        int[] out = new int[len];
        for (int c = goal, j = len - 1; c != -1; c = from[c]) out[j--] = c;
        return out;
    }

    private static int push(int[] heap, double[] key, int size, int c, double k) {
        int j = size++;
        heap[j] = c;
        key[j] = k;
        while (j > 0) {
            int p = (j - 1) / 2;
            if (key[p] <= key[j]) break;
            swap(heap, key, p, j);
            j = p;
        }
        return size;
    }

    private static int pop(int[] heap, double[] key, int size) {
        size--;
        heap[0] = heap[size];
        key[0] = key[size];
        int j = 0;
        while (true) {
            int l = 2 * j + 1, r = l + 1, m = j;
            if (l < size && key[l] < key[m]) m = l;
            if (r < size && key[r] < key[m]) m = r;
            if (m == j) break;
            swap(heap, key, m, j);
            j = m;
        }
        return size;
    }

    private static void swap(int[] heap, double[] key, int a, int b) {
        int t = heap[a];
        heap[a] = heap[b];
        heap[b] = t;
        double u = key[a];
        key[a] = key[b];
        key[b] = u;
    }

    /**
     * a way: where from and where it was asked to go, its points (blocks, x and z by turns, the last where it ends:
     * short of where it was asked when that was off the map), how long it is against the straight line, the part of
     * each in the enemy's sight short of its last END blocks (there it is at the enemy, seen or not)
     */
    public record Route(double fromX, double fromZ, double toX, double toZ, double[] points, double length, double straight, double seenPart,
            double straightSeenPart) {
        /** it ends short of where it was asked to go (that was off the map) */
        public boolean partial() {
            return Math.hypot(x(size() - 1) - toX, z(size() - 1) - toZ) > 1;
        }

        public int size() {
            return points.length / 2;
        }

        public double x(int j) {
            return points[2 * j];
        }

        public double z(int j) {
            return points[2 * j + 1];
        }
    }

    /**
     * The way from one place to another over the map: a place off the map is taken where the straight line to it
     * leaves the map. The enemy's sight weighs SEEN, half that if the way comes out more than DETOUR times the
     * straight one, none at all if it still does. Null: no way (from or to a cell with none).
     */
    public Route route(double fx, double fz, double tx, double tz) {
        double[] to = clampToMap(fx, fz, tx, tz);
        int ai = cellX(fx), ak = cellZ(fz), bi = cellX(to[0]), bk = cellZ(to[1]);
        if (ai < 0 || ak < 0 || bi < 0 || bk < 0) return null;
        double straight = Math.hypot(to[0] - fx, to[1] - fz);
        int[] p = null;
        for (double w : new double[] {SEEN, SEEN / 2, 0}) {
            p = path(ai, ak, bi, bk, w);
            if (p == null) return null;
            if (cells(p) * CELL <= Math.max(straight * DETOUR, 2 * CELL)) break;
        }
        double[] pts = thin(p, fx, fz, to[0], to[1]);
        double length = 0;
        for (int j = 2; j < pts.length; j += 2) length += Math.hypot(pts[j] - pts[j - 2], pts[j + 1] - pts[j - 1]);
        length += Math.hypot(pts[0] - fx, pts[1] - fz);
        return new Route(fx, fz, tx, tz, pts, length, straight, seenPart(fx, fz, pts, END), seenPart(fx, fz, new double[] {to[0], to[1]}, END));
    }

    /** the length of a path over cells, in cells */
    static double cells(int[] p) {
        double l = 0;
        for (int j = 1; j < p.length; j++) l += (p[j] % N != p[j - 1] % N && p[j] / N != p[j - 1] / N) ? Math.sqrt(2) : 1;
        return l;
    }

    /** where the straight line from f to t leaves the map, if t is off it (a little inside its edge) */
    double[] clampToMap(double fx, double fz, double tx, double tz) {
        double lo = 0.5, hiX = N * CELL - 0.5;
        double ax = tx - x0, az = tz - z0, bx = fx - x0, bz = fz - z0;
        if (ax >= lo && az >= lo && ax <= hiX && az <= hiX) return new double[] {tx, tz};
        double t = 1;
        double dx = ax - bx, dz = az - bz;
        if (ax < lo) t = Math.min(t, (lo - bx) / dx);
        if (ax > hiX) t = Math.min(t, (hiX - bx) / dx);
        if (az < lo) t = Math.min(t, (lo - bz) / dz);
        if (az > hiX) t = Math.min(t, (hiX - bz) / dz);
        t = Math.max(0, t);
        return new double[] {fx + (tx - fx) * t, fz + (tz - fz) * t};
    }

    /**
     * The path's cells thinned to points: from each point on, the farthest cell of the path within LEG that the
     * straight line from the point reaches over cells costing no more than the path's own there (it cuts no corner
     * into the enemy's sight, water or a cliff); its middle the next point. The last point: where it ends.
     */
    double[] thin(int[] p, double fx, double fz, double tx, double tz) {
        List<double[]> out = new ArrayList<>();
        double px = fx, pz = fz;
        int at = 0;
        while (at < p.length - 1) {
            int best = at + 1;
            for (int j = at + 2; j < p.length; j++) {
                double cx = midX(p[j] % N), cz = midZ(p[j] / N);
                if (Math.hypot(cx - px, cz - pz) > LEG) break;
                if (straightAsGood(px, pz, cx, cz, p, at, j)) best = j;
            }
            px = midX(p[best] % N);
            pz = midZ(p[best] / N);
            at = best;
            out.add(new double[] {px, pz});
        }
        if (out.isEmpty()) out.add(new double[] {tx, tz});
        else out.set(out.size() - 1, new double[] {tx, tz});
        double[] pts = new double[out.size() * 2];
        for (int j = 0; j < out.size(); j++) {
            pts[2 * j] = out.get(j)[0];
            pts[2 * j + 1] = out.get(j)[1];
        }
        return pts;
    }

    /**
     * the straight line costs no more than the path between (the enemy's sight and water weighed as the search does,
     * a tenth more let), and climbs no cliff
     */
    private boolean straightAsGood(double ax, double az, double bx, double bz, int[] p, int from, int to) {
        double along = 0;
        for (int j = from + 1; j <= to; j++) along += cost(p[j], SEEN) * (p[j] % N != p[j - 1] % N && p[j] / N != p[j - 1] / N ? Math.sqrt(2) : 1);
        double len = Math.hypot(bx - ax, bz - az) / CELL, straight = 0;
        int steps = Math.max(1, (int) Math.ceil(len * 2));
        int prev = cellX(ax) < 0 || cellZ(az) < 0 ? -1 : index(cellX(ax), cellZ(az));
        for (int s = 1; s <= steps; s++) {
            double t = s / (double) steps;
            int i = cellX(ax + (bx - ax) * t), k = cellZ(az + (bz - az) * t);
            if (i < 0 || k < 0) return false;
            int c = index(i, k);
            straight += cost(c, SEEN) * len / steps;
            if (prev >= 0 && c != prev && (cliff(prev, c) || prev % N != i && prev / N != k && (cliff(prev, index(i, prev / N)) || cliff(prev, index(prev % N, k))))) return false;
            prev = c;
        }
        return straight <= along * 1.1;
    }

    /**
     * the part of the way (from f through the points) over cells the enemy sees (more than half), short of its last
     * skip blocks (all of it if it is no longer)
     */
    public double seenPart(double fx, double fz, double[] pts, double skip) {
        double length = 0, px = fx, pz = fz;
        for (int j = 0; j < pts.length; j += 2) {
            length += Math.hypot(pts[j] - px, pts[j + 1] - pz);
            px = pts[j];
            pz = pts[j + 1];
        }
        double counted = length > skip ? length - skip : length, all = 0, onSight = 0;
        px = fx;
        pz = fz;
        for (int j = 0; j < pts.length && all < counted; j += 2) {
            double len = Math.hypot(pts[j] - px, pts[j + 1] - pz);
            int steps = Math.max(1, (int) Math.ceil(len));
            for (int s = 0; s < steps && all < counted; s++) {
                double t = (s + 0.5) / steps;
                int i = cellX(px + (pts[j] - px) * t), k = cellZ(pz + (pts[j + 1] - pz) * t);
                all += len / steps;
                if (i >= 0 && k >= 0 && seen[index(i, k)] > 0.5) onSight += len / steps;
            }
            px = pts[j];
            pz = pts[j + 1];
        }
        return all <= 0 ? 0 : onSight / all;
    }
}
