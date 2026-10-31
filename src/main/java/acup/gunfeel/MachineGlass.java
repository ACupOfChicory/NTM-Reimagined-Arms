package acup.gunfeel;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

// The clear pixels (alpha below 26) on the model of an NTM machine are glass, not air: a chemical plant's windows.
// A bullet pierces them as glass (Materials.GLASS over GLASS_PATH, whatever bulletPierceMachines says), with glass
// sounds, crumbs and a hole that stays. The client takes them as a second triangle over the machine's own
// (RenderedMesh.Window: over what its renderer draws, and over its body baked into the chunk, sprites
// hbm:block/models/...) and sends them to the server as faces marked glass (ModelSurface.Face.glass). Textures whose
// clear pixels really are empty (grates, nets, cut-outs) are listed in ntmgunfeel-machine-glass.txt; light (a
// triangle clear at every corner, RenderedMesh.Capture.tri) is never glass. Server switch: bulletMachineGlass.
public final class MachineGlass {
    private MachineGlass() {
    }

    public static final String KEY = "bulletMachineGlass";
    public static final String FILE = "/ntmgunfeel-machine-glass.txt";
    // how much glass a bullet goes through in a window: a pixel of a machine's model
    public static final double PATH = 1.0 / 16.0;
    public static final Materials.Verdict VERDICT = new Materials.Verdict(Materials.Material.GLASS, 1F, Materials.Source.RULE, "machine window");

    private static volatile Set<String> empty;

    // the server says machine windows are glass (on unless bulletMachineGlass = false); tests set their own
    static java.util.function.BooleanSupplier setting = () -> ReaimTimes.flag(KEY, true);

    public static boolean on() {
        return setting.getAsBoolean();
    }

    // a texture ("hbm:textures/...png") whose clear pixels are empty after all
    public static boolean empty(String texture) {
        Set<String> e = empty;
        if (e == null) empty = e = read();
        return e.contains(texture);
    }

    static Set<String> read() {
        Set<String> out = new HashSet<>();
        try (var in = MachineGlass.class.getResourceAsStream(FILE)) {
            if (in == null) return out;
            for (String line : new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList()) {
                String t = parse(line);
                if (t != null) out.add(t);
            }
        } catch (Exception e) {
            GunFeel.LOGGER.warn("Could not read {}: every machine's clear pixels count as glass", FILE, e);
        }
        return out;
    }

    // a line of the list: the texture, then " # " and why; blank lines and "#" lines are skipped
    static String parse(String line) {
        String l = line.strip();
        int hash = l.indexOf(" #");
        if (hash >= 0) l = l.substring(0, hash).strip();
        if (l.isEmpty() || l.startsWith("#")) return null;
        return l.split("\\s+")[0];
    }

    // A triangle with texture coordinates uv (u0, v0, u1, v1, u2, v2; the texture repeats out of 0..1) on a w x h
    // texture covers some of a clear pixel. shown: the pixels that show; sum: the clear pixels in the rectangle from
    // (0, 0) to (x, y), x and y not included, (w + 1) x (h + 1).
    static boolean covers(float[] uv, int w, int h, boolean[] shown, int[] sum) {
        double[] x = {uv[0] * (double) w, uv[2] * (double) w, uv[4] * (double) w};
        double[] y = {uv[1] * (double) h, uv[3] * (double) h, uv[5] * (double) h};
        double minX = Math.min(x[0], Math.min(x[1], x[2])), maxX = Math.max(x[0], Math.max(x[1], x[2]));
        double minY = Math.min(y[0], Math.min(y[1], y[2])), maxY = Math.max(y[0], Math.max(y[1], y[2]));
        int x0 = (int) Math.floor(minX), x1 = Math.max(x0, (int) Math.ceil(maxX) - 1);
        int y0 = (int) Math.floor(minY), y1 = Math.max(y0, (int) Math.ceil(maxY) - 1);
        // within the texture once: none clear in the box, none under the triangle
        if (x0 >= 0 && y0 >= 0 && x1 < w && y1 < h) {
            int n = sum[(y1 + 1) * (w + 1) + x1 + 1] - sum[y0 * (w + 1) + x1 + 1] - sum[(y1 + 1) * (w + 1) + x0] + sum[y0 * (w + 1) + x0];
            if (n == 0) return false;
        }
        // a texture repeated many times over: as good as all of it
        if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) > 4L * w * h) {
            for (boolean s : shown) if (!s) return true;
            return false;
        }
        for (int py = y0; py <= y1; py++) for (int px = x0; px <= x1; px++) {
            if (shown[Math.floorMod(py, h) * w + Math.floorMod(px, w)]) continue;
            if (overlaps(x, y, px, py)) return true;
        }
        return false;
    }

    // how far into a pixel a triangle must reach to cover it, in pixels: a triangle whose edge lies on a pixel border
    // (its coordinates a rounding off it) does not take the clear pixel beside it
    static final double INTO = 0.02;

    // the triangle (x, y) and the pixel square [px, px + 1] x [py, py + 1] share some area, more than a sliver INTO
    // deep along its edges (separating axes)
    static boolean overlaps(double[] x, double[] y, int px, int py) {
        if (Math.max(x[0], Math.max(x[1], x[2])) <= px + INTO || Math.min(x[0], Math.min(x[1], x[2])) >= px + 1 - INTO) return false;
        if (Math.max(y[0], Math.max(y[1], y[2])) <= py + INTO || Math.min(y[0], Math.min(y[1], y[2])) >= py + 1 - INTO) return false;
        for (int i = 0; i < 3; i++) {
            int j = (i + 1) % 3, k = (i + 2) % 3;
            double nx = y[j] - y[i], ny = x[i] - x[j];
            if (Math.abs(nx) + Math.abs(ny) < 1e-12) continue;
            double tri = nx * (x[k] - x[i]) + ny * (y[k] - y[i]);
            double s = Math.signum(tri);
            if (s == 0) continue;
            // the square wholly on the side away from the third corner
            double best = Double.NEGATIVE_INFINITY;
            for (int c = 0; c < 4; c++) {
                double cx = px + (c & 1), cy = py + (c >> 1);
                best = Math.max(best, s * (nx * (cx - x[i]) + ny * (cy - y[i])));
            }
            if (best <= INTO * Math.hypot(nx, ny)) return false;
        }
        return true;
    }
}
