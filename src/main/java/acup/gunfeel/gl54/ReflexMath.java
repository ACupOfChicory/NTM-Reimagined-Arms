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
}
