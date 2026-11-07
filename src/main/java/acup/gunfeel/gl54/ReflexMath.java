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

    // a ring as quads around the arc: n pieces between the radii
    static List<List<double[]>> ring(double cx, double cy, double inner, double outer, int n) {
        List<List<double[]>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double t0 = 2 * Math.PI * i / n, t1 = 2 * Math.PI * (i + 1) / n;
            out.add(List.of(new double[] {cx + inner * Math.cos(t0), cy + inner * Math.sin(t0)}, new double[] {cx + outer * Math.cos(t0), cy + outer * Math.sin(t0)},
                    new double[] {cx + outer * Math.cos(t1), cy + outer * Math.sin(t1)}, new double[] {cx + inner * Math.cos(t1), cy + inner * Math.sin(t1)}));
        }
        return out;
    }

    // a small arrow whose tip is at (tipA, tipB), pointing along the unit direction (da, db): `length` long, `half` wide at the base
    static List<double[]> arrow(double tipA, double tipB, double da, double db, double length, double half) {
        double n = Math.hypot(da, db);
        double ua = da / n, ub = db / n;
        double baseA = tipA - ua * length, baseB = tipB - ub * length;
        return List.of(new double[] {tipA, tipB}, new double[] {baseA - ub * half, baseB + ua * half}, new double[] {baseA + ub * half, baseB - ua * half});
    }
}
