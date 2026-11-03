package acup.gunfeel;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

// port: 26.2's ElevatorMesh (Java 8, BlockRay.Hit). The cargo elevator's platform and piston as NTM draws them
// (ntmgunfeel-elevator.properties, made from NTM 1.7.10's elevator.obj and elevator.png), for both sides: a bullet
// stops only on a shown pixel of a face turned to it, so it goes through the platform's see-through parts and on to
// what lies under it. They move with the elevator, so they are not in its cells (MachineCells).
public final class ElevatorMesh {
    private ElevatorMesh() {
    }

    // per triangle: corner a, edges e1 e2, uv of a, b, c, outward normal
    private static final class Tri {
        final double[] a, e1, e2, uv, n;

        Tri(double[] a, double[] e1, double[] e2, double[] uv, double[] n) {
            this.a = a;
            this.e1 = e1;
            this.e2 = e2;
            this.uv = uv;
            this.n = n;
        }
    }

    private static Tri[] platform, piston;
    private static boolean[] shown;
    private static int texW, texH;
    private static boolean loaded;

    private static synchronized boolean load() {
        if (loaded) return platform != null;
        loaded = true;
        try (InputStream in = ElevatorMesh.class.getResourceAsStream("/ntmgunfeel-elevator.properties")) {
            if (in == null) return false;
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            String[] t = p.getProperty("texture").trim().split(",");
            texW = Integer.parseInt(t[0]);
            texH = Integer.parseInt(t[1]);
            boolean[] s = new boolean[texW * texH];
            for (int i = 0; i < s.length; i++) s[i] = (Character.digit(t[2].charAt(i >> 2), 16) >> (3 - (i & 3)) & 1) != 0;
            Tri[] pl = tris(p.getProperty("platform")), pi = tris(p.getProperty("piston"));
            shown = s;
            piston = pi;
            platform = pl;
            return true;
        } catch (Exception e) {
            GunFeel.LOGGER.warn("Could not read the cargo elevator mesh: its platform is not met by bullets", e);
            platform = null;
            return false;
        }
    }

    private static Tri[] tris(String src) {
        String[] quads = src.trim().split(";");
        Tri[] out = new Tri[quads.length * 2];
        for (int q = 0; q < quads.length; q++) {
            String[] v = quads[q].split(",");
            double[][] c = new double[4][5];
            for (int k = 0; k < 20; k++) c[k / 5][k % 5] = Double.parseDouble(v[k]);
            out[q * 2] = tri(c[0], c[1], c[2]);
            out[q * 2 + 1] = tri(c[0], c[2], c[3]);
        }
        return out;
    }

