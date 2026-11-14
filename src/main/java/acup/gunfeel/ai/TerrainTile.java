package acup.gunfeel.ai;

/**
 * The lie of the land over one chunk (16 x 16 columns), as the sky sees it: for each column where a mob would stand
 * on the ground, how much water lies over it and how high the leaves stand above it. Copied from the game's own
 * heightmaps on the server thread and never changed after, so any thread reads it. Columns are (z &lt;&lt; 4) | x.
 *
 * <p>With it the same in coarse cells of {@link #CELL} x {@link #CELL} columns, sixteen to a tile, (cz &lt;&lt; 2) | cx:
 * the lowest, middle and highest surface, how many of its columns stand under leaves and how many in water. What
 * needs the tiles around (how the place stands against the land about it, how far one sees from it) is in
 * {@link TerrainDerived}.
 */
public final class TerrainTile {
    /** columns a side of a coarse cell, and coarse cells a side of a tile */
    public static final int CELL = 4, CELLS = 4;
    /** leaves this high over the ground and higher are a crown over a mob's head */
    static final int CROWN = 2;
    /** water this deep and deeper is swum, shallower waded */
    static final int DEEP = 2;

    public final long key;
    /** counts the copies of this chunk that differed from the one before */
    public final int version;
    /** the block a mob's feet are in on the ground of each column: over the bed where there is water */
    private final short[] ground;
    /** blocks of leaves and air under them over the ground (or the water), 0..127 */
    private final byte[] canopy;
    /** blocks of water over the bed, 0..127 */
    private final byte[] water;
    private final short[] lo, mid, hi;
    private final byte[] crowned, wet, deep;

    private TerrainTile(long key, int version, short[] ground, byte[] canopy, byte[] water) {
        this.key = key;
        this.version = version;
        this.ground = ground;
        this.canopy = canopy;
        this.water = water;
        int n = CELLS * CELLS;
        lo = new short[n];
        mid = new short[n];
        hi = new short[n];
        crowned = new byte[n];
        wet = new byte[n];
        deep = new byte[n];
        for (int c = 0; c < n; c++) {
            int x0 = (c & 3) * CELL, z0 = (c >> 2) * CELL;
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, sum = 0;
            for (int z = z0; z < z0 + CELL; z++)
                for (int x = x0; x < x0 + CELL; x++) {
                    int i = z << 4 | x, s = ground[i] + water[i];
                    min = Math.min(min, s);
                    max = Math.max(max, s);
                    sum += s;
                    if (canopy[i] >= CROWN) crowned[c]++;
                    if (water[i] > 0) wet[c]++;
                    if (water[i] >= DEEP) deep[c]++;
                }
            lo[c] = (short) min;
            hi[c] = (short) max;
            mid[c] = (short) Math.floorDiv(sum + CELL * CELL / 2, CELL * CELL);
        }
    }

    /** the three arrays, 256 each, are the tile's from here on */
    public static TerrainTile of(long key, int version, short[] ground, byte[] canopy, byte[] water) {
        if (ground.length != 256 || canopy.length != 256 || water.length != 256) throw new IllegalArgumentException("a chunk has 256 columns");
        return new TerrainTile(key, version, ground, canopy, water);
    }

    /** a chunk's key, as ChunkPos.asLong */
    public static long key(int cx, int cz) {
        return (cx & 0xFFFFFFFFL) | (cz & 0xFFFFFFFFL) << 32;
    }

    public static int x(long key) {
        return (int) key;
    }

    public static int z(long key) {
        return (int) (key >> 32);
    }

    /** the same columns as another copy of the chunk */
    boolean same(TerrainTile o) {
        return java.util.Arrays.equals(ground, o.ground) && java.util.Arrays.equals(canopy, o.canopy) && java.util.Arrays.equals(water, o.water);
    }

    /** the same coarse cells as another copy of the chunk: all that the layers worked out from the tiles read (TerrainDerived) */
    boolean sameCells(TerrainTile o) {
        return java.util.Arrays.equals(lo, o.lo) && java.util.Arrays.equals(mid, o.mid) && java.util.Arrays.equals(hi, o.hi)
                && java.util.Arrays.equals(crowned, o.crowned);
    }

    // ---- columns, by block x z (any, the low four bits are taken)

    /** the block a mob's feet are in standing on the ground there (on the bed, under water) */
    public int ground(int x, int z) {
        return ground[(z & 15) << 4 | (x & 15)];
    }

    /** blocks of water over the bed there */
    public int water(int x, int z) {
        return water[(z & 15) << 4 | (x & 15)];
    }

    /** the first block over the ground or the water there */
    public int surface(int x, int z) {
        int i = (z & 15) << 4 | (x & 15);
        return ground[i] + water[i];
    }

    /** how high the leaves reach over the ground (or the water) there, 0: open sky */
    public int canopy(int x, int z) {
        return canopy[(z & 15) << 4 | (x & 15)];
    }

    // ---- coarse cells, by block x z

    static int cell(int x, int z) {
        return ((z & 15) >> 2) << 2 | ((x & 15) >> 2);
    }

    public int lo(int x, int z) {
        return lo[cell(x, z)];
    }

    public int mid(int x, int z) {
        return mid[cell(x, z)];
    }

    public int hi(int x, int z) {
        return hi[cell(x, z)];
    }

    /** the part of the coarse cell's columns under a crown, 0..1 */
    public float crowned(int x, int z) {
        return crowned[cell(x, z)] / (float) (CELL * CELL);
    }

    /** the part of the coarse cell's columns in water, 0..1 */
    public float wet(int x, int z) {
        return wet[cell(x, z)] / (float) (CELL * CELL);
    }

    /** the part of the coarse cell's columns in water to swim in, 0..1 */
    public float deep(int x, int z) {
        return deep[cell(x, z)] / (float) (CELL * CELL);
    }

    // by coarse cell index, for the builder
    int loAt(int c) {
        return lo[c];
    }

    int midAt(int c) {
        return mid[c];
    }

    int hiAt(int c) {
        return hi[c];
    }

    int crownedAt(int c) {
        return crowned[c];
    }
}
