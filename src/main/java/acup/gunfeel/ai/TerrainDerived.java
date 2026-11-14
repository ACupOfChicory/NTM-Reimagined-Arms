package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

/**
 * What a chunk's coarse cells ({@link TerrainTile}) are against the land around them, worked out off the server
 * thread from the tiles within {@link #REACH} blocks and never changed after:
 * <ul>
 * <li>how high the cell stands over the land about it, near ({@link #NEAR} blocks around) and far ({@link #FAR}):
 * its middle surface less the mean of theirs. A hill is well over the far mean, a hollow well under;
 * <li>how far one sees from it, standing, level, each of {@link #DIRS} ways: to where the ground rises over the eye
 * or the crowns have thinned the look out, {@link #REACH} at most. Coarse: a look goes cell by cell over the middle
 * heights, not block by block. An open field reads near {@link #REACH}, a wood a dozen or two.
 * </ul>
 * A tile that is not kept (its chunk not loaded, or too far from any mob) ends a look there and is left out of a mean.
 */
public final class TerrainDerived {
    public static final int NEAR = 16, FAR = 48, REACH = 96, DIRS = 16;
    /** the eye over the block the feet are in */
    static final double EYE = 1.6;
    /** a coarse cell wholly under crowns lets this part of a look through; a look thinned to under FADED ends */
    static final double CROWNED_LETS = 0.65, FADED = 0.25;
    /** well over or under the land about: a height, a hollow */
    public static final int HIGH = 4, HOLLOW = -4;

    private static final double[] DX = new double[DIRS], DZ = new double[DIRS];
    static {
        for (int d = 0; d < DIRS; d++) {
            DX[d] = Math.cos(2 * Math.PI * d / DIRS);
            DZ[d] = Math.sin(2 * Math.PI * d / DIRS);
        }
    }

    public final long key;
    /** the version of its own tile it was worked out from */
    public final int version;
    private final byte[] near, far, open, openDir;
    /** the time it took, for /armsai info */
    public final long nanos;

    private TerrainDerived(long key, int version, byte[] near, byte[] far, byte[] open, byte[] openDir, long nanos) {
        this.key = key;
        this.version = version;
        this.near = near;
        this.far = far;
        this.open = open;
        this.openDir = openDir;
        this.nanos = nanos;
    }

    /** where the builder reads the tiles around: null where none is kept. Any thread. */
    @FunctionalInterface
    public interface Tiles {
        @Nullable
        TerrainTile tile(long key);
    }

    /** a reader of tiles that remembers the last one: a look crosses a tile in four steps */
    private static final class Reader {
        final Tiles tiles;
        long lastKey = Long.MIN_VALUE;
        @Nullable TerrainTile last;

        Reader(Tiles tiles) {
            this.tiles = tiles;
        }

        @Nullable TerrainTile at(int x, int z) {
            long k = TerrainTile.key(x >> 4, z >> 4);
            if (k != lastKey) {
                lastKey = k;
                last = tiles.tile(k);
            }
            return last;
        }
    }

    public static TerrainDerived build(Tiles tiles, TerrainTile self) {
        long t0 = System.nanoTime();
        int n = TerrainTile.CELLS * TerrainTile.CELLS;
        byte[] near = new byte[n], far = new byte[n], open = new byte[n], openDir = new byte[n * DIRS];
        Reader r = new Reader(tiles);
        int bx = TerrainTile.x(self.key) << 4, bz = TerrainTile.z(self.key) << 4;
        for (int c = 0; c < n; c++) {
            // the middle of the coarse cell
            int x = bx + (c & 3) * TerrainTile.CELL + TerrainTile.CELL / 2, z = bz + (c >> 2) * TerrainTile.CELL + TerrainTile.CELL / 2;
            int mid = self.midAt(c);
            near[c] = clamp(mid - mean(r, x, z, NEAR, TerrainTile.CELL, mid));
            far[c] = clamp(mid - mean(r, x, z, FAR, 2 * TerrainTile.CELL, mid));
            double eye = mid + EYE;
            int sum = 0;
            for (int d = 0; d < DIRS; d++) {
                int dist = look(r, x, z, eye, DX[d], DZ[d]);
                openDir[c * DIRS + d] = (byte) dist;
                sum += dist;
            }
            open[c] = (byte) ((sum + DIRS / 2) / DIRS);
        }
        return new TerrainDerived(self.key, self.version, near, far, open, openDir, System.nanoTime() - t0);
    }

    /** the mean middle surface of the coarse cells within reach blocks of x z, every step blocks; own where none is kept */
    private static double mean(Reader r, int x, int z, int reach, int step, int own) {
        long sum = 0;
        int count = 0;
        for (int dz = -reach; dz <= reach; dz += step)
            for (int dx = -reach; dx <= reach; dx += step) {
                TerrainTile t = r.at(x + dx, z + dz);
                if (t == null) continue;
                sum += t.mid(x + dx, z + dz);
                count++;
            }
        return count == 0 ? own : sum / (double) count;
    }

    /** how far a level look from the eye goes along dx dz, blocks */
    private static int look(Reader r, int x, int z, double eye, double dx, double dz) {
        double lets = 1;
        for (int s = TerrainTile.CELL; s <= REACH; s += TerrainTile.CELL) {
            int px = (int) Math.floor(x + dx * s), pz = (int) Math.floor(z + dz * s);
            TerrainTile t = r.at(px, pz);
            if (t == null) return s;
            int c = TerrainTile.cell(px, pz);
            int lo = t.loAt(c), hi = t.hiAt(c);
            // the part of the cell's columns that stand over the eye: all of them, none, or between by where the eye is
            // from its lowest to its highest
            double over = eye < lo ? 1 : eye >= hi ? 0 : (hi - eye) / (hi - lo + 1.0);
            if (over >= 1) return s - TerrainTile.CELL / 2;
            lets *= 1 - over;
            lets *= 1 - (1 - CROWNED_LETS) * t.crownedAt(c) / (double) (TerrainTile.CELL * TerrainTile.CELL);
            if (lets < FADED) return s;
        }
        return REACH;
    }

    private static byte clamp(double v) {
        return (byte) Math.max(-127, Math.min(127, Math.round(v)));
    }

    // ---- by block x z

    /** blocks over (under: negative) the mean surface within {@link #NEAR} blocks */
    public int near(int x, int z) {
        return near[TerrainTile.cell(x, z)];
    }

    /** blocks over (under: negative) the mean surface within {@link #FAR} blocks */
    public int far(int x, int z) {
        return far[TerrainTile.cell(x, z)];
    }

    /** how far one sees from there, the mean of all ways, blocks, 0..{@link #REACH} */
    public int open(int x, int z) {
        return open[TerrainTile.cell(x, z)] & 0xFF;
    }

    /** how far one sees from there one way: dir 0 along +x, on round through +z, {@link #DIRS} to a turn */
    public int open(int x, int z, int dir) {
        return openDir[TerrainTile.cell(x, z) * DIRS + (dir & (DIRS - 1))] & 0xFF;
    }

    /** the way ({@link #DIRS} to a turn) nearest the line from a place to another */
    public static int dir(double fromX, double fromZ, double toX, double toZ) {
        double a = Math.atan2(toZ - fromZ, toX - fromX);
        return (int) Math.round(a / (2 * Math.PI) * DIRS) & (DIRS - 1);
    }
}
