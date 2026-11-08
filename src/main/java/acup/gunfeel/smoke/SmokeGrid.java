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
 * <p>Only the box of occupied cells and one cell around it is stepped.
 */
public final class SmokeGrid {
    public static final int SX = 25, SY = 13, SZ = 25, CELLS = SX * SY * SZ;
    /** The burst cell inside the box: 12 cells to either side, 4 down and 8 up. */
    public static final int OX = 12, OY = 4, OZ = 12;
    /** Below this a thinning cell is empty. */
    public static final float GONE = 0.02F;
    /** Ticks the middle of a blast's hole waits before its smoke comes back (its edge: none). */
    public static final int BACK_DELAY = 30;
    /** Share of what a cell holds that comes back a tick once its time came. */
    public static final float BACK_RATE = 1F / 8F;

    /** Whether the cell at box coordinates x, y, z is closed to smoke (a full block, a liquid, an unloaded chunk). */
    public interface Mask {
        boolean closed(int x, int y, int z);
    }

    private final SmokeKind kind;
    private final long seed;
    private final float amount;
    private final float[] d = new float[CELLS], next = new float[CELLS];
    private final long[] closed = new long[(CELLS + 63) >>> 6];
    // smoke a blast pushed out of a cell, and the age from which it comes back; made at the first such blast
    private float[] held;
    private int[] back;
    private final float dirX, dirZ;
    private int[] emitters = new int[0];
    private int age;
    // the occupied cells, inclusive; empty while x1 < x0
    private int x0 = SX, y0 = SY, z0 = SZ, x1 = -1, y1 = -1, z1 = -1;

    /**
     * @param dirX, dirZ the way the grenade flew (the bomblets fan out along it; a straight-down shot rings them round)
     * @param scale      share of the kind's volume
     */
    public SmokeGrid(SmokeKind kind, long seed, float dirX, float dirZ, float scale) {
        this.kind = kind;
        this.seed = seed;
        this.amount = kind.volume() * Math.max(0F, scale);
        this.dirX = dirX;
        this.dirZ = dirZ;
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
        return age >= kind.release() && !occupied();
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
        for (int y = 0; y < SY; y++) for (int z = 0; z < SZ; z++) for (int x = 0; x < SX; x++) setClosed(index(x, y, z), mask.closed(x, y, z));
    }

    /**
     * Reads one part of the cells again (doors open and shut): cells {@code part * CELLS / parts} up to the next part.
     * A cell that closes loses its smoke.
     */
    public void refresh(Mask mask, int part, int parts) {
        int from = (int) ((long) part * CELLS / parts), to = (int) ((long) (part + 1) * CELLS / parts);
        for (int i = from; i < to; i++) {
            int x = i % SX, z = i / SX % SZ, y = i / (SX * SZ);
            boolean c = mask.closed(x, y, z);
            if (c) {
                d[i] = 0F;
                if (held != null) held[i] = 0F;
            }
            setClosed(i, c);
        }
    }

    /**
     * Picks the cells the smoke comes out of: the burst cell (or the nearest open one, a grenade that burst in a wall)
     * and the bomblets fanned out along the flight, each as far as it gets before a closed cell.
     */
    public void place() {
        int center = -1;
        for (int r = 0; r <= 2 && center < 0; r++) center = nearestOpen(r);
        if (center < 0) {
            emitters = new int[0];
            return;
        }
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
                angle = base + Math.toRadians(kind.fan()) * share + (unit(b, 0, 7) - 0.5) * 0.2;
            } else {
                angle = 2.0 * Math.PI * (b - 1) / (n - 1) + unit(b, 0, 8) * 0.5;
            }
            double reach = kind.reach() * (0.55 + 0.45 * unit(b, 0, 9));
            int last = center;
            for (double t = 0.25; t <= reach; t += 0.25) {
                int x = (int) Math.floor(cx + 0.5 + StrictMath.cos(angle) * t), z = (int) Math.floor(cz + 0.5 + StrictMath.sin(angle) * t);
                if (!inside(x, cy, z) || closedAt(index(x, cy, z))) break;
                last = index(x, cy, z);
            }
            out[count++] = last;
        }
        emitters = out;
    }

    private int nearestOpen(int r) {
        // shells of growing size, upward first: a grenade in the floor lets its smoke out above it
        for (int dy = r; dy >= -r; dy--) for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != r) continue;
            int x = OX + dx, y = OY + dy, z = OZ + dz;
            if (inside(x, y, z) && !closedAt(index(x, y, z))) return index(x, y, z);
        }
        return -1;
    }

    // ---- the step

    public void step() {
        if (age < kind.release() && emitters.length > 0) {
            float each = amount / (kind.release() * (float) emitters.length);
            for (int e : emitters) if (!closedAt(e)) d[e] += each;
            grow(emitters);
        }
        if (occupied()) {
            if (held != null) giveBack();
            spread();
            if (age >= kind.release() + kind.hold()) thin();
        }
        age++;
        bounds();
    }

    private void grow(int[] cells) {
        for (int i : cells) {
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
        System.arraycopy(d, 0, next, 0, CELLS);
        float k = kind.spread(), up = k * (1F + kind.rise()), down = k * (1F - kind.rise()), lift = kind.lift(), liftFrom = kind.fill() * 0.5F;
        // the faces between a cell and its +x, +y, +z neighbour, one cell around the occupied box (and the box's own
        // faces out of the grid: lost)
        int ax = Math.max(0, x0 - 1), ay = Math.max(0, y0 - 1), az = Math.max(0, z0 - 1);
        int bx = Math.min(SX - 1, x1 + 1), by = Math.min(SY - 1, y1 + 1), bz = Math.min(SZ - 1, z1 + 1);
        for (int y = ay; y <= by; y++) for (int z = az; z <= bz; z++) {
            int row = (y * SZ + z) * SX;
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
                        } else if (db > liftFrom) f += lift * db;
                        flow(a, b, f, da, db);
                    }
                } else next[a] -= limit(up * pa + (lift > 0F && da > liftFrom ? lift * da : 0F), da);
            }
        }
        System.arraycopy(next, 0, d, 0, CELLS);
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
        out.writeByte(x0);
        out.writeByte(y0);
        out.writeByte(z0);
        out.writeByte(x1);
        out.writeByte(y1);
        out.writeByte(z1);
        for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) out.writeFloat(d[index(x, y, z)]);
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
        int ax = in.readByte(), ay = in.readByte(), az = in.readByte(), bx = in.readByte(), by = in.readByte(), bz = in.readByte();
        if (a > kind.maxAge() + 1) throw new IOException("bad age");
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
            if (in.readBoolean()) {
                keep = new float[cells.length];
                when = new int[cells.length];
                for (int i = 0; i < cells.length; i++) {
                    float v = in.readFloat();
                    int w = in.readUnsignedShort();
                    if (!(v >= 0F && v < 100F)) throw new IOException("bad held");
                    if (w > kind.maxAge() + 1 + BACK_DELAY) throw new IOException("bad back");
                    keep[i] = v;
                    when[i] = w;
                }
            }
        }
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
        x0 = ax;
        y0 = ay;
        z0 = az;
        x1 = bx;
        y1 = by;
        z1 = bz;
    }
}
