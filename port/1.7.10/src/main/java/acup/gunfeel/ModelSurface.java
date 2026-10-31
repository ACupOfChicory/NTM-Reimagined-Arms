package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

// port: 26.2's ModelSurface (e284339 + glass from 901b1b3), Java 8: exact faces over the 16³ cells. A shape longer than
// 65 longs carries, after the cells and bits[64], its face count (bits[65]) and per face its corners in block space
// (9 doubles as long bits), the corners' pixel coordinates (6), a size word (width << 32 | height, the top bit for glass)
// and the alpha mask of its pixels (1 = shown). A shape with faces is met by them, not by its cells.
final class ModelSurface {
    private ModelSurface() {
    }

    // glass: its shown pixels are a machine's window (MachineGlass), not its casing
    static final class Face {
        final double[] p, uv;
        final int width, height;
        final long[] alpha;
        final boolean glass;

        Face(double[] p, double[] uv, int width, int height, long[] alpha, boolean glass) {
            this.p = p;
            this.uv = uv;
            this.width = width;
            this.height = height;
            this.alpha = alpha;
            this.glass = glass;
        }

        Face(double[] p, double[] uv, int width, int height, long[] alpha) {
            this(p, uv, width, height, alpha, false);
        }
    }

    // the face's size word: width, height and, in the top bit, glass
    private static final long GLASS = 1L << 63;

    private static int width(long word) {
        return (int) (word >>> 32 & 0x7fffffffL);
    }

    static long[] attach(long[] cells, List<Face> faces) {
        int size = 66;
        for (Face f : faces) size += 16 + f.alpha.length;
        long[] out = java.util.Arrays.copyOf(cells, size);
        out[65] = faces.size();
        int at = 66;
        for (Face f : faces) {
            for (double v : f.p) out[at++] = Double.doubleToLongBits(v);
            for (double v : f.uv) out[at++] = Double.doubleToLongBits(v);
            out[at++] = (long) f.width << 32 | f.height | (f.glass ? GLASS : 0L);
            for (long v : f.alpha) out[at++] = v;
        }
        return out;
    }

    static List<Face> faces(long[] shape) {
        List<Face> out = new ArrayList<Face>();
        if (!present(shape)) return out;
        int at = 66;
        for (int i = 0; i < shape[65]; i++) {
            double[] p = new double[9], uv = new double[6];
            for (int j = 0; j < 9; j++) p[j] = number(shape, at++);
            for (int j = 0; j < 6; j++) uv[j] = number(shape, at++);
            boolean glass = (shape[at] & GLASS) != 0;
            int w = width(shape[at]), h = (int) shape[at++];
            int n = (w * h + 63) / 64;
            out.add(new Face(p, uv, w, h, java.util.Arrays.copyOfRange(shape, at, at + n), glass));
            at += n;
        }
        return out;
    }

    // the face moved by (-x, -y, -z): from world or machine space into its block's
    static Face local(Face face, double x, double y, double z) {
        double[] p = face.p.clone();
        for (int i = 0; i < 9; i += 3) {
            p[i] -= x;
            p[i + 1] -= y;
            p[i + 2] -= z;
        }
        return new Face(p, face.uv, face.width, face.height, face.alpha, face.glass);
    }

    // does the face reach into the block at (x, y, z)
    static boolean overlaps(Face f, int x, int y, int z) {
        for (int axis = 0; axis < 3; axis++) {
            double min = Math.min(f.p[axis], Math.min(f.p[axis + 3], f.p[axis + 6]));
            double max = Math.max(f.p[axis], Math.max(f.p[axis + 3], f.p[axis + 6]));
            double lo = axis == 0 ? x : axis == 1 ? y : z;
            if (max < lo - 1e-7 || min > lo + 1 + 1e-7) return false;
        }
        return true;
    }

    static boolean present(long[] shape) {
        return shape.length >= 66;
    }

    static boolean valid(long[] shape) {
        if (!present(shape)) return shape.length == 65;
        if (shape[65] < 0 || shape[65] > 16384) return false;
        int at = 66;
        for (int i = 0; i < shape[65]; i++) {
            if (at + 16 > shape.length) return false;
            for (int j = 0; j < 15; j++) {
                double v = Double.longBitsToDouble(shape[at + j]);
                if (Double.isNaN(v) || Double.isInfinite(v)) return false;
            }
            int w = width(shape[at + 15]), h = (int) shape[at + 15];
            if (w < 1 || h < 1 || w > 4096 || h > 4096) return false;
            at += 16 + (w * h + 63) / 64;
            if (at > shape.length) return false;
        }
        return at == shape.length;
    }

