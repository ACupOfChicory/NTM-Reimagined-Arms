package acup.gunfeel;

// The radial ammo menu's numbers (RadialMenu draws it): its rings and reach, the unseen pointer the mouse turns out of
// the centre and the sector it points at, and each frame's easing — the hovered sector sliding out, the sectors coming
// in clockwise, the menu opening. Times in nanoseconds of the caller's clock (GunClock), passed in.
final class RadialDial {
    /** at most this many sectors */
    static final int MAX = 16;
    static final float R_IN = 23, R_OUT = 62, DEAD = 10, REACH = 50, TAU = (float) (Math.PI * 2);

    private static double px, py;
    private static long openedAt, lastFrame;
    /** how far each sector is slid out (0..1), eased toward the hovered one */
    static float[] h = new float[0];
    /** how much any sector is hovered (0..1), eased */
    static float any;

    private RadialDial() {
    }

    /** opened with n sectors at now: the pointer in the centre, nothing slid out */
    static void open(int n, long now) {
        px = py = 0;
        h = new float[n];
        any = 0;
        openedAt = lastFrame = now;
    }

    /** mouse movement while open */
    static void move(double dx, double dy) {
        px += dx * 0.4;
        py += dy * 0.4;
        double l = Math.sqrt(px * px + py * py);
        if (l > REACH) {
            px *= REACH / l;
            py *= REACH / l;
        }
    }

    /** the sector of n the pointer is on, clockwise from the top; -1 in the dead centre */
    static int hovered(int n) {
        if (px * px + py * py < DEAD * DEAD) return -1;
        double a = Math.atan2(px, -py);
        double step = Math.PI * 2 / n;
        return Math.floorMod((int) Math.floor((a + step / 2) / step), n);
    }

    /** the frame's step in ms since the last (at most 50), and now the last */
    static float frame(long now) {
        float dt = Math.min(50, (now - lastFrame) / 1e6F);
        lastFrame = now;
        return dt;
    }

    /** ms since it opened */
    static float since(long now) {
        return (now - openedAt) / 1e6F;
    }

    /** the n sectors' slide and any's eased dt toward the hovered one (-1: none) */
    static void settle(int n, int hover, float dt) {
        float k = 1 - (float) Math.exp(-dt / 50);
        for (int i = 0; i < n; i++) h[i] += ((i == hover ? 1 : 0) - h[i]) * k;
        any += ((hover >= 0 ? 1 : 0) - any) * k;
    }

    /** how far in each of n sectors is, ms after opening: they come in clockwise, 22 ms apart */
    static float[] in(int n, float ms) {
        float[] in = new float[n];
        for (int i = 0; i < n; i++) in[i] = ease((ms - i * 22) / 90);
        return in;
    }

    /** how far open the menu is, ms after opening */
    static float open(float ms) {
        return ease(ms / 110);
    }

    /** the menu's scale, as far open as that */
    static float scale(float open) {
        return 0.88F + 0.12F * open;
    }

    static float ease(float t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    static float sx(float r, float a) {
        return (float) Math.sin(a) * r;
    }

    static float sy(float r, float a) {
        return (float) -Math.cos(a) * r;
    }
}
