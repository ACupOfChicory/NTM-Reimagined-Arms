package acup.gunfeel.ai.client;

/**
 * An AI mob's trunk pushed by a hit: each hit a kick to a critically damped spring, the trunk turned about the neck
 * (its pivot: the head stays where the server's eye is) and the hips with the roots of the legs moved under it. One
 * for each mob; BotPoser adds {@link #sample} over the pose (the flinch clips too).
 *
 * <p>A hit's push peaks {@link #PEAK} s after it and is down to a fifth by 0.5 s, to 3 % by 0.75 s (dropped at 1.2 s,
 * a thousandth of it left). People pushed at the shoulder (DeCIMo, 21 people, the pushes they took without a step:
 * 15, 24, 30 N s) lean 5, 9 and 15 degrees, most at 0.6 to 0.8 s (the push itself lasts 0.74 s) and back to a fifth
 * by 1.4 to 1.7 s, their head going 11 to 24 cm: a bullet's push is far shorter, and the bot's head may not leave the
 * eye, so the trunk turns about the neck, at most {@link #MOST} degrees, the hips going 2 px at that.
 *
 * <p>The turn is to be added to the trunk's own (its angles summed, as ModelPart turns it). out[3..5] is where the
 * hips go from a trunk upright; with the pose's trunk turned (bent 20 degrees, twisted 30) that is up to 1 px off
 * where the summed angles put the body's bottom: then move the hips by {@link #hips} of the summed angles less
 * {@link #hips} of the pose's own.
 */
public final class BotImpulse {
    /** s from a hit to its push's most */
    public static final float PEAK = 0.12F;
    /** the trunk's turn at most, degrees, of hits together however many */
    public static final float MOST = 10F;
    /** the twist about the vertical at most, degrees */
    public static final float TWIST = 8F;
    /** a hit of strength 1 alone turns the trunk this share of MOST (and of TWIST, at the side of the chest, 4 px out):
     *  up to it the turns add as they are, over it they go on to MOST softly, so a hit alone keeps its shape */
    static final float KNEE = 0.8F;
    /** px from the neck to the hips (BotModel's body) */
    static final float BODY = 12F;
    /** hits kept at once: 20 a second over GONE PEAKs (1.2 s) are 24; past that the oldest gives way */
    static final int KEEP = 32;
    /** a hit is dropped this many PEAKs after it, a thousandth of it left */
    static final float GONE = 10F;

    // each hit: when, the turn it gives at its most (rad, xRot, yRot, zRot)
    private final float[] at = new float[KEEP], x = new float[KEEP], y = new float[KEEP], z = new float[KEEP];
    private int n;

    /**
     * A hit.
     *
     * @param dirX     where the bullet (or the hit's push) went, the model's axes (X its left, Y down, Z its back);
     *                 any length; all nought (or not a number): from the front (to its back); only its part over the
     *                 floor leans the trunk, a push straight down or up none
     * @param atX      where it hit, px from the neck in the model's axes; NaN: unknown (no twist). Its height (atY)
     *                 is not used: a hit at the legs twists the trunk as one at the chest
     * @param strength 0 .. 1, the hit's share of the health it takes (BotAnimator: twice the damage's share)
     * @param nowSec   the mob's own time, s
     */
    public void hit(float dirX, float dirY, float dirZ, float atX, float atY, float atZ, float strength, float nowSec) {
        float s = Math.max(0F, Math.min(1F, strength));
        if (!(s > 0F) || !Float.isFinite(nowSec)) return;
        if (!Float.isFinite(dirX) || !Float.isFinite(dirY) || !Float.isFinite(dirZ)) dirX = dirY = dirZ = 0F;
        // over the floor only: the push down or up is not a lean. Scaled to the largest so none of it overflows
        double big = Math.max(Math.abs(dirX), Math.max(Math.abs(dirY), Math.abs(dirZ)));
        double fx = big > 0 ? dirX / big : 0, fy = big > 0 ? dirY / big : 0, fz = big > 0 ? dirZ / big : 1;
        double h = Math.sqrt(fx * fx + fz * fz), all = Math.sqrt(fx * fx + fy * fy + fz * fz);
        float ux = h > 1e-6 ? (float) (fx / h) : 0F, uz = h > 1e-6 ? (float) (fz / h) : 0F;
        float a = (float) Math.toRadians(MOST) * KNEE * s * (float) (h / all);
        // the chest goes with the push and the hips stay behind: under the neck they go the other way. The body's
        // bottom (0, BODY, 0) turned by xRot goes to +Z by BODY sin(xRot), by zRot to -X by BODY sin(zRot)
        float xr = -uz * a, zr = ux * a;
        float yr = 0F;
        if (Float.isFinite(atX) && Float.isFinite(atZ)) {
            // a hit off the middle twists the trunk about the vertical, the way its push turns it about the neck's
            // line: the left side (+X) pushed back (+Z) goes back, yRot negative (ModelPart's: z' = -x sin yRot)
            float torque = atZ * ux - atX * uz;
            float t = Math.max(-1F, Math.min(1F, torque / 4F));
            yr = (float) Math.toRadians(TWIST) * KNEE * s * t;
        }
        // the oldest gives way
        int i;
        if (n < KEEP) i = n++;
        else {
            i = 0;
            for (int k = 1; k < KEEP; k++) if (at[k] < at[i]) i = k;
        }
        at[i] = nowSec;
        x[i] = xr;
        y[i] = yr;
        z[i] = zr;
    }

