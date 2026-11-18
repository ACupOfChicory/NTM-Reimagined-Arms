package acup.gunfeel.hitmarker;

// The hit marker and the damage indicator as the owner chose them (2026-11-18) on the gallery page, its FINAL and
// FINAL_INDICATOR: the same numbers and the same arithmetic, pixel for pixel (HitMarkerLookTest holds the page's own
// output). Coordinates are GUI pixels from the middle of the screen, the edge between its two middle columns (where
// NTM's crosshair, two pixels wide, is centred); time is in game ticks, partial ticks counted.
//  - The marker: four diagonal strokes round the crosshair, white on the body, red and longer on the head. They start
//    wider and close in over `pop` ticks (easing out), stay at full for `hold` ticks, then fade out over `fade`.
//    A kill has no mark of its own: the hit's mark and the kill's sound.
//  - The indicator: a 2-pixel arc on a ring round the crosshair, towards where the damage came from (0° ahead,
//    clockwise), its ends thinning; at full for INDICATOR_HOLD ticks, then fading over INDICATOR_FADE.
public final class HitMarkerLook {
    private HitMarkerLook() {
    }

    /** one look of the marker: its timing, its colour (RGB) and its strokes (from gapFrom to gapTo over pop ticks) */
    public record Look(int hold, int fade, int rgb, int gapFrom, int gapTo, int len, int pop) {
        public double end() {
            return hold + fade;
        }
    }

    public static final Look BODY = new Look(3, 4, 0xFFFFFF, 6, 4, 4, 2);
    public static final Look HEAD = new Look(4, 5, 0xFF3A30, 7, 4, 5, 2);

    static final double INDICATOR_IN = 24, INDICATOR_OUT = 26, INDICATOR_HALF = 42, INDICATOR_TAPER = 0.45, INDICATOR_END = 0.15,
            INDICATOR_ALPHA = 0.8;
    static final int INDICATOR_RGB = 0xF04538, INDICATOR_HOLD = 11, INDICATOR_FADE = 22;

    /** a pixel at (x, y) from the middle, in ARGB */
    @FunctionalInterface
    public interface Pixel {
        void put(int x, int y, int argb);
    }

    /** at full until hold, then down to 0 over fade; 0 before the start */
    static double strength(int hold, int fade, double t) {
        if (t < 0) return 0;
        if (t < hold) return 1;
        double f = fade > 0 ? 1 - (t - hold) / fade : 0;
        return f > 0 ? f : 0;
    }

    /** the marker {@code t} ticks after the hit; false once it is gone */
    public static boolean marker(Look l, double t, Pixel out) {
        double a = strength(l.hold, l.fade, t);
        if (a <= 0) return false;
        double k = Math.min(1, Math.max(0, t / Math.max(1, l.pop)));
        double e = 1 - (1 - k) * (1 - k);
        long gap = Math.round(l.gapFrom + (l.gapTo - l.gapFrom) * e);
        int argb = (int) Math.floor(a * 255) << 24 | l.rgb;
        for (long d = gap; d < gap + l.len; d++) {
            int i = (int) d;
            // the quarter right and down of the middle, and its mirrors
            out.put(i, i, argb);
            out.put(-i - 1, i, argb);
            out.put(i, -i - 1, argb);
            out.put(-i - 1, -i - 1, argb);
        }
        return true;
    }

    /** the indicator {@code t} ticks after the damage, the damage from {@code dir} degrees; false once it is gone */
    public static boolean indicator(double dir, double t, Pixel out) {
        double a0 = strength(INDICATOR_HOLD, INDICATOR_FADE, t);
        if (a0 <= 0) return false;
        int R = (int) INDICATOR_OUT + 1;
        for (int y = -R; y < R; y++) {
            for (int x = -R; x < R; x++) {
                double px = x + 0.5, py = y + 0.5, r = Math.sqrt(px * px + py * py);
                if (r < INDICATOR_IN || r >= INDICATOR_OUT) continue;
                // StrictMath: fdlibm's atan2, the one the page's JavaScript has
                double ang = StrictMath.atan2(px, -py) * 180 / Math.PI;
                double diff = Math.abs((((ang - dir) % 360) + 540) % 360 - 180);
                if (diff > INDICATOR_HALF) continue;
                double f = 1, s = INDICATOR_HALF * INDICATOR_TAPER;
                if (diff > s) f = 1 - (1 - INDICATOR_END) * (diff - s) / (INDICATOR_HALF - s);
                int A = (int) Math.floor(a0 * INDICATOR_ALPHA * f * 255);
                if (A > 0) out.put(x, y, A << 24 | INDICATOR_RGB);
            }
        }
        return true;
    }

    /**
     * The direction of (dx, dz) away for a player looking along {@code yaw} (the game's: 0 south, +90 west), degrees:
     * 0 ahead, clockwise, +90 on the right
     */
    public static double bearing(double yaw, double dx, double dz) {
        double r = Math.toRadians(yaw);
        double ahead = -Math.sin(r) * dx + Math.cos(r) * dz;
        double right = -Math.cos(r) * dx - Math.sin(r) * dz;
        return Math.toDegrees(Math.atan2(right, ahead));
    }

    /** ticks an indicator lasts */
    public static int indicatorTicks() {
        return INDICATOR_HOLD + INDICATOR_FADE;
    }
}
