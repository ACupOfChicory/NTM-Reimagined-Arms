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
 * <p>Only the box of occupied cells and one cell around it is stepped.
 */
public final class SmokeGrid {
    public static final int SX = 25, SY = 13, SZ = 25, CELLS = SX * SY * SZ;
    /** The burst cell inside the box: 12 cells to either side, 4 down and 8 up. */
    public static final int OX = 12, OY = 4, OZ = 12;
    /** Below this a thinning cell is empty. */
    public static final float GONE = 0.02F;

    /** Whether the cell at box coordinates x, y, z is closed to smoke (a full block, a liquid, an unloaded chunk). */
    public interface Mask {
        boolean closed(int x, int y, int z);
    }

    private final SmokeKind kind;
    private final long seed;
    private final float amount;
    private final float[] d = new float[CELLS], next = new float[CELLS];
    private final long[] closed = new long[(CELLS + 63) >>> 6];
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
            if (c) d[i] = 0F;
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
                float v = d[i];
                if (v == 0F) continue;
                v *= base + (unit(i, slot, 1) - 0.5F) * 0.03F;
                d[i] = v < GONE ? 0F : v;
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
                    continue;
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
        long h = 0xCBF29CE484222325L ^ age;
        for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
            int i = index(x, y, z);
            float v = d[i];
            if (v == 0F) continue;
            h = (h ^ i) * 0x100000001B3L;
            h = (h ^ Float.floatToRawIntBits(v)) * 0x100000001B3L;
        }
        return h;
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
        float[] cells = null;
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
        java.util.Arrays.fill(d, 0F);
        if (!empty) {
            int i = 0;
            for (int y = ay; y <= by; y++) for (int z = az; z <= bz; z++) for (int x = ax; x <= bx; x++) d[index(x, y, z)] = cells[i++];
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
