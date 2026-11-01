package acup.gunfeel;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

// Точные грани дополняют сетку 16³. Сервер получает вершины в координатах блока, координаты пикселей
// и маску прозрачности, без клиентских классов и атласа текстур. Сетка задаёт толщину открытой плоскости.
final class ModelSurface {
    private ModelSurface() {
    }

    // glass: its shown pixels are a machine's window (MachineGlass), not its casing
    record Face(double[] p, double[] uv, int width, int height, long[] alpha, boolean glass) {
        Face(double[] p, double[] uv, int width, int height, long[] alpha) {
            this(p, uv, width, height, alpha, false);
        }
    }

    // the face's size word: width, height and, in the top bit, glass
    private static final long GLASS = 1L << 63;

    private static int width(long word) {
        return (int) (word >>> 32 & 0x7fffffffL);
    }

    // a bullet met a machine's window: it pierces it as glass (PassableClip)
    static final class GlassHit extends BlockHitResult {
        GlassHit(Vec3 at, Direction face, BlockPos pos) {
            super(at, face, pos, false);
        }
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

    static Face local(Face face, BlockPos pos, net.minecraft.world.level.block.Rotation rotation) {
        double[] p = face.p().clone();
        for (int i = 0; i < 9; i += 3) {
            double x = p[i] - pos.getX(), z = p[i + 2] - pos.getZ();
            p[i] = switch (rotation) {
                case CLOCKWISE_90 -> 1 - z;
                case CLOCKWISE_180 -> 1 - x;
                case COUNTERCLOCKWISE_90 -> z;
                default -> x;
            };
            p[i + 1] -= pos.getY();
            p[i + 2] = switch (rotation) {
                case CLOCKWISE_90 -> x;
                case CLOCKWISE_180 -> 1 - z;
                case COUNTERCLOCKWISE_90 -> 1 - x;
                default -> z;
            };
        }
        return new Face(p, face.uv(), face.width(), face.height(), face.alpha(), face.glass());
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

    static long[] rotate(long[] shape, net.minecraft.world.level.block.Rotation rotation) {
        long[] out = new long[65];
        out[64] = shape[64];
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int[] n = Structures.turn(x, z, rotation);
            for (int y = 0; y < 16; y++) if (PassableClip.cell(shape, x, y, z)) {
                int k = n[0] + 16 * (y + 16 * n[1]);
                out[k >> 6] |= 1L << (k & 63);
            }
        }
        return present(shape) ? attach(out, faces(shape).stream().map(f -> local(f, BlockPos.ZERO, rotation)).toList()) : out;
    }

    static boolean overlaps(Face f, BlockPos pos) {
        for (int axis = 0; axis < 3; axis++) {
            double min = Math.min(f.p()[axis], Math.min(f.p()[axis + 3], f.p()[axis + 6]));
            double max = Math.max(f.p()[axis], Math.max(f.p()[axis + 3], f.p()[axis + 6]));
            double lo = axis == 0 ? pos.getX() : axis == 1 ? pos.getY() : pos.getZ();
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
            for (int j = 0; j < 15; j++) if (!Double.isFinite(Double.longBitsToDouble(shape[at + j]))) return false;
            int w = width(shape[at + 15]), h = (int) shape[at + 15];
            if (w < 1 || h < 1 || w > 4096 || h > 4096) return false;
            at += 16 + (w * h + 63) / 64;
            if (at > shape.length) return false;
        }
        return at == shape.length;
    }

    // Снаружи луч встречает лицевую грань. Выход через обратную грань задаёт толщину закрытой детали.
    // Для открытых двусторонних плоскостей остаётся толщина клетки.
    static @Nullable BlockHitResult hit(long[] shape, BlockPos pos, Vec3 from, Vec3 to, boolean leaving) {
        return hit(shape, pos, from, to, leaving, false);
    }

    static @Nullable BlockHitResult hit(long[] shape, BlockPos pos, Vec3 from, Vec3 to, boolean leaving, boolean withinCell) {
        Vec3 o = from.subtract(pos.getX(), pos.getY(), pos.getZ()), d = to.subtract(from);
        double best = Double.POSITIVE_INFINITY;
        Direction face = null;
        boolean metGlass = false;
        int windows = -1; // asked of the server's settings only when a window is there
        int at = 66;
        for (int i = 0; i < shape[65]; i++) {
            double ax = number(shape, at), ay = number(shape, at + 1), az = number(shape, at + 2);
            double ex = number(shape, at + 3) - ax, ey = number(shape, at + 4) - ay, ez = number(shape, at + 5) - az;
            double fx = number(shape, at + 6) - ax, fy = number(shape, at + 7) - ay, fz = number(shape, at + 8) - az;
            double nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
            double facing = nx * d.x + ny * d.y + nz * d.z;
            int w = width(shape[at + 15]), h = (int) shape[at + 15];
            boolean glass = (shape[at + 15] & GLASS) != 0;
            if (glass && !leaving && windows < 0) windows = MachineGlass.on() ? 1 : 0;
            if ((!glass || !leaving && windows == 1) && (leaving ? facing > 1e-12 : facing < -1e-12)) {
                double px = d.y * fz - d.z * fy, py = d.z * fx - d.x * fz, pz = d.x * fy - d.y * fx;
                double det = ex * px + ey * py + ez * pz;
                double sx = o.x - ax, sy = o.y - ay, sz = o.z - az;
                double b = (sx * px + sy * py + sz * pz) / det;
                double qx = sy * ez - sz * ey, qy = sz * ex - sx * ez, qz = sx * ey - sy * ex;
                double c = (d.x * qx + d.y * qy + d.z * qz) / det;
                double t = (fx * qx + fy * qy + fz * qz) / det;
                if (b >= -1e-8 && c >= -1e-8 && b + c <= 1 + 1e-8 && t > 1e-8 && t <= 1 && t < best) {
                    double a = 1 - b - c;
                    int u = Math.clamp((int) Math.floor(a * number(shape, at + 9) + b * number(shape, at + 11) + c * number(shape, at + 13)), 0, w - 1);
                    int v = Math.clamp((int) Math.floor(a * number(shape, at + 10) + b * number(shape, at + 12) + c * number(shape, at + 14)), 0, h - 1);
                    int pixel = u + w * v;
                    Vec3 point = o.add(d.scale(t));
                    boolean inside = !withinCell || point.x >= -1e-7 && point.x <= 1 + 1e-7
                            && point.y >= -1e-7 && point.y <= 1 + 1e-7 && point.z >= -1e-7 && point.z <= 1 + 1e-7;
                    if (inside && (shape[at + 16 + (pixel >> 6)] >>> (pixel & 63) & 1L) != 0) {
                        best = t;
                        face = Direction.getApproximateNearest(nx, ny, nz);
                        metGlass = glass;
                    }
                }
            }
            at += 16 + (w * h + 63) / 64;
        }
        if (face == null) return null;
        return metGlass ? new GlassHit(from.add(d.scale(best)), face, pos) : new BlockHitResult(from.add(d.scale(best)), face, pos, false);
    }

    private static double number(long[] data, int at) {
        return Double.longBitsToDouble(data[at]);
    }
}
