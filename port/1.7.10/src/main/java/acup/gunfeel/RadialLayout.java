package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

// port: new for 1.7.10. The game-free half of 26.2's RadialMenu.java (0762086): which ammo goes in, the pointer and the
// sector under it, the easing of the sectors and the split of an ammo's name, the same numbers as 26.2, so the tests
// check them without a game (RadialLayoutTest). RadialMenu draws with them.
final class RadialLayout {
    static final int MAX = 16;
    static final float R_IN = 23, R_OUT = 62, DEAD = 10, REACH = 50, TAU = (float) (Math.PI * 2);
    // the mouse moves the pointer by this much of its pixels
    static final double MOUSE = 0.4;

    private RadialLayout() {
    }

    // the choices: what the magazine takes and the player has (count != 0), each once, at most MAX, in its order
    static <T> List<T> choices(List<T> accepted, ToIntFunction<T> count) {
        List<T> out = new ArrayList<T>();
        for (T c : accepted) {
            if (out.size() < MAX && !out.contains(c) && count.applyAsInt(c) != 0) out.add(c);
        }
        return out;
    }

    // the pointer {px, py} after a mouse move of dx, dy pixels (y down, as 26.2's GLFW), kept within REACH
    static void move(double[] p, double dx, double dy) {
        p[0] += dx * MOUSE;
        p[1] += dy * MOUSE;
        double l = Math.sqrt(p[0] * p[0] + p[1] * p[1]);
        if (l > REACH) {
            p[0] *= REACH / l;
            p[1] *= REACH / l;
        }
    }

    // the sector under the pointer of n, clockwise from the top; -1 in the dead middle
    static int hovered(double px, double py, int n) {
        if (n <= 0 || px * px + py * py < DEAD * DEAD) return -1;
        double a = Math.atan2(px, -py);
        double step = Math.PI * 2 / n;
        return Math.floorMod((int) Math.floor((a + step / 2) / step), n);
    }

    // How far the menu reaches from its centre, in GUI pixels: up to the hover's outer marks (a petal 6 + 2 out of
    // R_OUT, the petals' triangle at R_OUT + 12 and 3 more), down to the last line of the name and the numbers under
    // it (caption at R_OUT + 14 at most, the name's two lines, three lines of numbers from 24 under it, 9 high and 1 of
    // shadow); sideways the ring and its marks. Fixed, so the menu does not move as the hover changes
    static final float TOP = R_OUT + 16, BOTTOM = R_OUT + 14 + 24 + 20 + 10, SIDE = R_OUT + 16, MARGIN = 4;

    // Where the menu goes on a screen of w x h GUI pixels: {centre x, centre y, scale}. At the middle when it fits;
    // else moved up or down as little as keeps it on the screen (854 x 480 at GUI scale 2, 240 high: the numbers
    // went off the bottom); scaled down only when the screen is lower or narrower than the menu itself
    static float[] fit(float w, float h) {
        float scale = Math.min(1, Math.min((h - 2 * MARGIN) / (TOP + BOTTOM), (w - 2 * MARGIN) / (2 * SIDE)));
        float lo = MARGIN + TOP * scale, hi = h - MARGIN - BOTTOM * scale;
        float cy = lo > hi ? (lo + hi) / 2 : Math.max(lo, Math.min(hi, h / 2));
        return new float[] {w / 2, cy, scale};
    }

    static float ease(float t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    // how far sector i has come in, ms after opening: they come in clockwise
    static float in(float ms, int i) {
        return ease((ms - i * 22) / 90);
    }

    // the whole menu, ms after opening
    static float open(float ms) {
        return ease(ms / 110);
    }

    // the hover glow h toward its target after dt ms
    static float follow(float h, float target, float dt) {
        float k = 1 - (float) Math.exp(-dt / 50);
        return h + (target - h) * k;
    }

    static float sx(float r, float a) {
        return (float) Math.sin(a) * r;
    }

    static float sy(float r, float a) {
        return (float) -Math.cos(a) * r;
    }

    // an ammo's name as the menu writes it: "Патрон 12 калибра (Пуля)" -> {"Патрон 12 калибра", "Пуля"}; a name
    // without the brackets -> {null, name}
    static String[] caption(String name) {
        int p = name.lastIndexOf(" ("), q = name.lastIndexOf(')');
        return p > 0 && q > p ? new String[] {name.substring(0, p), name.substring(p + 2, q)} : new String[] {null, name};
    }
}
