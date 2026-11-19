package acup.gunfeel;

/**
 * NTM's multiblock shapes name the block that holds the hit point (BlockPos.containing): a point on the top face of a
 * block (the elevator's platform on its base) is then put in the block above it. Whether a hit is such a one, so its
 * block is the one behind the face it came in by (PassableClip.owner). Faces by the game's Direction ordinals.
 */
public final class HitOwner {
    // a face's axis (x 0, y 1, z 2) and whether it looks the axis' way
    private static final int[] AXIS = {1, 1, 2, 2, 0, 0};
    private static final boolean[] POSITIVE = {false, true, false, true, false, true};

    private HitOwner() {
    }

    /**
     * the hit at (px, py, pz) on {@code face} of the block (bx, by, bz) lies on that block's far side along the face's
     * axis, and did not start inside: the hit's block is the next one against the face
     */
    public static boolean moves(int face, double px, double py, double pz, int bx, int by, int bz, boolean inside) {
        int axis = AXIS[face];
        double at = axis == 0 ? px : axis == 1 ? py : pz, lo = axis == 0 ? bx : axis == 1 ? by : bz;
        boolean onFar = POSITIVE[face] ? at == lo : at == lo + 1;
        return onFar && !inside;
    }
}
