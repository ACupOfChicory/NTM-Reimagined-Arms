package acup.gunfeel.ammo;

import acup.gunfeel.CoreServices;
import acup.gunfeel.host.DuplexShots;

/** Pair the Duplex bullets of one shot, keeping the front bullet opaque. */
public final class DuplexBase {
    private DuplexBase() {}

    private static final class ShotsPort {
        static final DuplexShots SHOTS = CoreServices.load(DuplexShots.class, DuplexShots.MISSING);
    }

    /** the front bullet of the shot under way on this thread (MixinDuplexShot: the next Duplex bullet is its rear one) */
    private static final ThreadLocal<Object> FRONT = new ThreadLocal<>();

    /** a shot begins: no front bullet yet */
    public static void shotStart() {
        FRONT.remove();
    }

    /**
     * a bullet of the shot about to be added to the world: a Duplex one either becomes the front one, or is the rear one
     * of the front before it and is set behind it, off its heading (DuplexRule.rear)
     */
    public static void place(Object e) {
        place(e, ShotsPort.SHOTS);
    }

    static void place(Object b, DuplexShots shots) {
        if (!shots.duplex(b)) return;
        Object front = FRONT.get();
        if (front == null || shots.config(front) != shots.config(b) || shots.thrower(front) != shots.thrower(b)) {
            FRONT.set(b);
            return;
        }
        FRONT.remove();
        double x = shots.mx(front), y = shots.my(front), z = shots.mz(front);
        double[] r = DuplexRule.rear(x, y, z, () -> shots.gaussian(b));
        double hyp = Math.sqrt(r[0] * r[0] + r[2] * r[2]);
        float yaw = (float) Math.toDegrees(Math.atan2(r[0], r[2])), pitch = (float) Math.toDegrees(Math.atan2(r[1], hyp));
        double len = Math.sqrt(x * x + y * y + z * z);
        boolean shortMotion = len < 1e-9;
        double ax = shots.px(front), ay = shots.py(front), az = shots.pz(front);
        if (!shortMotion) {
            double scale = DuplexRule.BEHIND / len;
            double sx = x * scale, sy = y * scale, sz = z * scale;
            double nx = -sx, ny = -sy, nz = -sz;
            ax += nx;
            ay += ny;
            az += nz;
        }
        shots.write(b, r[0], r[1], r[2], yaw, pitch, ax, ay, az);
    }
}
