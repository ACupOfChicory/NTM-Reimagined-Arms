package acup.gunfeel.ammo;

/**
 * The flame of Mister Topaz and of the Fritz turret falls as the hand flamer's does (the owner, 2026-11-19: "топаз и фриц
 * дай гравитацию, дизель не надо трогать"). NTM gave them no gravity: a straight beam, 60 and 100 blocks.
 *
 * How NTM flies a bullet (EntityThrowableNT): a heading of length 1, a tick the bullet moves heading × the config's
 * velocity, then the heading loses the config's gravity. The drop a tick² is so velocity × gravity: the hand flamer's
 * 1 × 0.02. A faster jet with the same 0.02 on its heading would fall faster than the hand flamer's flame; here the flame
 * falls the same (FALL) and the jet is faster (SPEED), so it reaches further, a heavy flamer's.
 */
public final class FlamerArc {
    /** the jet's speed, blocks a tick, Topaz's and the Fritz's (NTM's 1, the hand flamer's too) */
    public static final float SPEED = 1.3F;
    /** the drop a tick², blocks: the hand flamer's (velocity 1 × gravity 0.02) */
    public static final double FALL = 0.02;
    /** the config's gravity (NTM takes it off the heading, a tick) */
    public static final double GRAVITY = FALL / SPEED;
    /** Topaz's life, ticks: the whole arc even straight up (2 × SPEED / FALL = 130), NTM's 60 ended it mid-air */
    public static final int TOPAZ_LIFE = 140;
    /** the Fritz's, the hand flamer's: diesel 100, balefire 200 (a 45° arc of the turret ends in ~95) */
    public static final int FRITZ_LIFE = 100, FRITZ_BALEFIRE_LIFE = 200;

    private static final double LOW = Math.toRadians(-80), HIGH = Math.toRadians(80);

    private FlamerArc() {
    }

    /**
     * The barrel's pitch, radians (up positive), a bullet of this speed and drop leaves the barrel's tip with to get
     * from the pivot to (x ahead, y up): the low arc. The tip is barrel blocks out along the barrel, as NTM's turret
     * fires (barrelTip). A target out of reach: the pitch getting highest at x. Too near to the pivot (inside the
     * barrel and a block): the straight line, NTM's.
     */
    public static double pitch(double x, double y, double speed, double fall, double barrel) {
        if (x < barrel + 1) return Math.atan2(y, x);
        double a = LOW, b = HIGH;
        // the height at x rises with the pitch to the top's and falls past it
        for (int i = 0; i < 60; i++) {
            double m1 = a + (b - a) / 3, m2 = b - (b - a) / 3;
            if (height(m1, x, speed, fall, barrel) < height(m2, x, speed, fall, barrel)) a = m1;
            else b = m2;
        }
        double top = (a + b) / 2;
        if (height(top, x, speed, fall, barrel) <= y) return top;
        a = LOW;
        b = top;
        for (int i = 0; i < 60; i++) {
            double m = (a + b) / 2;
            if (height(m, x, speed, fall, barrel) < y) a = m;
            else b = m;
        }
        return (a + b) / 2;
    }

    /**
     * The height over the pivot a bullet fired at this pitch has x ahead of the pivot: its n ticks past the tip are
     * n × speed × cos ahead and n × speed × sin − fall × n(n − 1)/2 up (NTM moves it before the heading drops)
     */
    public static double height(double pitch, double x, double speed, double fall, double barrel) {
        double cos = Math.cos(pitch), sin = Math.sin(pitch);
        double n = (x - barrel * cos) / (speed * cos);
        return barrel * sin + speed * sin * n - fall * n * (n - 1) / 2;
    }

    /**
     * Where a bullet fired from h blocks over flat ground at deg up lands, blocks ahead, as NTM flies it: speed and the
     * config's gravity, life ticks. NaN when its life ends in the air.
     */
    public static double range(double speed, double gravity, int life, double deg, double h) {
        double hx = Math.cos(Math.toRadians(deg)), hy = Math.sin(Math.toRadians(deg)), x = 0, y = h;
        for (int t = 0; t < life; t++) {
            double nx = x + hx * speed, ny = y + hy * speed;
            if (ny < 0) return x + (nx - x) * y / (y - ny);
            x = nx;
            y = ny;
            hy -= gravity;
        }
        return Double.NaN;
    }
}
