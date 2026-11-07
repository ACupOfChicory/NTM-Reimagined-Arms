package acup.gunfeel.ai;

/**
 * What the AI knows of one block, in a byte: how a mob moves through it, whether it hides and how well it stops
 * bullets. Worked out once per block state on the server thread ({@link BlockClasses}); everything past that (the
 * cells, the rays, the paths) reads only these bytes, so it can run off the server thread.
 */
public final class Voxel {
    private Voxel() {
    }

    // bits 0-1: movement
    public static final int MOVE_MASK = 3;
    /** nothing to bump into: air, grass, flowers, carpet, an open door */
    public static final int OPEN = 0;
    /** a block to stand on that a mob cannot walk through (its collision tops out at 0.5..1) */
    public static final int SOLID = 1;
    /** taller than a block (fences, walls) or not to be stood on (lava): a wall for movement */
    public static final int TALL = 2;
    /** a low block (a bottom slab): stood on, not walked through */
    public static final int LOW = 3;

    /** hides what is behind it (an opaque block, leaves); glass, bars and fences do not */
    public static final int SIGHT = 1 << 2;
    // bits 3-4: how well it stops bullets, 0 (glass, wool, leaves) .. 3 (concrete, steel)
    public static final int PROTECT_SHIFT = 3;
    public static final int PROTECT_MASK = 3 << PROTECT_SHIFT;
    public static final int WATER = 1 << 5;
    /** ladders, vines, scaffolding */
    public static final int CLIMB = 1 << 6;
    /** doors, gates, trapdoors */
    public static final int DOOR = 1 << 7;

    /** an unloaded neighbour: as a stone wall, so no cell leans on what is not known */
    public static final byte UNKNOWN = (byte) (SOLID | SIGHT | 2 << PROTECT_SHIFT);
    public static final byte AIR = 0;

    public static int move(int v) {
        return v & MOVE_MASK;
    }

    public static boolean open(int v) {
        return (v & MOVE_MASK) == OPEN;
    }

    /** a mob stands on it: a full block or a low one */
    public static boolean floor(int v) {
        int m = v & MOVE_MASK;
        return m == SOLID || m == LOW;
    }

    public static boolean sight(int v) {
        return (v & SIGHT) != 0;
    }

    public static int protect(int v) {
        return (v & PROTECT_MASK) >> PROTECT_SHIFT;
    }

    /** cover for a mob behind it: hides it or stops bullets (protection 2 and up) */
    public static boolean covers(int v) {
        return (v & SIGHT) != 0 || protect(v) >= 2;
    }

    /** stops bullets well enough to stand behind under fire */
    public static boolean hard(int v) {
        return protect(v) >= 2;
    }

    public static byte of(int move, boolean sight, int protect, boolean water, boolean climb, boolean door) {
        int v = move & MOVE_MASK;
        if (sight) v |= SIGHT;
        v |= Math.max(0, Math.min(3, protect)) << PROTECT_SHIFT;
        if (water) v |= WATER;
        if (climb) v |= CLIMB;
        if (door) v |= DOOR;
        return (byte) v;
    }
}
