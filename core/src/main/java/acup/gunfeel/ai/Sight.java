package acup.gunfeel.ai;

/** Plain sight through leaves and grass along a line (Senses, CoverQuery, AiSoldier): how thin a veil is no hiding. */
public final class Sight {
    private Sight() {
    }

    /** grass, block for block, as a share of leaves: it hides little (the owner's word) */
    public static final double GRASS = 0.15;
    /**
     * A veil this thin or thinner is no hiding (noticed in under twice the time): one behind it is in plain sight, to its
     * cover's choice and its keeping (CoverQuery, AiSoldier) and to a peek round a corner (Senses.clearLine)
     */
    public static final double PLAIN = 4;

    /** in plain sight along the line: no block that hides, and a veil of leaves and grass no thicker than {@link #PLAIN} */
    public static boolean plain(VoxelRay.Source voxels, double x0, double y0, double z0, double x1, double y1, double z1) {
        return VoxelRay.veil(voxels, x0, y0, z0, x1, y1, z1, GRASS, PLAIN) >= 0;
    }
}
