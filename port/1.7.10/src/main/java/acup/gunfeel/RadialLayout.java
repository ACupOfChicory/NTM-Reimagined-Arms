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
