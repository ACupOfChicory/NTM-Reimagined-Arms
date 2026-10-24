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

    record Face(double[] p, double[] uv, int width, int height, long[] alpha) {
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
            out[at++] = (long) f.width() << 32 | f.height();
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
            int w = (int) (shape[at] >>> 32), h = (int) shape[at++];
            int n = (w * h + 63) / 64;
            out.add(new Face(p, uv, w, h, java.util.Arrays.copyOfRange(shape, at, at + n)));
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
        return new Face(p, face.uv(), face.width(), face.height(), face.alpha());
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
            int w = (int) (shape[at + 15] >>> 32), h = (int) shape[at + 15];
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
        int at = 66;
        for (int i = 0; i < shape[65]; i++) {
            double ax = number(shape, at), ay = number(shape, at + 1), az = number(shape, at + 2);
            double ex = number(shape, at + 3) - ax, ey = number(shape, at + 4) - ay, ez = number(shape, at + 5) - az;
            double fx = number(shape, at + 6) - ax, fy = number(shape, at + 7) - ay, fz = number(shape, at + 8) - az;
            double nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
            double facing = nx * d.x + ny * d.y + nz * d.z;
            int w = (int) (shape[at + 15] >>> 32), h = (int) shape[at + 15];
            if (leaving ? facing > 1e-12 : facing < -1e-12) {
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
                    }
                }
            }
            at += 16 + (w * h + 63) / 64;
        }
        return face == null ? null : new BlockHitResult(from.add(d.scale(best)), face, pos, false);
    }

    private static double number(long[] data, int at) {
        return Double.longBitsToDouble(data[at]);
    }
}
