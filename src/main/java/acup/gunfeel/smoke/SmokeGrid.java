package acup.gunfeel.smoke;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * One cloud's simulation: a box of cells a block each around the burst cell, the density in each, which cells are
 * closed to it, and the step that lets it out, spreads it and thins it. Plain Java, no game classes: the server and the
 * client run the same steps from the same seed and the same blocks and get the same floats (Java's float is the same on
 * every JVM, there is no shared Random and the cells go in a fixed order), so only the events go over the network.
 *
 * <p>How it spreads. A cell above the kind's {@code fill} density gives its neighbours part of what it has above it
 * (more above 1, so the core empties out fast); below {@code fill} a cell keeps what it has. A cloud therefore fills
 * the open cells around it up to {@code fill} and stops, with a soft edge of half-filled cells, as TGMC's smoke fills
 * its tiles: an open field gets a half sphere, a corridor a long plug, and nothing goes through a closed cell. Up is
 * as fast as down for smoke ({@code rise}) and the dense part of it drifts up a little ({@code lift}); a heavy gas will
 * turn both round. After {@code hold} ticks every cell thins by {@code fade} a tick, with a little noise per cell so the edge
 * breaks up; below {@link #GONE} a cell is empty. What spreads out of the box is lost.
 *
 * <p>A blast can push smoke out of its cells for a while ({@link #stirBlast} with {@code back}): what it took is held in
 * the cell, out of sight, and comes back into it when its time comes, the cells at the hole's edge first and its middle
 * {@link #BACK_DELAY} ticks later, each an eighth of what it holds a tick. The hole closes from its edge in 2 to 3 s
 * and the cloud loses nothing; what is held thins as the rest does.
 *
 * <p>A smoke grenade that bursts inside a cloud of its kind feeds it ({@link #feed}) instead of making a cloud of its
 * own (a gas does not: {@link SmokeClouds#feeds}): its smoke comes out of its cells over the release as the first one's did and spreads in what is there, so the
 * cloud grows outward at its {@code fill} and does not stack a second cloud on the first, twice as dense. The cloud
 * thins {@code hold} ticks after the last feed's release. At most {@link #FEEDS_MAX} feeds.
 *
 * <p>The wind (cells a tick, the one of the cloud's birth for its life: {@link SmokeWind}) carries what is in a cell with
 * no roof over it downwind (second order, so the cloud keeps its shape), less in the four cells behind a wall
 * ({@link #LEE}). Under a roof (something closed above the cell, or over its column above the box) it only reaches in
 * from an opening, {@link #DRAUGHT} less a cell further in: there the cells mix with their neighbours and the draught
 * takes {@link #AIRING} of the gas out, so a room with an open window or door airs out (faster the stronger the wind)
 * and a shut one keeps its gas. A heavy gas sinks faster in it. The box follows the cloud downwind ({@link #drift}): when
 * the blown smoke nears the box's downwind side, the box moves {@link #SHIFT} cells along and reads the cells that came
 * in; what it leaves behind upwind is at most {@link #DROP_MAX} of the cloud.
 *
 * <p>Only the box of occupied cells and one cell around it is stepped.
 */
public final class SmokeGrid {
    public static final int SX = 25, SY = 13, SZ = 25, CELLS = SX * SY * SZ;
    /** The burst cell inside the box: 12 cells to either side, 4 down and 8 up. */
    public static final int OX = 12, OY = 4, OZ = 12;
    /** The burst cell's height in the box for a heavy gas: 8 cells down and 4 up, to run down into a cellar or a trench. */
    public static final int OY_HEAVY = 8;
    /** Cells behind a wall the wind is weaker in: right behind it a fifth, then two fifths, up to the whole past four. */
    public static final int LEE = 4;
    /** Under a roof: the draught a cell further in from an opening, and how many cells in it reaches at most. */
    public static final float DRAUGHT = 0.75F;
    public static final int DRAUGHT_DEPTH = 8;
    /** How fast the air mixes under a roof, per cell a tick of wind and per draught; at most {@link #MIX_MAX} a tick. */
    public static final float MIX = 6F, MIX_MAX = 1F / 8F;
    /**
     * The share of a cell under a roof the draught takes out a tick, per cell a tick of wind and per draught: the air
     * that comes in at an opening and goes out again takes the gas with it.
     */
    public static final float AIRING = 0.4F;
    /**
     * Below this (a tenth of {@link #GONE}) the wind takes nothing from a cell, and a cell in the wind that got nothing
     * in the step is emptied: the cloud's front runs at most a cell ahead of its smoke and the thin trail behind it goes.
     * Without it the edges smear out to the box's sides at next to nothing, and the box (the work) grows by half again
     * for smoke no one sees; with it a cloud in the clear loses some 2% more over its life.
     */
    public static final float TAIL = 0.002F;
    /** The box moves this many cells downwind once the smoke comes within {@link #EDGE} cells of its side. */
    public static final int SHIFT = 4, EDGE = 3;
    /** Share of the cloud the box may leave behind upwind when it moves (the thin tail the wind smeared out). */
    public static final float DROP_MAX = 0.03F;
    /** How far a box can have moved, cells: farther than any cloud lives to drift, for a grid from the network. */
    public static final int SHIFT_MAX = 4096;
    /** Below this a thinning cell is empty. */
    public static final float GONE = 0.02F;
    /** Ticks the middle of a blast's hole waits before its smoke comes back (its edge: none). */
    public static final int BACK_DELAY = 30;
    /** Share of what a cell holds that comes back a tick once its time came. */
    public static final float BACK_RATE = 1F / 8F;
    /** Grenades that can feed one cloud after the first. */
    public static final int FEEDS_MAX = 8;

    // a later grenade's smoke: from this age over the release, a share a tick out of each of its cells
    private record Feed(int from, int[] cells, float each) {
    }

    /**
     * Whether the cell at x, y, z is closed to smoke (a full block, a liquid, an unloaded chunk). The coordinates are the
     * box's as it was when the cloud was born: a box that moved downwind asks for its cells by them ({@link #drift}).
     */
    public interface Mask {
        boolean closed(int x, int y, int z);

        /** Whether something is over the column x, z above the box's top (a roof there): no wind under it. */
        default boolean roofed(int x, int z) {
            return false;
        }
    }

    private final SmokeKind kind;
    private final long seed;
    private final float amount;
    private final float[] d = new float[CELLS], next = new float[CELLS];
    private final long[] closed = new long[(CELLS + 63) >>> 6];
    // the columns with a roof above the box
    private final long[] roof = new long[(SX * SZ + 63) >>> 6];
    // smoke a blast pushed out of a cell, and the age from which it comes back; made at the first such blast
    private float[] held;
    private int[] back;
    private final float dirX, dirZ;
    private int[] emitters = new int[0];
    // the emitters as place() found them, in the box the cloud was born with
    private int[] placed = new int[0];
    private final java.util.List<Feed> feeds = new java.util.ArrayList<>();
    private int age;
    // the occupied cells, inclusive; empty while x1 < x0
    private int x0 = SX, y0 = SY, z0 = SZ, x1 = -1, y1 = -1, z1 = -1;
    // the wind, cells a tick; none: no air worked out, no box moved
    private final float windX, windZ, windSpeed;
    // how much of the wind a cell gets (under a roof: the draught), and the cells under a roof; again once a cell opened
    // or closed, the box moved or a grid came
    private float[] air;
    private long[] sheltered;
    private boolean anySheltered;
    private boolean airStale = true, rescan;
    // how far the box moved downwind from where the cloud was born, cells
    private int shiftX, shiftZ;

    /**
     * @param dirX, dirZ the way the grenade flew (the bomblets fan out along it; a straight-down shot rings them round)
     * @param scale      share of the kind's volume
     */
    public SmokeGrid(SmokeKind kind, long seed, float dirX, float dirZ, float scale) {
        this(kind, seed, dirX, dirZ, scale, 0F, 0F);
    }

    /** @param windX, windZ the wind over the cloud's life, cells (blocks) a tick */
    public SmokeGrid(SmokeKind kind, long seed, float dirX, float dirZ, float scale, float windX, float windZ) {
        this.kind = kind;
        this.seed = seed;
        this.amount = kind.volume() * Math.max(0F, scale);
        this.dirX = dirX;
        this.dirZ = dirZ;
        float speed = (float) Math.sqrt(windX * windX + windZ * windZ);
        boolean any = speed > 1e-5F && Float.isFinite(speed);
        this.windX = any ? windX : 0F;
        this.windZ = any ? windZ : 0F;
        this.windSpeed = any ? speed : 0F;
    }

    public static int index(int x, int y, int z) {
        return (y * SZ + z) * SX + x;
    }

    public static boolean inside(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < SX && y < SY && z < SZ;
    }

    public SmokeKind kind() {
        return kind;
    }

    /** The burst cell's height in the box: {@link #OY}, a heavy gas's {@link #OY_HEAVY}. */
    public int oy() {
        return kind.below();
    }

    /** How far the box moved downwind since the cloud was born, cells along x and z. */
    public int shiftX() {
        return shiftX;
    }

    public int shiftZ() {
        return shiftZ;
    }

    /** The wind, cells a tick (0 without). */
    public float windX() {
        return windX;
    }

    public float windZ() {
        return windZ;
    }

    public long seed() {
        return seed;
    }

    /** Ticks stepped. */
    public int age() {
        return age;
    }

    public float density(int x, int y, int z) {
        return inside(x, y, z) ? d[index(x, y, z)] : 0F;
    }

    public float density(int index) {
        return d[index];
    }

    public boolean closed(int x, int y, int z) {
        return inside(x, y, z) && closedAt(index(x, y, z));
    }

    public boolean closedAt(int index) {
        return (closed[index >>> 6] & 1L << index) != 0L;
    }

    private void setClosed(int index, boolean on) {
        if (on) closed[index >>> 6] |= 1L << index;
        else closed[index >>> 6] &= ~(1L << index);
    }

    /** The occupied box, inclusive: {x0, y0, z0, x1, y1, z1}; x1 < x0 when there is nothing. */
    public int[] box() {
        return new int[] {x0, y0, z0, x1, y1, z1};
    }

    public boolean occupied() {
        return x1 >= x0 && y1 >= y0 && z1 >= z0;
    }

    /** Let out and gone: nothing left to step or draw. */
    public boolean done() {
        return age >= lastFeed() + kind.release() && !occupied();
    }

    // the age the last grenade began to let its smoke out at: 0, the first, or the last feed's
    private int lastFeed() {
        return feeds.isEmpty() ? 0 : feeds.getLast().from;
    }

    /** Ticks the cloud can live at most: its kind's, from the last feed on. */
    public int maxAge() {
        return lastFeed() + kind.maxAge();
    }

    public int feeds() {
        return feeds.size();
    }

    /** The age feed {@code i} (0 the first after the cloud's own) began to let its smoke out at. */
    public int feedFrom(int i) {
        return feeds.get(i).from;
    }

    public float total() {
        double sum = 0;
        for (float v : d) sum += v;
        return (float) sum;
    }

    /** What blasts hold out of the cells, to come back. */
    public float heldTotal() {
        if (held == null) return 0F;
        double sum = 0;
        for (float v : held) sum += v;
        return (float) sum;
    }

    private boolean holds(int i) {
        return held != null && held[i] > 0F;
    }

    public int emitters() {
        return emitters.length;
    }

    // ---- the blocks

    /** Reads every cell's mask (when the cloud is born); then {@link #place()}. */
    public void scan(Mask mask) {
        for (int y = 0; y < SY; y++) for (int z = 0; z < SZ; z++) for (int x = 0; x < SX; x++) setClosed(index(x, y, z), mask.closed(x + shiftX, y, z + shiftZ));
        if (windSpeed > 0F) for (int c = 0; c < SX * SZ; c++) setRoof(c, mask.roofed(c % SX + shiftX, c / SX + shiftZ));
        airStale = true;
        rescan = false;
    }

    private boolean roofAt(int column) {
        return (roof[column >>> 6] & 1L << column) != 0L;
    }

    private void setRoof(int column, boolean on) {
        if (on) roof[column >>> 6] |= 1L << column;
        else roof[column >>> 6] &= ~(1L << column);
    }

    /**
     * Reads one part of the cells again (doors open and shut): cells {@code part * CELLS / parts} up to the next part.
     * A cell that closes loses its smoke.
     */
    public void refresh(Mask mask, int part, int parts) {
        // a grid from the server whose box had moved elsewhere: every cell again
        if (rescan) {
            scan(mask);
            for (int i = 0; i < CELLS; i++) if (closedAt(i)) {
                d[i] = 0F;
                if (held != null) held[i] = 0F;
            }
            return;
        }
        int from = (int) ((long) part * CELLS / parts), to = (int) ((long) (part + 1) * CELLS / parts);
        for (int i = from; i < to; i++) {
            int x = i % SX, z = i / SX % SZ, y = i / (SX * SZ);
            boolean c = mask.closed(x + shiftX, y, z + shiftZ);
            if (c) {
                d[i] = 0F;
                if (held != null) held[i] = 0F;
            }
            if (c != closedAt(i)) airStale = true;
            setClosed(i, c);
        }
        if (windSpeed > 0F) {
            int columns = SX * SZ, a = (int) ((long) part * columns / parts), b = (int) ((long) (part + 1) * columns / parts);
            for (int c = a; c < b; c++) {
                boolean r = mask.roofed(c % SX + shiftX, c / SX + shiftZ);
                if (r != roofAt(c)) airStale = true;
                setRoof(c, r);
            }
        }
    }

    /**
     * Picks the cells the smoke comes out of: the burst cell (or the nearest open one, a grenade that burst in a wall)
     * and the bomblets fanned out along the flight, each as far as it gets before a closed cell.
     */
    public void place() {
        emitters = cellsAt(OX, oy(), OZ, dirX, dirZ, 0);
        placed = emitters.clone();
        remap(emitters, shiftX, shiftZ);
    }

    /**
     * A grenade of this kind burst at box cell x, y, z at this age (before its step), flying along dirX, dirZ:
     * its smoke ({@code scale} of the kind's volume) comes out of its own cells, placed as {@link #place} places the
     * first's, over the release from now. False (nothing done) past {@link #FEEDS_MAX} or with no open cell there.
     */
    public boolean feed(int x, int y, int z, float dirX, float dirZ, float scale) {
        if (feeds.size() >= FEEDS_MAX || !inside(x, y, z) || !(scale > 0F)) return false;
        int[] cells = cellsAt(x, y, z, dirX, dirZ, feeds.size() + 1);
        if (cells.length == 0) return false;
        feeds.add(new Feed(age, cells, kind.volume() * scale / (kind.release() * (float) cells.length)));
        return true;
    }

    // the cells a grenade's smoke comes out of; salt: which grenade of the cloud (the noise of its fan)
    private int[] cellsAt(int ox, int oy, int oz, float dirX, float dirZ, int salt) {
        int center = -1;
        for (int r = 0; r <= 2 && center < 0; r++) center = nearestOpen(ox, oy, oz, r);
        if (center < 0) return new int[0];
        int n = Math.max(1, kind.bomblets());
        int[] out = new int[n];
        int count = 0;
        out[count++] = center;
        int cx = center % SX, cz = center / SX % SZ, cy = center / (SX * SZ);
        // StrictMath: the same bits on every JVM and CPU, as the Javadoc above promises
        double len = StrictMath.hypot(dirX, dirZ);
        double base = len > 0.2 ? StrictMath.atan2(dirZ, dirX) : 0.0;
        for (int b = 1; b < n; b++) {
            double angle;
            if (len > 0.2) {
                double share = n > 2 ? (b - 1) / (double) (n - 2) * 2.0 - 1.0 : 0.0;
                angle = base + Math.toRadians(kind.fan()) * share + (unit(b, salt, 7) - 0.5) * 0.2;
            } else {
                angle = 2.0 * Math.PI * (b - 1) / (n - 1) + unit(b, salt, 8) * 0.5;
            }
            double reach = kind.reach() * (0.55 + 0.45 * unit(b, salt, 9));
            int last = center;
            for (double t = 0.25; t <= reach; t += 0.25) {
                int x = (int) Math.floor(cx + 0.5 + StrictMath.cos(angle) * t), z = (int) Math.floor(cz + 0.5 + StrictMath.sin(angle) * t);
                if (!inside(x, cy, z) || closedAt(index(x, cy, z))) break;
                last = index(x, cy, z);
            }
            out[count++] = last;
        }
        return out;
    }

    private int nearestOpen(int ox, int oy, int oz, int r) {
        // shells of growing size, upward first: a grenade in the floor lets its smoke out above it
        for (int dy = r; dy >= -r; dy--) for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != r) continue;
            int x = ox + dx, y = oy + dy, z = oz + dz;
            if (inside(x, y, z) && !closedAt(index(x, y, z))) return index(x, y, z);
        }
        return -1;
    }

    // ---- the step

    public void step() {
        if (age < kind.release() && emitters.length > 0) {
            float each = amount / (kind.release() * (float) emitters.length);
            for (int e : emitters) if (e >= 0 && !closedAt(e)) d[e] += each;
            grow(emitters);
        }
        for (Feed f : feeds) {
            if (age < f.from || age >= f.from + kind.release()) continue;
            for (int e : f.cells) if (e >= 0 && !closedAt(e)) d[e] += f.each;
            grow(f.cells);
        }
        if (occupied()) {
            if (windSpeed > 0F && airStale) air();
            if (held != null) giveBack();
            spread();
            if (windSpeed > 0F) wind();
            if (age >= lastFeed() + kind.release() + kind.hold()) thin();
        }
        age++;
        bounds();
    }

    private void grow(int[] cells) {
        for (int i : cells) {
            if (i < 0) continue;
            int x = i % SX, z = i / SX % SZ, y = i / (SX * SZ);
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            z0 = Math.min(z0, z);
            x1 = Math.max(x1, x);
            y1 = Math.max(y1, y);
            z1 = Math.max(z1, z);
        }
    }

    // the held smoke whose time came goes back into its cells
    private void giveBack() {
        for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
            int row = (y * SZ + z) * SX;
            for (int x = x0; x <= x1; x++) {
                int i = row + x;
                float h = held[i];
                if (h == 0F || age < back[i]) continue;
                float g = h < GONE ? h : h * BACK_RATE;
                d[i] += g;
                held[i] = h - g;
            }
        }
    }

    // what a cell pushes its neighbours with: what it has above fill, and the share above 1 twice
    private float push(float v) {
        float p = v - kind.fill();
        if (p <= 0F) return 0F;
        return v > 1F ? p + (v - 1F) : p;
    }

    private void spread() {
        float k = kind.spread(), up = k * (1F + kind.rise()), down = k * (1F - kind.rise()), lift = kind.lift(), liftFrom = kind.fill() * 0.5F;
        // the faces between a cell and its +x, +y, +z neighbour, one cell around the occupied box (and the box's own
        // faces out of the grid: lost)
        int ax = Math.max(0, x0 - 1), ay = Math.max(0, y0 - 1), az = Math.max(0, z0 - 1);
        int bx = Math.min(SX - 1, x1 + 1), by = Math.min(SY - 1, y1 + 1), bz = Math.min(SZ - 1, z1 + 1);
        // what the step reads and writes: these cells and their +x, +y, +z neighbours; a row (y, z) with no smoke whose
        // +y and +z rows have none either gives and takes nothing (no cell in it pushes, none of its neighbours does)
        int ex = Math.min(SX - 1, bx + 1), ey = Math.min(SY - 1, by + 1), ez = Math.min(SZ - 1, bz + 1);
        copy(d, next, ax, ex, ay, ey, az, ez);
        for (int y = ay; y <= ey; y++) for (int z = az; z <= ez; z++) {
            int row = (y * SZ + z) * SX;
            boolean any = false;
            for (int x = ax; x <= ex && !any; x++) any = d[row + x] != 0F;
            smokeRow[y * SZ + z] = any;
        }
        for (int y = ay; y <= by; y++) for (int z = az; z <= bz; z++) {
            int row = (y * SZ + z) * SX, r = y * SZ + z;
            if (!smokeRow[r] && (z + 1 >= SZ || !smokeRow[r + 1]) && (y + 1 >= SY || !smokeRow[r + SZ])) continue;
            for (int x = ax; x <= bx; x++) {
                int a = row + x;
                if (closedAt(a)) continue;
                float da = d[a], pa = push(da);
                if (x == 0) next[a] -= limit(k * pa, da);
                if (z == 0) next[a] -= limit(k * pa, da);
                if (y == 0) next[a] -= limit(down * pa, da);
                // +x
                if (x + 1 < SX) {
                    int b = a + 1;
                    if (!closedAt(b)) flow(a, b, k * (pa - push(d[b])), da, d[b]);
                } else next[a] -= limit(k * pa, da);
                // +z
                if (z + 1 < SZ) {
                    int b = a + SX;
                    if (!closedAt(b)) flow(a, b, k * (pa - push(d[b])), da, d[b]);
                } else next[a] -= limit(k * pa, da);
                // +y: up faster than down, and the lift
                if (y + 1 < SY) {
                    int b = a + SX * SZ;
                    if (!closedAt(b)) {
                        float db = d[b], diff = pa - push(db);
                        float f = diff > 0F ? up * diff : down * diff;
                        // only from a dense cell: a thin edge does not trail away up to the top of the box
                        if (lift > 0F) {
                            if (da > liftFrom) f += lift * da;
                        } else if (db > liftFrom) f += (air != null ? lift * (1F + air[b]) : lift) * db;
                        flow(a, b, f, da, db);
                    }
                } else next[a] -= limit(up * pa + (lift > 0F && da > liftFrom ? lift * da : 0F), da);
            }
        }
        copy(next, d, ax, ex, ay, ey, az, ez);
    }

    // rows of the grid (y, z) with smoke in the step's cells, see spread
    private final boolean[] smokeRow = new boolean[SY * SZ];

    // the cells x0..x1, y0..y1, z0..z1 (inclusive) of one array into the other
    private static void copy(float[] from, float[] to, int x0, int x1, int y0, int y1, int z0, int z1) {
        for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
            int row = (y * SZ + z) * SX + x0;
            System.arraycopy(from, row, to, row, x1 - x0 + 1);
        }
    }

    // a cell gives no more than a seventh of what it has across one face, so six faces never take it below zero
    private static float limit(float f, float from) {
        return Math.min(f, from * (1F / 7F));
    }

    private void flow(int a, int b, float f, float da, float db) {
        if (f > 0F) f = Math.min(f, da * (1F / 7F));
        else if (f < 0F) f = Math.max(f, -db * (1F / 7F));
        else return;
        next[a] -= f;
        next[b] += f;
    }

    // ---- the wind

    private boolean shelteredAt(int i) {
        return (sheltered[i >>> 6] & 1L << i) != 0L;
    }

    // how much of the wind each open cell gets: none under a roof (something closed above it in the box, or a roof over
    // its column), less behind a wall upwind; then under a roof the draught from the cells next to it, DRAUGHT less a cell
    private void air() {
        airStale = false;
        if (air == null) {
            air = new float[CELLS];
            sheltered = new long[closed.length];
        }
        java.util.Arrays.fill(sheltered, 0L);
        anySheltered = false;
        float ux = windX / windSpeed, uz = windZ / windSpeed;
        for (int z = 0; z < SZ; z++) for (int x = 0; x < SX; x++) {
            boolean covered = roofAt(z * SX + x);
            for (int y = SY - 1; y >= 0; y--) {
                int i = index(x, y, z);
                if (closedAt(i)) {
                    covered = true;
                    air[i] = 0F;
                } else if (covered) {
                    sheltered[i >>> 6] |= 1L << i;
                    anySheltered = true;
                    air[i] = 0F;
                } else air[i] = lee(x, y, z, ux, uz);
            }
        }
        float[] was = next;
        for (int k = 0; k < DRAUGHT_DEPTH; k++) {
            System.arraycopy(air, 0, was, 0, CELLS);
            boolean any = false;
            for (int i = 0; i < CELLS; i++) {
                if (!shelteredAt(i)) continue;
                int x = i % SX, z = i / SX % SZ, y = i / (SX * SZ);
                float m = 0F;
                if (x > 0) m = Math.max(m, was[i - 1]);
                if (x + 1 < SX) m = Math.max(m, was[i + 1]);
                if (z > 0) m = Math.max(m, was[i - SX]);
                if (z + 1 < SZ) m = Math.max(m, was[i + SX]);
                if (y > 0) m = Math.max(m, was[i - SX * SZ]);
                if (y + 1 < SY) m = Math.max(m, was[i + SX * SZ]);
                float v = m * DRAUGHT;
                if (v > air[i]) {
                    air[i] = v;
                    any = true;
                }
            }
            if (!any) break;
        }
    }

    // the wind an open cell with no roof gets: a share by how far upwind the nearest wall is at its height
    private float lee(int x, int y, int z, float ux, float uz) {
        for (int k = 1; k <= LEE; k++) {
            int ax = (int) Math.floor(x + 0.5F - ux * k), az = (int) Math.floor(z + 0.5F - uz * k);
            if (!inside(ax, y, az)) return 1F;
            if (closedAt(index(ax, y, az))) return k / (float) (LEE + 1);
        }
        return 1F;
    }

    /** How much of the wind the cell gets, 0 to 1 (0 without wind, in a closed cell, deep under a roof). */
    public float air(int x, int y, int z) {
        if (windSpeed == 0F || !inside(x, y, z)) return 0F;
        if (airStale) air();
        return air[index(x, y, z)];
    }

    // the wind's step: a cell with no roof gives its downwind neighbours its share of the wind (a closed one takes none,
    // out of the box it is lost), the face's value from the cell and its slope (second order, minmod: the cloud keeps its
    // shape instead of smearing out along the wind); across a face with a roof on either side the two cells mix by the
    // draught, and a cell under a roof loses AIRING of what it has by its draught
    private void wind() {
        int bx0 = Math.max(0, x0 - 1), by0 = Math.max(0, y0 - 1), bz0 = Math.max(0, z0 - 1);
        int bx1 = Math.min(SX - 1, x1 + 1), by1 = Math.min(SY - 1, y1 + 1), bz1 = Math.min(SZ - 1, z1 + 1);
        // what is written back: one more around, where the ring's cells carry and mix to
        int cx0 = Math.max(0, bx0 - 1), cy0 = Math.max(0, by0 - 1), cz0 = Math.max(0, bz0 - 1);
        int cx1 = Math.min(SX - 1, bx1 + 1), cy1 = Math.min(SY - 1, by1 + 1), cz1 = Math.min(SZ - 1, bz1 + 1);
        for (int y = cy0; y <= cy1; y++) for (int z = cz0; z <= cz1; z++) {
            int row = (y * SZ + z) * SX;
            System.arraycopy(d, row + cx0, next, row + cx0, cx1 - cx0 + 1);
        }
        float ax = Math.abs(windX), az = Math.abs(windZ), mixK = MIX * windSpeed, airing = AIRING * windSpeed;
        int sx = windX > 0F ? 1 : -1, sz = windZ > 0F ? 1 : -1;
        boolean mixing = anySheltered;
        for (int y = by0; y <= by1; y++) for (int z = bz0; z <= bz1; z++) {
            int row = (y * SZ + z) * SX;
            boolean zUp = z - sz >= 0 && z - sz < SZ, zDown = z + sz >= 0 && z + sz < SZ;
            if (!mixing) {
                // out in the open a row with no cell thick enough to carry does nothing
                boolean any = false;
                for (int x = bx0; x <= bx1 && !any; x++) any = d[row + x] >= TAIL;
                if (!any) continue;
            }
            for (int x = bx0; x <= bx1; x++) {
                int a = row + x;
                if (closedAt(a)) continue;
                float da = d[a];
                // out in the open, a cell too thin to carry does nothing in the wind
                if (!mixing && da < TAIL) continue;
                float e = air[a];
                boolean roofed = mixing && shelteredAt(a);
                if (roofed) {
                    if (da > 0F && e > 0F) next[a] -= Math.min(airing * e, MIX_MAX) * da;
                } else if (da >= TAIL && e > 0F) {
                    if (ax > 0F) carry(a, x - sx >= 0 && x - sx < SX ? a - sx : -1, x + sx >= 0 && x + sx < SX ? a + sx : -1, ax * e);
                    if (az > 0F) carry(a, zUp ? a - sz * SX : -1, zDown ? a + sz * SX : -1, az * e);
                }
                if (mixing) {
                    if (x + 1 < SX) mix(a, a + 1, roofed, da, mixK);
                    if (z + 1 < SZ) mix(a, a + SX, roofed, da, mixK);
                    if (y + 1 < SY) mix(a, a + SX * SZ, roofed, da, mixK);
                }
            }
        }
        // back into the cells; below TAIL in the wind and given nothing this step: gone
        for (int y = cy0; y <= cy1; y++) for (int z = cz0; z <= cz1; z++) {
            int row = (y * SZ + z) * SX;
            for (int x = cx0; x <= cx1; x++) {
                int a = row + x;
                float v = next[a];
                d[a] = v <= 0F || v < TAIL && v <= d[a] && air[a] > 0F ? 0F : v;
            }
        }
    }

    // what the wind takes from cell a to its downwind neighbour b (-1: out of the box, lost) at Courant number c: the
    // face's value from a and its slope between the upwind neighbour u and b, minmod; a closed or outside upwind
    // neighbour is taken as a itself
    private void carry(int a, int u, int b, float c) {
        if (b >= 0 && closedAt(b)) return;
        float da = d[a], du = u >= 0 && !closedAt(u) ? d[u] : da, db = b >= 0 ? d[b] : 0F;
        float l = da - du, r = db - da, slope = l > 0F && r > 0F ? Math.min(l, r) : l < 0F && r < 0F ? Math.max(l, r) : 0F;
        float f = c * (da + 0.5F * (1F - c) * slope);
        if (!(f > 0F)) return;
        next[a] -= f;
        if (b >= 0) next[b] += f;
    }

    private void mix(int a, int b, boolean roofedA, float da, float mixK) {
        if (closedAt(b) || !roofedA && !shelteredAt(b)) return;
        float db = d[b];
        if (da == db) return;
        float m = Math.min(air[a], air[b]);
        if (m <= 0F) return;
        float f = Math.min(mixK * m, MIX_MAX) * (da - db);
        next[a] -= f;
        next[b] += f;
    }

    /**
     * After a step: when the smoke came within {@link #EDGE} cells of the box's downwind side and the upwind side has
     * {@link #SHIFT} empty cells, the box moves {@code SHIFT} cells downwind (both ways at once for a slanting wind); the
     * cells that came in are read from the mask. The same on every side. Returns whether it moved.
     */
    public boolean drift(Mask mask) {
        if (windSpeed == 0F || !occupied()) return false;
        int dx = 0, dz = 0, top = SX - 1, far = SX - 1 - EDGE;
        if (windX > 0F && x1 >= far && blown(far, top, 0, top) && light(0, SHIFT - 1, 0, top)) dx = SHIFT;
        else if (windX < 0F && x0 <= EDGE && blown(0, EDGE, 0, top) && light(SX - SHIFT, top, 0, top)) dx = -SHIFT;
        if (windZ > 0F && z1 >= far && blown(0, top, far, top) && light(0, top, 0, SHIFT - 1)) dz = SHIFT;
        else if (windZ < 0F && z0 <= EDGE && blown(0, top, 0, EDGE) && light(0, top, SZ - SHIFT, top)) dz = -SHIFT;
        if (dx == 0 && dz == 0) return false;
        move(dx, dz);
        for (int y = 0; y < SY; y++) for (int z = 0; z < SZ; z++) for (int x = 0; x < SX; x++) {
            if (inside(x + dx, y, z + dz)) continue;
            setClosed(index(x, y, z), mask.closed(x + shiftX, y, z + shiftZ));
        }
        for (int z = 0; z < SZ; z++) for (int x = 0; x < SX; x++) {
            if (inside(x + dx, 0, z + dz)) continue;
            setRoof(z * SX + x, mask.roofed(x + shiftX, z + shiftZ));
        }
        return true;
    }

    // whether these columns (inclusive) hold smoke the wind carries: in an open cell with no roof and wind on it (a cloud
    // under a roof that spread to the box's side stays where it is, and so does its box)
    private boolean blown(int ax, int bx, int az, int bz) {
        if (airStale) air();
        for (int y = y0; y <= y1; y++) for (int z = Math.max(az, z0); z <= Math.min(bz, z1); z++) for (int x = Math.max(ax, x0); x <= Math.min(bx, x1); x++) {
            int i = index(x, y, z);
            if (d[i] >= GONE && air[i] > 0F && !shelteredAt(i)) return true;
        }
        return false;
    }

    // whether these columns (inclusive) hold at most DROP_MAX of the cloud (what blasts hold counted): the box can leave
    // them behind
    private boolean light(int ax, int bx, int az, int bz) {
        if (bx < x0 || ax > x1 || bz < z0 || az > z1) return true;
        double in = 0, all = 0;
        for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
            int i = index(x, y, z);
            float v = d[i] + (held != null ? held[i] : 0F);
            all += v;
            if (x >= ax && x <= bx && z >= az && z <= bz) in += v;
        }
        return in <= all * DROP_MAX;
    }

    // the cells, what blasts hold, the walls, the roofs and the cells the smoke comes out of, dx, dz cells back (the box
    // goes dx, dz on); what leaves the box is gone, what comes in is empty and open until it is read
    private void move(int dx, int dz) {
        float[] was = d.clone();
        long[] wasClosed = closed.clone(), wasRoof = roof.clone();
        float[] wasHeld = held != null ? held.clone() : null;
        int[] wasBack = back != null ? back.clone() : null;
        java.util.Arrays.fill(closed, 0L);
        java.util.Arrays.fill(roof, 0L);
        for (int y = 0; y < SY; y++) for (int z = 0; z < SZ; z++) for (int x = 0; x < SX; x++) {
            int i = index(x, y, z), fx = x + dx, fz = z + dz;
            if (!inside(fx, y, fz)) {
                d[i] = 0F;
                if (held != null) {
                    held[i] = 0F;
                    back[i] = 0;
                }
                continue;
            }
            int j = index(fx, y, fz);
            d[i] = was[j];
            if ((wasClosed[j >>> 6] & 1L << j) != 0L) setClosed(i, true);
            if (held != null) {
                held[i] = wasHeld[j];
                back[i] = wasBack[j];
            }
        }
        for (int z = 0; z < SZ; z++) for (int x = 0; x < SX; x++) {
            int fx = x + dx, fz = z + dz;
            if (fx >= 0 && fz >= 0 && fx < SX && fz < SZ && (wasRoof[(fz * SX + fx) >>> 6] & 1L << (fz * SX + fx)) != 0L) setRoof(z * SX + x, true);
        }
        remap(emitters, dx, dz);
        for (Feed f : feeds) remap(f.cells, dx, dz);
        // the box goes along; a strip it left behind (light()) leaves it within the grid, the next step's bounds() tighten it
        if (occupied()) {
            x0 = Math.max(0, x0 - dx);
            x1 = Math.min(SX - 1, x1 - dx);
            z0 = Math.max(0, z0 - dz);
            z1 = Math.min(SZ - 1, z1 - dz);
            if (x1 < x0 || z1 < z0) {
                x0 = SX;
                y0 = SY;
                z0 = SZ;
                x1 = y1 = z1 = -1;
            }
        }
        shiftX += dx;
        shiftZ += dz;
        airStale = true;
    }

    // box indices dx, dz cells back; one that leaves the box is -1
    private static void remap(int[] cells, int dx, int dz) {
        for (int k = 0; k < cells.length; k++) {
            int i = cells[k];
            if (i < 0) continue;
            int x = i % SX - dx, z = i / SX % SZ - dz, y = i / (SX * SZ);
            cells[k] = inside(x, y, z) ? index(x, y, z) : -1;
        }
    }

    private void thin() {
        int slot = age / 10;
        float base = kind.fade();
        for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
            int row = (y * SZ + z) * SX;
            for (int x = x0; x <= x1; x++) {
                int i = row + x;
                float v = d[i], h = held != null ? held[i] : 0F;
                if (v == 0F && h == 0F) continue;
                float f = base + (unit(i, slot, 1) - 0.5F) * 0.03F;
                v *= f;
                d[i] = v < GONE ? 0F : v;
                if (h != 0F) {
                    h *= f;
                    held[i] = h < GONE ? 0F : h;
                }
            }
        }
    }

    private void bounds() {
        if (!occupied()) return;
        int ax = Math.max(0, x0 - 1), ay = Math.max(0, y0 - 1), az = Math.max(0, z0 - 1);
        int bx = Math.min(SX - 1, x1 + 1), by = Math.min(SY - 1, y1 + 1), bz = Math.min(SZ - 1, z1 + 1);
        x0 = SX;
        y0 = SY;
        z0 = SZ;
        x1 = y1 = z1 = -1;
        for (int y = ay; y <= by; y++) for (int z = az; z <= bz; z++) {
            int row = (y * SZ + z) * SX;
            for (int x = ax; x <= bx; x++) {
                if (d[row + x] <= 0F) {
                    d[row + x] = 0F;
                    if (!holds(row + x)) continue;
                }
                if (x < x0) x0 = x;
                if (x > x1) x1 = x;
                if (y < y0) y0 = y;
                if (y > y1) y1 = y;
                if (z < z0) z0 = z;
                if (z > z1) z1 = z;
            }
        }
    }

    // a number in [0, 1) from the seed, a cell and a time slot: the same on every side
    private float unit(int cell, int slot, int salt) {
        long h = seed ^ 0x9E3779B97F4A7C15L * (cell + 1) ^ 0xC2B2AE3D27D4EB4FL * (slot + 31L * salt + 1);
        h = (h ^ h >>> 30) * 0xBF58476D1CE4E5B9L;
        h = (h ^ h >>> 27) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 40) / (float) (1 << 24);
    }

    /** The cells' densities, bit for bit, with the age: what the server and a client compare. */
    public long hash() {
        return fold(0xCBF29CE484222325L ^ age);
    }

    /** The same without the age: changes when a density does. */
    public long contentHash() {
        return fold(0xCBF29CE484222325L);
    }

    private long fold(long h) {
        if (shiftX != 0 || shiftZ != 0) {
            h = (h ^ shiftX) * 0x100000001B3L;
            h = (h ^ shiftZ) * 0x100000001B3L;
        }
        for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
            int i = index(x, y, z);
            float v = d[i];
            if (v == 0F) continue;
            h = (h ^ i) * 0x100000001B3L;
            h = (h ^ Float.floatToRawIntBits(v)) * 0x100000001B3L;
        }
        if (held != null) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
            int i = index(x, y, z);
            if (held[i] == 0F) continue;
            h = (h ^ ~i) * 0x100000001B3L;
            h = (h ^ Float.floatToRawIntBits(held[i])) * 0x100000001B3L;
            h = (h ^ back[i]) * 0x100000001B3L;
        }
        for (Feed f : feeds) {
            h = (h ^ f.from) * 0x100000001B3L;
            h = (h ^ Float.floatToRawIntBits(f.each)) * 0x100000001B3L;
            for (int c : f.cells) h = (h ^ c) * 0x100000001B3L;
        }
        return h;
    }

    // ---- stirred: a bullet through the cloud, a blast near it (SmokeClouds.stirLine, stirBlast)

    /**
     * A bullet's path through the cloud, box coordinates in blocks: every cell it crosses keeps {@code keep} of what it
     * has and the cells next to them {@code keepNear}, each once however the path runs. The same on every side.
     */
    public void stirLine(double ax, double ay, double az, double bx, double by, double bz, float keep, float keepNear) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int n = (int) Math.min(256, Math.ceil(len * 4.0));
        long[] hit = new long[closed.length];
        for (int s = 0; s <= n; s++) {
            double t = n == 0 ? 0.0 : s / (double) n;
            int x = (int) Math.floor(ax + dx * t), y = (int) Math.floor(ay + dy * t), z = (int) Math.floor(az + dz * t);
            if (inside(x, y, z)) {
                int i = index(x, y, z);
                hit[i >>> 6] |= 1L << i;
            }
        }
        long[] near = new long[closed.length];
        for (int i = 0; i < CELLS; i++) {
            if ((hit[i >>> 6] & 1L << i) == 0L) continue;
            int x = i % SX, z = i / SX % SZ, y = i / (SX * SZ);
            for (int f = 0; f < 6; f++) {
                int nx = x + (f == 0 ? 1 : f == 1 ? -1 : 0), ny = y + (f == 2 ? 1 : f == 3 ? -1 : 0), nz = z + (f == 4 ? 1 : f == 5 ? -1 : 0);
                if (!inside(nx, ny, nz)) continue;
                int j = index(nx, ny, nz);
                if ((hit[j >>> 6] & 1L << j) == 0L) near[j >>> 6] |= 1L << j;
            }
        }
        for (int i = 0; i < CELLS; i++) {
            if (d[i] == 0F) continue;
            if ((hit[i >>> 6] & 1L << i) != 0L) d[i] = cut(d[i] * keep);
            else if ((near[i >>> 6] & 1L << i) != 0L) d[i] = cut(d[i] * keepNear);
        }
    }

    /**
     * A blast at a point, box coordinates: a cell whose centre is {@code r} from it keeps {@code 1 - most * (1 - (r /
     * reach)^2)} of what it has (in the middle {@code 1 - most}, at {@code reach} and past it all). The rest is gone, or
     * with {@code back} held to come back: from {@code BACK_DELAY * (1 - r / reach)} ticks on.
     */
    public void stirBlast(double cx, double cy, double cz, double reach, float most, boolean back) {
        if (!(reach > 0)) return;
        int ax = Math.max(0, (int) Math.floor(cx - reach)), ay = Math.max(0, (int) Math.floor(cy - reach)), az = Math.max(0, (int) Math.floor(cz - reach));
        int bx = Math.min(SX - 1, (int) Math.floor(cx + reach)), by = Math.min(SY - 1, (int) Math.floor(cy + reach)), bz = Math.min(SZ - 1, (int) Math.floor(cz + reach));
        double r2 = reach * reach;
        for (int y = ay; y <= by; y++) for (int z = az; z <= bz; z++) for (int x = ax; x <= bx; x++) {
            int i = index(x, y, z);
            // an emptied cell still holding from a blast before waits again
            if (d[i] == 0F && !(back && holds(i))) continue;
            double ex = x + 0.5 - cx, ey = y + 0.5 - cy, ez = z + 0.5 - cz;
            double q = (ex * ex + ey * ey + ez * ez) / r2;
            if (q >= 1.0) continue;
            float was = d[i], left = cut(was * (float) (1.0 - most * (1.0 - q)));
            d[i] = left;
            if (!back) continue;
            if (held == null) {
                held = new float[CELLS];
                this.back = new int[CELLS];
            }
            int from = age + (int) Math.round(BACK_DELAY * (1.0 - Math.sqrt(q)));
            // a back age left from held smoke that thinned away counts no more (it is in no hash nor packet)
            this.back[i] = held[i] > 0F ? Math.max(this.back[i], from) : from;
            held[i] += was - left;
        }
    }

    private static float cut(float v) {
        return v < GONE ? 0F : v;
    }

    /**
     * The cells that lie on a floor (open, a closed cell under them) with this much or more in them, as box indices,
     * lowest first: where a heavy gas wets the ground.
     */
    public int[] floorCells(float least) {
        if (!occupied()) return new int[0];
        int[] out = new int[64];
        int n = 0;
        for (int y = Math.max(1, y0); y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
            int i = index(x, y, z);
            if (d[i] < least || closedAt(i) || !closedAt(i - SX * SZ)) continue;
            if (n == out.length) out = java.util.Arrays.copyOf(out, n * 2);
            out[n++] = i;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    // ---- the whole state, for a client that went apart from the server

    public void write(DataOutput out) throws IOException {
        out.writeShort(age);
        out.writeShort(shiftX);
        out.writeShort(shiftZ);
        out.writeByte(x0);
        out.writeByte(y0);
        out.writeByte(z0);
        out.writeByte(x1);
        out.writeByte(y1);
        out.writeByte(z1);
        for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) out.writeFloat(d[index(x, y, z)]);
        // the feeds, with the cells they come out of
        out.writeByte(feeds.size());
        for (Feed f : feeds) {
            out.writeShort(f.from);
            out.writeFloat(f.each);
            out.writeByte(f.cells.length);
            // a cell the box left behind: 0xFFFF
            for (int c : f.cells) out.writeShort(c);
        }
        // what blasts hold, with the age it comes back from (an empty grid has no cells and nothing more)
        if (!occupied()) return;
        boolean any = false;
        if (held != null) for (int y = y0; y <= y1 && !any; y++) for (int z = z0; z <= z1 && !any; z++) for (int x = x0; x <= x1 && !any; x++) any = held[index(x, y, z)] != 0F;
        out.writeBoolean(any);
        if (any) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
            int i = index(x, y, z);
            out.writeFloat(held[i]);
            out.writeShort(held[i] != 0F ? back[i] : 0);
        }
    }

    /**
     * Takes a state written by {@link #write}. Everything is read and checked first and the grid is changed only when
     * all of it is good: an {@link IOException} (a short or broken packet, a box outside the grid, an age or a density
     * that no cloud has) leaves the grid as it was.
     */
    public void read(DataInput in) throws IOException {
        int a = in.readUnsignedShort();
        int sx = in.readShort(), sz = in.readShort();
        if (Math.abs(sx) > SHIFT_MAX || Math.abs(sz) > SHIFT_MAX || sx % SHIFT != 0 || sz % SHIFT != 0) throw new IOException("bad shift");
        int ax = in.readByte(), ay = in.readByte(), az = in.readByte(), bx = in.readByte(), by = in.readByte(), bz = in.readByte();
        boolean empty = ax == SX && ay == SY && az == SZ && bx == -1 && by == -1 && bz == -1;
        float[] cells = null, keep = null;
        int[] when = null;
        if (!empty) {
            // a box with all six corners in the grid and no side backwards
            if (!inside(ax, ay, az) || !inside(bx, by, bz) || bx < ax || by < ay || bz < az) throw new IOException("bad box");
            cells = new float[(bx - ax + 1) * (by - ay + 1) * (bz - az + 1)];
            for (int i = 0; i < cells.length; i++) {
                float v = in.readFloat();
                if (!(v >= 0F && v < 100F)) throw new IOException("bad density");
                cells[i] = v;
            }
        }
        int nf = in.readUnsignedByte();
        if (nf > FEEDS_MAX) throw new IOException("bad feeds");
        java.util.List<Feed> fed = new java.util.ArrayList<>(nf);
        for (int k = 0; k < nf; k++) {
            int from = in.readUnsignedShort();
            float each = in.readFloat();
            int n = in.readUnsignedByte();
            if (from > a || !(each > 0F && each < 100F) || n == 0 || n > Math.max(1, kind.bomblets())) throw new IOException("bad feed");
            if (k > 0 && from < fed.getLast().from) throw new IOException("bad feed order");
            int[] at = new int[n];
            for (int j = 0; j < n; j++) {
                at[j] = in.readUnsignedShort();
                if (at[j] == 0xFFFF) at[j] = -1;
                else if (at[j] >= CELLS) throw new IOException("bad feed cell");
            }
            fed.add(new Feed(from, at, each));
        }
        // no older than the cloud can live from its last feed
        int last = fed.isEmpty() ? 0 : fed.getLast().from;
        if (a > last + kind.maxAge() + 1) throw new IOException("bad age");
        if (!empty) {
            if (in.readBoolean()) {
                keep = new float[cells.length];
                when = new int[cells.length];
                for (int i = 0; i < cells.length; i++) {
                    float v = in.readFloat();
                    int w = in.readUnsignedShort();
                    if (!(v >= 0F && v < 100F)) throw new IOException("bad held");
                    if (w > last + kind.maxAge() + 1 + BACK_DELAY) throw new IOException("bad back");
                    keep[i] = v;
                    when[i] = w;
                }
            }
        }
        feeds.clear();
        feeds.addAll(fed);
        java.util.Arrays.fill(d, 0F);
        if (held != null) {
            java.util.Arrays.fill(held, 0F);
            java.util.Arrays.fill(back, 0);
        }
        if (!empty) {
            if (keep != null && held == null) {
                held = new float[CELLS];
                back = new int[CELLS];
            }
            int i = 0;
            for (int y = ay; y <= by; y++) for (int z = az; z <= bz; z++) for (int x = ax; x <= bx; x++) {
                int c = index(x, y, z);
                d[c] = cells[i];
                if (keep != null) {
                    held[c] = keep[i];
                    back[c] = when[i];
                }
                i++;
            }
        }
        age = a;
        if (sx != shiftX || sz != shiftZ) {
            // the box is elsewhere than here: the cells the smoke comes out of follow (from where they were placed: one
            // this copy's box had left behind comes back), the walls are read again
            emitters = placed.clone();
            remap(emitters, sx, sz);
            shiftX = sx;
            shiftZ = sz;
            rescan = true;
            airStale = true;
        }
        x0 = ax;
        y0 = ay;
        z0 = az;
        x1 = bx;
        y1 = by;
        z1 = bz;
    }
}
