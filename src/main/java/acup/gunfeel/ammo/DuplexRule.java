package acup.gunfeel.ammo;

import java.util.function.DoubleSupplier;

/**
 * The Duplex round's numbers and geometry (no Minecraft: the tests drive it). Two bullets a shot, out at once: the front
 * one as the gun throws it, the rear one a little behind it along the barrel and off its heading by a normal angle of
 * SIGMA radians each way (cut at CUT), so both go the same way, not as two shots nor as buckshot (owner 2026-11-17; the
 * M198's rear bullet ~1.8 mrad, made a little wider; six times that, 18 mrad cut at 42, owner 2026-11-17: "more
 * spread" — about a block apart at 50).
 */
public final class DuplexRule {
    private DuplexRule() {
    }

    /** the rear bullet's angle off the front one's heading: sigma each way, and the most, radians */
    public static final double SIGMA = 0.018, CUT = 0.042;
    /** how far behind the front bullet the rear one starts, blocks along the heading */
    public static final double BEHIND = 0.25;
    /** each bullet's damage: this share of the same gun's SP round */
    public static final float SHARE = 0.7F;

    /**
     * The rear bullet's heading off the front one's {@code (x, y, z)} (any length; the result is as long): turned by
     * two normal angles of SIGMA about axes across it, the whole angle no more than CUT (drawn again past it).
     */
    public static double[] rear(double x, double y, double z, DoubleSupplier gaussian) {
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-12) return new double[] {x, y, z};
        double fx = x / len, fy = y / len, fz = z / len;
        // two unit vectors across the heading: one level (sideways), one up from it
        double sx = fz, sy = 0, sz = -fx;
        double sl = Math.sqrt(sx * sx + sz * sz);
        if (sl < 1e-9) {
            sx = 1;
            sz = 0;
            sl = 1;
        }
        sx /= sl;
        sz /= sl;
        double ux = fy * sz - fz * sy, uy = fz * sx - fx * sz, uz = fx * sy - fy * sx;
        double a, b;
        int tries = 0;
        do {
            a = gaussian.getAsDouble() * SIGMA;
            b = gaussian.getAsDouble() * SIGMA;
        } while (a * a + b * b > CUT * CUT && ++tries < 16);
        if (a * a + b * b > CUT * CUT) {
            double k = CUT / Math.sqrt(a * a + b * b);
            a *= k;
            b *= k;
        }
        // small angles: the heading plus the offsets across it, made unit again
        double rx = fx + a * sx + b * ux, ry = fy + a * sy + b * uy, rz = fz + a * sz + b * uz;
        double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
        return new double[] {rx / rl * len, ry / rl * len, rz / rl * len};
    }

    /** the angle between two headings, radians */
    public static double angle(double[] p, double[] q) {
        double d = p[0] * q[0] + p[1] * q[1] + p[2] * q[2];
        double l = Math.sqrt((p[0] * p[0] + p[1] * p[1] + p[2] * p[2]) * (q[0] * q[0] + q[1] * q[1] + q[2] * q[2]));
        return Math.acos(Math.clamp(d / l, -1.0, 1.0));
    }
}
