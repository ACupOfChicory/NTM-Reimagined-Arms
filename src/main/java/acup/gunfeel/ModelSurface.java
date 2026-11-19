package acup.gunfeel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

// Точные грани дополняют сетку 16³. Сервер получает вершины в координатах блока, координаты пикселей
// и маску прозрачности, без клиентских классов и атласа текстур. Сетка задаёт толщину открытой плоскости.
final class ModelSurface extends ModelSurfaceBase {
    private ModelSurface() {
    }

    // a bullet met a machine's window: it pierces it as glass (PassableClip)
    static final class GlassHit extends BlockHitResult {
        GlassHit(Vec3 at, Direction face, BlockPos pos) {
            super(at, face, pos, false);
        }
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

    static long[] rotate(long[] shape, net.minecraft.world.level.block.Rotation rotation) {
        long[] out = new long[65];
        out[64] = shape[64];
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int[] n = Structures.turn(x, z, rotation);
            for (int y = 0; y < 16; y++) if (ShapeCells.cell(shape, x, y, z)) {
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

    // Снаружи луч встречает лицевую грань. Выход через обратную грань задаёт толщину закрытой детали.
    // Для открытых двусторонних плоскостей остаётся толщина клетки.
    static @Nullable BlockHitResult hit(long[] shape, BlockPos pos, Vec3 from, Vec3 to, boolean leaving) {
        return hit(shape, pos, from, to, leaving, false);
    }

    static @Nullable BlockHitResult hit(long[] shape, BlockPos pos, Vec3 from, Vec3 to, boolean leaving, boolean withinCell) {
        return hit(shape, pos, from, to, leaving, withinCell, 0);
    }

    // hidden: the block's sides (bit Direction.ordinal) its neighbours hide, as the game leaves them undrawn
    // (Block.shouldRenderFace: the end of a bar against the next bar). A face lying in such a side meets nothing:
    // there is no pixel there, the bullet goes on into the neighbour's part, as holes are not put on it (Tracers)
    static @Nullable BlockHitResult hit(long[] shape, BlockPos pos, Vec3 from, Vec3 to, boolean leaving, boolean withinCell, int hidden) {
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
            if ((!glass || !leaving && windows == 1) && (leaving ? facing > 1e-12 : facing < -1e-12) && !(hidden != 0 && inSide(shape, at, hidden))) {
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

    // the face at `at` lies in one of the block's sides in `sides` (bit Direction.ordinal)
    static boolean inSide(long[] shape, int at, int sides) {
        for (int axis = 0; axis < 3; axis++) {
            double a = number(shape, at + axis), b = number(shape, at + 3 + axis), c = number(shape, at + 6 + axis);
            if (Math.max(a, Math.max(b, c)) - Math.min(a, Math.min(b, c)) > 1e-6) continue;
            Direction.Axis ax = Direction.Axis.values()[axis];
            if (Math.abs(a - 1) < 1e-6 && (sides >> Direction.fromAxisAndDirection(ax, Direction.AxisDirection.POSITIVE).ordinal() & 1) != 0) return true;
            if (Math.abs(a) < 1e-6 && (sides >> Direction.fromAxisAndDirection(ax, Direction.AxisDirection.NEGATIVE).ordinal() & 1) != 0) return true;
        }
        return false;
    }
}
