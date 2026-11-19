package acup.gunfeel;

/**
 * Minecraft's block and section positions packed in a long, for core's code: {@code BlockPos.asLong(x, y, z)} and
 * {@code BlockPos.getX/getY/getZ(long)}, {@code SectionPos.asLong(x, y, z)} and {@code SectionPos.x/y/z(long)}, each
 * doing the operations of 26.2's code in the same order with its fields' values, so a key comes out bit for bit the
 * same, a coordinate past the packed field included (cut by the mask, its top bit spread as the sign on unpacking).
 * PosLongTest on 26.2 holds it, against BlockPos and SectionPos themselves. A method is added here only with its line in
 * PosLongTest, from the bytecode of BlockPos or SectionPos.
 */
public final class PosLong {
    private PosLong() {
    }

    // BlockPos: PACKED_HORIZONTAL_LENGTH = 1 + log2(smallestEncompassingPowerOfTwo(30000000)), PACKED_Y_LENGTH = 64 - 2 * that
    private static final int PACKED_HORIZONTAL_LENGTH = 26, PACKED_Y_LENGTH = 12;
    private static final long PACKED_X_MASK = (1L << PACKED_HORIZONTAL_LENGTH) - 1L, PACKED_Y_MASK = (1L << PACKED_Y_LENGTH) - 1L,
            PACKED_Z_MASK = (1L << PACKED_HORIZONTAL_LENGTH) - 1L;
    private static final int Z_OFFSET = PACKED_Y_LENGTH, X_OFFSET = PACKED_Y_LENGTH + PACKED_HORIZONTAL_LENGTH;

    /** BlockPos.asLong(x, y, z) */
    public static long block(int x, int y, int z) {
        long l = 0L;
        l |= ((long) x & PACKED_X_MASK) << X_OFFSET;
        l |= ((long) y & PACKED_Y_MASK) << 0;
        l |= ((long) z & PACKED_Z_MASK) << Z_OFFSET;
        return l;
    }

    /** BlockPos.getX(long) */
    public static int blockX(long packed) {
        return (int) (packed << 64 - X_OFFSET - PACKED_HORIZONTAL_LENGTH >> 64 - PACKED_HORIZONTAL_LENGTH);
    }

    /** BlockPos.getY(long) */
    public static int blockY(long packed) {
        return (int) (packed << 64 - PACKED_Y_LENGTH >> 64 - PACKED_Y_LENGTH);
    }

    /** BlockPos.getZ(long) */
    public static int blockZ(long packed) {
        return (int) (packed << 64 - Z_OFFSET - PACKED_HORIZONTAL_LENGTH >> 64 - PACKED_HORIZONTAL_LENGTH);
    }

    // SectionPos: x 22 bits at 42, z 22 bits at 20, y 20 bits at 0
    private static final long SECTION_X_MASK = 4194303L, SECTION_Y_MASK = 1048575L, SECTION_Z_MASK = 4194303L;
    private static final int SECTION_Y_OFFSET = 0, SECTION_Z_OFFSET = 20, SECTION_X_OFFSET = 42;

    /** SectionPos.asLong(x, y, z) */
    public static long section(int x, int y, int z) {
        long l = 0L;
        l |= ((long) x & SECTION_X_MASK) << SECTION_X_OFFSET;
        l |= ((long) y & SECTION_Y_MASK) << SECTION_Y_OFFSET;
        l |= ((long) z & SECTION_Z_MASK) << SECTION_Z_OFFSET;
        return l;
    }

    /** SectionPos.x(long) */
    public static int sectionX(long packed) {
        return (int) (packed << 0 >> 42);
    }

    /** SectionPos.y(long) */
    public static int sectionY(long packed) {
        return (int) (packed << 44 >> 44);
    }

    /** SectionPos.z(long) */
    public static int sectionZ(long packed) {
        return (int) (packed << 22 >> 42);
    }
}