    /** a kick's share at dt s after it: rises from nought to 1 at PEAK, back to a fifth by 4 PEAKs (0.48 s) */
    static float shape(float dt) {
        if (dt < 0F || dt > GONE * PEAK) return 0F;
        float u = dt / PEAK;
        return u * (float) Math.exp(1F - u);
    }

    /**
     * The push at nowSec: out[0..2] the trunk's turn about the neck to add (rad, xRot, yRot, zRot as ModelPart's),
     * out[3..5] how far the hips and the roots of the legs go, px in the model's axes (the head stays). False: none.
     */
    public boolean sample(float nowSec, float[] out) {
        if (!Float.isFinite(nowSec)) {
            java.util.Arrays.fill(out, 0, 6, 0F);
            return false;
        }
        float sx = 0F, sy = 0F, sz = 0F;
        int live = 0;
        for (int i = 0; i < n; i++) {
            float w = shape(nowSec - at[i]);
            if (nowSec - at[i] > GONE * PEAK) continue;
            live++;
            sx += w * x[i];
            sy += w * y[i];
            sz += w * z[i];
        }
        // the gone ones out
        if (live < n) {
            int k = 0;
            for (int i = 0; i < n; i++) {
                if (nowSec - at[i] > GONE * PEAK) continue;
                at[k] = at[i];
                x[k] = x[i];
                y[k] = y[i];
                z[k] = z[i];
                k++;
            }
            n = k;
        }
        // hits together: the lean no further than MOST, the twist than TWIST
        float lean = (float) Math.sqrt(sx * sx + sz * sz);
        if (lean > 1e-9F) {
            float to = soft(lean, (float) Math.toRadians(MOST));
            sx *= to / lean;
            sz *= to / lean;
        }
        sy = Math.copySign(soft(Math.abs(sy), (float) Math.toRadians(TWIST)), sy);
        out[0] = sx;
        out[1] = sy;
        out[2] = sz;
        hips(sx, sy, sz, out);
        boolean any = sx != 0F || sy != 0F || sz != 0F;
        if (!any) java.util.Arrays.fill(out, 0, 6, 0F);
        return any;
    }

    /** v as it is up to KNEE of most, past it on to most softly (its slope going on smooth) */
    static float soft(float v, float most) {
        float k = KNEE * most;
        if (v <= k) return v;
        return k + (most - k) * (float) Math.tanh((v - k) / (most - k));
    }

    /** where the body's bottom (0, BODY, 0) goes turned by Rz Ry Rx (ModelPart's order), less where it was: out[3..5] */
    public static void hips(float xr, float yr, float zr, float[] out) {
        double cx = Math.cos(xr), sx = Math.sin(xr), cy = Math.cos(yr), sy = Math.sin(yr), cz = Math.cos(zr), sz = Math.sin(zr);
        // Rx (0, B, 0) = (0, B cx, B sx); then Ry; then Rz
        double px = 0, py = BODY * cx, pz = BODY * sx;
        double qx = cy * px + sy * pz, qy = py, qz = -sy * px + cy * pz;
        double rx = cz * qx - sz * qy, ry = sz * qx + cz * qy;
        out[3] = (float) rx;
        out[4] = (float) (ry - BODY);
        out[5] = (float) qz;
    }
}
