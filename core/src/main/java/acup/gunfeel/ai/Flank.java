package acup.gunfeel.ai;

/** Bearings on the ground for a squad's flanking (AiSquads, CoverQuery): the cosine between two directions and its bounds. */
public final class Flank {
    private Flank() {
    }

    /**
     * flanking cover: off the line from the target to the squad by 45 degrees at least, and short of the target's
     * back (135 degrees): no way past it (cosines)
     */
    public static final double FLANK_COS = 0.7071, BEHIND_COS = -0.7071;

    /** the cosine between (ax, az) and (bx, bz); 1 when either is too short to have a direction */
    public static double cos(double ax, double az, double bx, double bz) {
        double la = Math.hypot(ax, az), lb = Math.hypot(bx, bz);
        return la < 1e-3 || lb < 1e-3 ? 1 : (ax * bx + az * bz) / (la * lb);
    }
}
