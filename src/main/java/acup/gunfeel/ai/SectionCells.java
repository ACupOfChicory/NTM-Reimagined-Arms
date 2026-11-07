package acup.gunfeel.ai;

/**
 * The cells of one chunk section a mob can stand in (feet in the block, head in the one above, something under
 * them), each with its cover in eight directions. Built off the server thread by {@link CellBuilder}, never changed
 * after. Cells are kept by column (z * 16 + x), then by height.
 *
 * <p>Cover of a cell, an int: bits 0-7 the block beside the feet hides it in that direction, 8-15 the block beside
 * the head does, 16-23 the block beside the feet stops bullets, 24-31 the one beside the head does. A diagonal is
 * covered by its own block or by both blocks either side of it.
 */
public final class SectionCells {
    // cell flags
    public static final int WATER = 1, CLIMB = 2, ROOF = 4, HEAD3 = 8, UNKNOWN = 16, DOOR_NEAR = 32, EDGE = 64, NARROW = 128;

    public final long key;
    /** the map's build count of the section this was built for (see {@link TacticalMap}) */
    public final int stamp;
    final short[] pos;
    final int[] cover;
    final byte[] flags;
    final short[] colStart;
    /** the time the build took, for /armsai info */
    public final long buildNanos;

    SectionCells(long key, int stamp, short[] pos, int[] cover, byte[] flags, short[] colStart, long buildNanos) {
        this.key = key;
        this.stamp = stamp;
        this.pos = pos;
        this.cover = cover;
        this.flags = flags;
        this.colStart = colStart;
        this.buildNanos = buildNanos;
    }

    public int count() {
        return pos.length;
    }

    /** the cell with its feet at local x y z, or -1 */
    public int find(int x, int y, int z) {
        int col = z << 4 | x;
        for (int i = colStart[col], end = colStart[col + 1]; i < end; i++) if ((pos[i] >> 8) == y) return i;
        return -1;
    }

    public int x(int i) {
        return pos[i] & 15;
    }

    public int y(int i) {
        return (pos[i] >> 8) & 15;
    }

    public int z(int i) {
        return (pos[i] >> 4) & 15;
    }

    public int cover(int i) {
        return cover[i];
    }

    public int flags(int i) {
        return flags[i] & 0xFF;
    }

    // ---- reading the cover int

    public static int sightFeet(int cover) {
        return cover & 0xFF;
    }

    public static int sightHead(int cover) {
        return (cover >>> 8) & 0xFF;
    }

    public static int hardFeet(int cover) {
        return (cover >>> 16) & 0xFF;
    }

    public static int hardHead(int cover) {
        return (cover >>> 24) & 0xFF;
    }

    /** 0 none, 1 low (beside the feet only), 2 high (beside the head), in direction d */
    public static int height(int cover, int d) {
        int bit = 1 << d;
        if (((sightHead(cover) | hardHead(cover)) & bit) != 0) return 2;
        return ((sightFeet(cover) | hardFeet(cover)) & bit) != 0 ? 1 : 0;
    }

    /** the cover in direction d stops bullets at the height it covers */
    public static boolean hard(int cover, int d) {
        int bit = 1 << d;
        if (((sightHead(cover) | hardHead(cover)) & bit) != 0) return (hardHead(cover) & bit) != 0;
        return (hardFeet(cover) & bit) != 0;
    }
}
