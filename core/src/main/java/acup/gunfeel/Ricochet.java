package acup.gunfeel;

import org.jspecify.annotations.Nullable;

/**
 * NTM's standard ricochet (BulletConfig.LAMBDA_STANDARD_RICOCHET) as our code asks about it, without the game: a
 * bullet meeting a face at no more than its ricochetAngle off grazing bounces. The server stops it in the block
 * instead where the bullet rules say "ricochet ... no" ({@link #stopsInstead}); the client's tracer bounces as NTM
 * would ({@link #bounces}: not off what NTM's ricochet breaks, glass and glowstone); off steel the ricochet's sound is
 * ours ({@link #steelSound}). The face is its normal's steps (Direction.getStepX/Y/Z), the velocity the bullet's;
 * the arithmetic is the callers' as it was, in the same order, each side's comparison its own (they part on a
 * velocity that is not a number). RicochetTest holds it to both as they were.
 */
public final class Ricochet {
    private Ricochet() {
    }

    // degrees off grazing, of a velocity of length l
    private static double angle(int fx, int fy, int fz, double vx, double vy, double vz, double l) {
        double cos = (fx * vx + fy * vy + fz * vz) / l;
        return Math.abs(Math.toDegrees(Math.acos(Math.clamp(cos, -1.0, 1.0))) - 90.0);
    }

    // Vec3.length
    private static double length(double vx, double vy, double vz) {
        return Math.sqrt(vx * vx + vy * vy + vz * vz);
    }

    /**
     * the server: NTM would bounce it (within {@code maxAngle} of grazing; a velocity of no length does not), the
     * bullet rules do not let it ({@code ruleBounces} false): it stops there. Not past the angle, as the server asked
     * it: a velocity that is not a number stops it
     */
    public static boolean stopsInstead(boolean ruleBounces, int fx, int fy, int fz, double vx, double vy, double vz, float maxAngle) {
        if (ruleBounces) return false;
        double l = length(vx, vy, vz);
        if (l < 1e-9) return false;
        return !(angle(fx, fy, fz, vx, vy, vz, l) > maxAngle);
    }

    /**
     * the tracer: it bounces as NTM's would (within {@code maxAngle} of grazing, as the client asked it: a velocity that
     * is not a number does not), unless NTM's ricochet breaks the block ({@code breaks}) or the rules say no
     */
    public static boolean bounces(boolean breaks, boolean ruleBounces, int fx, int fy, int fz, double vx, double vy, double vz, float maxAngle) {
        if (breaks || !ruleBounces) return false;
        double l = length(vx, vy, vz);
        if (l < 1e-9) return false;
        return angle(fx, fy, fz, vx, vy, vz, l) <= maxAngle;
    }

    /** the block's hit sound (the bullet rules') is steel or metal: the ricochet's sound is ours */
    public static boolean steelSound(@Nullable String hitSound) {
        return hitSound != null && (hitSound.startsWith("ntmgunfeel:bullet.hit.steel") || hitSound.startsWith("ntmgunfeel:bullet.hit.metal"));
    }
}