    // From outside a ray meets a face turned to it; leaving, a face turned away (the far side of a closed part gives
    // its thickness). withinCell: only points in the block count (a face reaching into the next block is met there).
    static BlockRay.Hit hit(long[] shape, int x, int y, int z, double fx, double fy, double fz, double tx, double ty, double tz,
            boolean leaving) {
        return hit(shape, x, y, z, fx, fy, fz, tx, ty, tz, leaving, false);
    }

    static BlockRay.Hit hit(long[] shape, int x, int y, int z, double fx, double fy, double fz, double tx, double ty, double tz,
            boolean leaving, boolean withinCell) {
        double ox = fx - x, oy = fy - y, oz = fz - z, dx = tx - fx, dy = ty - fy, dz = tz - fz;
        double best = Double.POSITIVE_INFINITY;
        int face = -1;
        boolean metGlass = false;
        int windows = -1; // asked of the server's settings only when a window is there
        int at = 66;
        for (int i = 0; i < shape[65]; i++) {
            double ax = number(shape, at), ay = number(shape, at + 1), az = number(shape, at + 2);
            double ex = number(shape, at + 3) - ax, ey = number(shape, at + 4) - ay, ez = number(shape, at + 5) - az;
            double gx = number(shape, at + 6) - ax, gy = number(shape, at + 7) - ay, gz = number(shape, at + 8) - az;
            double nx = ey * gz - ez * gy, ny = ez * gx - ex * gz, nz = ex * gy - ey * gx;
            double facing = nx * dx + ny * dy + nz * dz;
            int w = width(shape[at + 15]), h = (int) shape[at + 15];
            boolean glass = (shape[at + 15] & GLASS) != 0;
            if (glass && !leaving && windows < 0) windows = MachineGlass.on() ? 1 : 0;
            if ((!glass || !leaving && windows == 1) && (leaving ? facing > 1e-12 : facing < -1e-12)) {
                double px = dy * gz - dz * gy, py = dz * gx - dx * gz, pz = dx * gy - dy * gx;
                double det = ex * px + ey * py + ez * pz;
                double sx = ox - ax, sy = oy - ay, sz = oz - az;
                double b = (sx * px + sy * py + sz * pz) / det;
                double qx = sy * ez - sz * ey, qy = sz * ex - sx * ez, qz = sx * ey - sy * ex;
                double c = (dx * qx + dy * qy + dz * qz) / det;
                double t = (gx * qx + gy * qy + gz * qz) / det;
                if (b >= -1e-8 && c >= -1e-8 && b + c <= 1 + 1e-8 && t > 1e-8 && t <= 1 && t < best) {
                    double a = 1 - b - c;
                    int u = clamp((int) Math.floor(a * number(shape, at + 9) + b * number(shape, at + 11) + c * number(shape, at + 13)), 0, w - 1);
                    int v = clamp((int) Math.floor(a * number(shape, at + 10) + b * number(shape, at + 12) + c * number(shape, at + 14)), 0, h - 1);
                    int pixel = u + w * v;
                    double hx = ox + dx * t, hy = oy + dy * t, hz = oz + dz * t;
                    boolean inside = !withinCell || hx >= -1e-7 && hx <= 1 + 1e-7 && hy >= -1e-7 && hy <= 1 + 1e-7 && hz >= -1e-7 && hz <= 1 + 1e-7;
                    if (inside && (shape[at + 16 + (pixel >> 6)] >>> (pixel & 63) & 1L) != 0) {
                        best = t;
                        face = BlockRay.nearest(nx, ny, nz);
                        metGlass = glass;
                    }
                }
            }
            at += 16 + (w * h + 63) / 64;
        }
        if (face < 0) return null;
        BlockRay.Hit h = new BlockRay.Hit(x, y, z, face, fx + dx * best, fy + dy * best, fz + dz * best, false);
        return metGlass ? new BlockRay.GlassHit(h) : h;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    private static double number(long[] data, int at) {
        return Double.longBitsToDouble(data[at]);
    }
}