    private static Tri tri(double[] a, double[] b, double[] c) {
        double[] e1 = {b[0] - a[0], b[1] - a[1], b[2] - a[2]}, e2 = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
        double[] n = {e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]};
        return new Tri(new double[] {a[0], a[1], a[2]}, e1, e2, new double[] {a[3], a[4], b[3], b[4], c[3], c[4]}, n);
    }

    public static boolean available() {
        return load();
    }

    // The first shown pixel the bullet from (fx, fy, fz) to (tx, ty, tz) meets on the platform or the piston, inside
    // block (x, y, z); null = none there. (cx, cy, cz) is the elevator's core block, `extension` its platform's height
    // above the bottom.
    public static BlockRay.Hit hit(int cx, int cy, int cz, double extension, int x, int y, int z, double fx, double fy, double fz,
            double tx, double ty, double tz) {
        if (!load()) return null;
        double ox = cx + 0.5, oy = cy + extension, oz = cz + 0.5;
        double[] o = {fx - ox, fy - oy, fz - oz}, d = {tx - fx, ty - fy, tz - fz};
        double best = Double.POSITIVE_INFINITY;
        Tri bestTri = null;
        double lo = x - ox, loY = y - oy, loZ = z - oz;
        // the platform, and the piston drawn once per block from the platform down (RenderCargoElevator)
        int pistons = (int) Math.ceil(extension + 1.0);
        for (int copy = -1; copy < pistons; copy++) {
            Tri[] tris = copy < 0 ? platform : piston;
            double dy = copy < 0 ? 0 : -copy;
            for (Tri t : tris) {
                double k = cross(t, o[0], o[1] - dy, o[2], d);
                if (!(k >= 0 && k <= 1 && k < best)) continue;
                double px = o[0] + d[0] * k, py = o[1] + d[1] * k, pz = o[2] + d[2] * k;
                double e = 1e-7;
                if (px < lo - e || px > lo + 1 + e || py < loY - e || py > loY + 1 + e || pz < loZ - e || pz > loZ + 1 + e) continue;
                if (!shownAt(t, o[0], o[1] - dy, o[2], d, k)) continue;
                best = k;
                bestTri = t;
            }
        }
        if (bestTri == null) return null;
        double[] n = bestTri.n;
        return new BlockRay.Hit(x, y, z, face(n), fx + d[0] * best, fy + d[1] * best, fz + d[2] * best, false);
    }

    // the side a normal points to most (26.2: Direction.getApproximateNearest)
    private static int face(double[] n) {
        double ax = Math.abs(n[0]), ay = Math.abs(n[1]), az = Math.abs(n[2]);
        if (ay >= ax && ay >= az) return n[1] < 0 ? BlockRay.DOWN : BlockRay.UP;
        if (ax >= az) return n[0] < 0 ? BlockRay.WEST : BlockRay.EAST;
        return n[2] < 0 ? BlockRay.NORTH : BlockRay.SOUTH;
    }

    // along d from o, where it meets the triangle's front (as a share of d), or NaN
    private static double cross(Tri t, double ox, double oy, double oz, double[] d) {
        double[] n = t.n;
        if (n[0] * d[0] + n[1] * d[1] + n[2] * d[2] >= 0) return Double.NaN; // its back: not drawn (cull)
        double[] e1 = t.e1, e2 = t.e2, a = t.a;
        double hx = d[1] * e2[2] - d[2] * e2[1], hy = d[2] * e2[0] - d[0] * e2[2], hz = d[0] * e2[1] - d[1] * e2[0];
        double det = e1[0] * hx + e1[1] * hy + e1[2] * hz;
        if (Math.abs(det) < 1e-12) return Double.NaN;
        double f = 1 / det, sx = ox - a[0], sy = oy - a[1], sz = oz - a[2];
        double u = f * (sx * hx + sy * hy + sz * hz);
        if (u < -1e-6 || u > 1 + 1e-6) return Double.NaN;
        double qx = sy * e1[2] - sz * e1[1], qy = sz * e1[0] - sx * e1[2], qz = sx * e1[1] - sy * e1[0];
        double v = f * (d[0] * qx + d[1] * qy + d[2] * qz);
        if (v < -1e-6 || u + v > 1 + 1e-6) return Double.NaN;
        return f * (e2[0] * qx + e2[1] * qy + e2[2] * qz);
    }

    private static boolean shownAt(Tri t, double ox, double oy, double oz, double[] d, double k) {
        double[] a = t.a, e1 = t.e1, e2 = t.e2;
        // barycentric of the point: solve p - a = u e1 + v e2 in the plane
        double px = ox + d[0] * k - a[0], py = oy + d[1] * k - a[1], pz = oz + d[2] * k - a[2];
        double d00 = dot(e1, e1), d01 = dot(e1, e2), d11 = dot(e2, e2);
        double d20 = px * e1[0] + py * e1[1] + pz * e1[2], d21 = px * e2[0] + py * e2[1] + pz * e2[2];
        double den = d00 * d11 - d01 * d01;
        if (Math.abs(den) < 1e-18) return true;
        double v = (d11 * d20 - d01 * d21) / den, w = (d00 * d21 - d01 * d20) / den, u = 1 - v - w;
        double[] uv = t.uv;
        double tu = u * uv[0] + v * uv[2] + w * uv[4], tv = u * uv[1] + v * uv[3] + w * uv[5];
        int x = Math.floorMod((int) Math.floor(tu * texW), texW), y = Math.floorMod((int) Math.floor(tv * texH), texH);
        return shown[y * texW + x];
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
