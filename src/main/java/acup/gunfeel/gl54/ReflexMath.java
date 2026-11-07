package acup.gunfeel.gl54;

import java.util.ArrayList;
import java.util.List;

// The flat drawing on the reflex sight's pane (ReflexDraw): everything in the pane's own metric frame, a across the pane and b
// up it, in model units, so a shape is clipped to the pane's outline and laid on the model without the 3D in the way.
final class ReflexMath {
    private ReflexMath() {
    }

    // `poly` cut to the convex `window` (either winding), Sutherland-Hodgman; empty when nothing is left
    static List<double[]> clip(List<double[]> poly, double[][] window) {
        double area = 0;
        for (int i = 0; i < window.length; i++) {
            double[] p = window[i], q = window[(i + 1) % window.length];
            area += p[0] * q[1] - q[0] * p[1];
        }
        double sign = area >= 0 ? 1 : -1;
        List<double[]> out = poly;
        for (int i = 0; i < window.length && !out.isEmpty(); i++) {
            double[] p = window[i], q = window[(i + 1) % window.length];
            List<double[]> in = out;
            out = new ArrayList<>();
            for (int k = 0; k < in.size(); k++) {
                double[] s = in.get(k), e = in.get((k + 1) % in.size());
                double ds = side(p, q, s) * sign, de = side(p, q, e) * sign;
                if (de >= 0) {
                    if (ds < 0) out.add(cross(s, e, ds, de));
                    out.add(e);
                } else if (ds >= 0) {
                    out.add(cross(s, e, ds, de));
                }
            }
        }
        return out.size() < 3 ? List.of() : out;
    }

    private static double side(double[] p, double[] q, double[] s) {
        return (q[0] - p[0]) * (s[1] - p[1]) - (q[1] - p[1]) * (s[0] - p[0]);
    }

    private static double[] cross(double[] s, double[] e, double ds, double de) {
        double t = ds / (ds - de);
        return new double[] {s[0] + (e[0] - s[0]) * t, s[1] + (e[1] - s[1]) * t};
    }

