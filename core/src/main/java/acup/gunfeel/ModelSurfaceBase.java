package acup.gunfeel;

import java.util.List;

import org.jspecify.annotations.Nullable;

// What ModelSurface knows without the game: a shape's exact faces after its 16³ cells (Face; attach, faces, the
// size word, present, valid) and a face cut down to the block it lies in (crop). ModelSurface extends it on each
// version and meets the faces with the game's vectors and sides (hit, local, rotate, overlaps, inSide): its callers
// name these members through it, ModelSurface.Face, ModelSurface.attach and so on.
class ModelSurfaceBase {
    ModelSurfaceBase() {
    }

    // glass: its shown pixels are a machine's window (MachineGlass), not its casing
    record Face(double[] p, double[] uv, int width, int height, long[] alpha, boolean glass) {
        Face(double[] p, double[] uv, int width, int height, long[] alpha) {
            this(p, uv, width, height, alpha, false);
        }
    }

    // the face's size word: width, height and, in the top bit, glass
    static final long GLASS = 1L << 63;

    static int width(long word) {
        return (int) (word >>> 32 & 0x7fffffffL);
    }

    static long[] attach(long[] cells, List<Face> faces) {
        int size = 66;
        for (Face f : faces) size += 16 + f.alpha().length;
        long[] out = java.util.Arrays.copyOf(cells, size);
        out[65] = faces.size();
        int at = 66;
        for (Face f : faces) {
            for (double v : f.p()) out[at++] = Double.doubleToLongBits(v);
            for (double v : f.uv()) out[at++] = Double.doubleToLongBits(v);
            out[at++] = (long) f.width() << 32 | f.height() | (f.glass() ? GLASS : 0L);
            for (long v : f.alpha()) out[at++] = v;
        }
        return out;
    }

    static List<Face> faces(long[] shape) {
        List<Face> out = new java.util.ArrayList<>();
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

    // A face in a block's own coordinates (local) cut down to what lies in the block: its pixels only those under its
    // part inside the block (a pixel more round them), as a bullet meets a machine's faces only within a block
    // (hit, withinCell). A big face of a machine is otherwise kept whole in every block it crosses. Null: no pixel
    // of it shows in the block (or it does not reach into it).
    static @Nullable Face crop(Face face) {
        double[] p = face.p(), uv = face.uv();
        List<double[]> poly = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) poly.add(new double[] {p[i * 3], p[i * 3 + 1], p[i * 3 + 2], uv[i * 2], uv[i * 2 + 1]});
        for (int axis = 0; axis < 3 && !poly.isEmpty(); axis++) {
            poly = clip(poly, axis, -1e-6, true);
            poly = clip(poly, axis, 1 + 1e-6, false);
        }
        if (poly.isEmpty()) return null;
        int w = face.width(), h = face.height();
        if (w == 1 && h == 1) return face.alpha()[0] != 0 ? face : null;
        double minU = Double.POSITIVE_INFINITY, maxU = Double.NEGATIVE_INFINITY, minV = Double.POSITIVE_INFINITY, maxV = Double.NEGATIVE_INFINITY;
        for (double[] v : poly) {
            minU = Math.min(minU, v[3]);
            maxU = Math.max(maxU, v[3]);
            minV = Math.min(minV, v[4]);
            maxV = Math.max(maxV, v[4]);
        }
        int x0 = Math.clamp((long) Math.floor(minU) - 1, 0, w - 1), x1 = Math.clamp((long) Math.ceil(maxU) + 1, x0 + 1, w);
        int y0 = Math.clamp((long) Math.floor(minV) - 1, 0, h - 1), y1 = Math.clamp((long) Math.ceil(maxV) + 1, y0 + 1, h);
        int cw = x1 - x0, ch = y1 - y0, shown = 0;
        long[] alpha = new long[(cw * ch + 63) / 64];
        for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++) {
            int from = x0 + x + w * (y0 + y);
            if ((face.alpha()[from >> 6] >>> (from & 63) & 1L) == 0) continue;
            int k = x + cw * y;
            alpha[k >> 6] |= 1L << (k & 63);
            shown++;
        }
        if (shown == 0) return null;
        if (shown == cw * ch) return new Face(p, new double[6], 1, 1, new long[] {1}, face.glass());
        if (cw == w && ch == h) return face;
        double[] moved = uv.clone();
        for (int i = 0; i < 3; i++) {
            moved[i * 2] -= x0;
            moved[i * 2 + 1] -= y0;
        }
        return new Face(p, moved, cw, ch, alpha, face.glass());
    }

    // the polygon (x, y, z, u, v) cut at axis = at, keeping the side above it (or below)
    private static List<double[]> clip(List<double[]> poly, int axis, double at, boolean above) {
        List<double[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < poly.size(); i++) {
            double[] a = poly.get(i), b = poly.get((i + 1) % poly.size());
            boolean ina = above ? a[axis] >= at : a[axis] <= at, inb = above ? b[axis] >= at : b[axis] <= at;
            if (ina) out.add(a);
            if (ina != inb) {
                double t = (at - a[axis]) / (b[axis] - a[axis]);
                double[] c = new double[5];
                for (int k = 0; k < 5; k++) c[k] = a[k] + (b[k] - a[k]) * t;
                out.add(c);
            }
        }
        return out;
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
            for (int j = 0; j < 15; j++) if (!Double.isFinite(Double.longBitsToDouble(shape[at + j]))) return false;
            int w = width(shape[at + 15]), h = (int) shape[at + 15];
            if (w < 1 || h < 1 || w > 4096 || h > 4096) return false;
            at += 16 + (w * h + 63) / 64;
            if (at > shape.length) return false;
        }
        return at == shape.length;
    }

    static double number(long[] data, int at) {
        return Double.longBitsToDouble(data[at]);
    }
}