    static List<double[]> disk(double cx, double cy, double r, int n) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n;
            out.add(new double[] {cx + r * Math.cos(t), cy + r * Math.sin(t)});
        }
        return out;
    }

    static List<double[]> rect(double x0, double y0, double x1, double y1) {
        return List.of(new double[] {x0, y0}, new double[] {x1, y0}, new double[] {x1, y1}, new double[] {x0, y1});
    }

    // the 3 x 5 pixel font of the range: rows from the top, '#' a lit pixel
    private static final String[][] GLYPHS = {
            {"###", "#.#", "#.#", "#.#", "###"}, {".#.", "##.", ".#.", ".#.", "###"}, {"###", "..#", "###", "#..", "###"},
            {"###", "..#", "###", "..#", "###"}, {"#.#", "#.#", "###", "..#", "..#"}, {"###", "#..", "###", "..#", "###"},
            {"###", "#..", "###", "#.#", "###"}, {"###", "..#", ".#.", ".#.", ".#."}, {"###", "#.#", "###", "#.#", "###"},
            {"###", "#.#", "###", "..#", "###"}};
    private static final String[] DASH = {"...", "...", "###", "...", "..."};
    private static final String[] PLUS = {"...", ".#.", "###", ".#.", "..."};
    static final int GLYPH_W = 3, GLYPH_H = 5, GLYPH_GAP = 1;

    // the lit pixels of the text (digits and '-') as unit squares: {column, row from the top}
    static List<int[]> pixels(String text) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String[] g = c == '-' ? DASH : c == '+' ? PLUS : c >= '0' && c <= '9' ? GLYPHS[c - '0'] : null;
            if (g == null) continue;
            for (int row = 0; row < GLYPH_H; row++)
                for (int col = 0; col < GLYPH_W; col++)
                    if (g[row].charAt(col) == '#') out.add(new int[] {i * (GLYPH_W + GLYPH_GAP) + col, row});
        }
        return out;
    }

    static int textWidth(String text) {
        return text.isEmpty() ? 0 : text.length() * (GLYPH_W + GLYPH_GAP) - GLYPH_GAP;
    }

    // the range as the pane shows it: whole blocks and the shift of the burst ("40+2", "40-1"; none when it is 0), "---" when
    // there is no range
    static String rangeText(float range, int offset) {
        if (Float.isNaN(range)) return "---";
        return Math.round(range) + (offset > 0 ? "+" + offset : offset < 0 ? "-" + -offset : "");
    }

    // A slope in the world's picture (a direction's sideways or upward part over its depth) as the hand's picture has it. The hand is
    // drawn with its own field of view, the world with the aim's zoomed one: the same point of the screen is at the slope
    // tan(world / 2) over the half height in the one and tan(hud / 2) in the other, so the hand's slope is the world's times
    // tan(hud / 2) / tan(world / 2).
    static double handSlope(double worldSlope, double hudFov, double worldFov) {
        return worldSlope * Math.tan(Math.toRadians(hudFov * 0.5)) / Math.tan(Math.toRadians(worldFov * 0.5));
    }

    // whether a point is inside the convex outline (either winding), edges included
    static boolean inside(double[][] window, double a, double b) {
        double area = 0;
        for (int i = 0; i < window.length; i++) {
            double[] p = window[i], q = window[(i + 1) % window.length];
            area += p[0] * q[1] - q[0] * p[1];
        }
        double sign = area >= 0 ? 1 : -1;
        for (int i = 0; i < window.length; i++) {
            double[] p = window[i], q = window[(i + 1) % window.length];
            if (((q[0] - p[0]) * (b - p[1]) - (q[1] - p[1]) * (a - p[0])) * sign < -1e-12) return false;
        }
        return true;
    }

    // where the half line from (a, b) toward (da, db) leaves the convex outline; (a, b) is inside it. Null for no direction.
    static double[] exitPoint(double[][] window, double a, double b, double da, double db) {
        if (Math.hypot(da, db) < 1e-12) return null;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < window.length; i++) {
            double[] p = window[i], q = window[(i + 1) % window.length];
            double ex = q[0] - p[0], ey = q[1] - p[1];
            double den = da * ey - db * ex;
            if (Math.abs(den) < 1e-12) continue;
            // a + t da = p + u e
            double t = ((p[0] - a) * ey - (p[1] - b) * ex) / den;
            double u = ((p[0] - a) * db - (p[1] - b) * da) / den;
            if (t >= 0 && u >= -1e-9 && u <= 1 + 1e-9 && t < best) best = t;
        }
        return best == Double.MAX_VALUE ? null : new double[] {a + best * da, b + best * db};
    }

    // a small arrow whose tip is at (tipA, tipB), pointing along the unit direction (da, db): `length` long, `half` wide at the base
    static List<double[]> arrow(double tipA, double tipB, double da, double db, double length, double half) {
        double n = Math.hypot(da, db);
        double ua = da / n, ub = db / n;
        double baseA = tipA - ua * length, baseB = tipB - ub * length;
        return List.of(new double[] {tipA, tipB}, new double[] {baseA - ub * half, baseB + ua * half}, new double[] {baseA + ub * half, baseB - ua * half});
    }

    // the font's strokes: lines between the middles of lit pixels that touch (side by side, or corner to corner where no side
    // pixel joins them), and each lit pixel as a point; {column, row, column, row}, a pixel's middle at +0.5
    static List<double[]> strokes(String text) {
        java.util.Set<Long> lit = new java.util.HashSet<>();
        List<int[]> px = pixels(text);
        for (int[] p : px) lit.add(key(p[0], p[1]));
        List<double[]> out = new ArrayList<>();
        for (int[] p : px) {
            int c = p[0], r = p[1];
            out.add(new double[] {c + 0.5, r + 0.5, c + 0.5, r + 0.5});
            if (lit.contains(key(c + 1, r))) out.add(new double[] {c + 0.5, r + 0.5, c + 1.5, r + 0.5});
            if (lit.contains(key(c, r + 1))) out.add(new double[] {c + 0.5, r + 0.5, c + 0.5, r + 1.5});
            for (int dc : new int[] {-1, 1})
                if (lit.contains(key(c + dc, r + 1)) && !lit.contains(key(c + dc, r)) && !lit.contains(key(c, r + 1)))
                    out.add(new double[] {c + 0.5, r + 0.5, c + dc + 0.5, r + 1.5});
        }
        return out;
    }

    private static long key(int c, int r) {
        return ((long) c << 32) ^ (r & 0xFFFFFFFFL);
    }

    // the distance from (a, b) to the segment {a0, b0, a1, b1}
    static double segmentDistance(double a, double b, double[] s) {
        double da = s[2] - s[0], db = s[3] - s[1], l = da * da + db * db;
        double t = l < 1e-24 ? 0 : Math.max(0, Math.min(1, ((a - s[0]) * da + (b - s[1]) * db) / l));
        return Math.hypot(a - s[0] - t * da, b - s[1] - t * db);
    }

    // How a mark of the sight shines, in screen pixels: an opaque core `half` wide on each side of its line, its edge smoothed
    // over `edge` px (0: a hard edge), a glow beyond it that falls as a Gaussian (`peak` 0-255 at the core's edge, `sigma` px),
    // and for the dot a white-hot middle (`hot` added to green and blue, gone at `hotRadius` px from the middle).
    record Light(double half, double edge, double peak, double sigma, double hot, double hotRadius) {
        // how far past the core the glow is still above one step of 255
        double reach() {
            return sigma * Math.sqrt(2 * Math.log(Math.max(peak, 1.0001)));
        }
    }

    // The colour at `line` px from a mark's line (`middle` px from the dot's middle, for the hot spot), premultiplied by its
    // alpha for the blend `src + dst * (1 - alpha)`: the core is the LED's red and covers what is behind (alpha 1, its edge
    // smoothed over a pixel), the glow only adds red light (alpha 0), so it never darkens the view. 0 for nothing.
    static int light(Light l, double line, double middle, int red, int green, int blue) {
        double d = line - l.half();
        double cover = l.edge() <= 0 ? (d <= 0 ? 1 : 0) : Math.max(0, Math.min(1, 0.5 - d / l.edge()));
        double glow = d <= 0 ? l.peak() : l.peak() * Math.exp(-d * d / (2 * l.sigma() * l.sigma()));
        double k = cover + (1 - cover) * glow / 255.0;
        double hot = l.hotRadius() > 0 ? l.hot() * Math.max(0, 1 - (middle / l.hotRadius()) * (middle / l.hotRadius())) : 0;
        int r = (int) Math.round(Math.min(255, red * k)), g = (int) Math.round(Math.min(255, green * k + hot)),
                b = (int) Math.round(Math.min(255, blue * k + hot)), a = (int) Math.round(255 * cover);
        if (a == 0 && r == 0 && g == 0 && b == 0) return 0;
        return a << 24 | r << 16 | g << 8 | b;
    }
}
