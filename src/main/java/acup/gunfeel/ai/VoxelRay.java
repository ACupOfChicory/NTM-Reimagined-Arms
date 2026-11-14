package acup.gunfeel.ai;

/**
 * A line through the blocks, block by block (Amanatides and Woo), asking each block's {@link Voxel} byte. For sight
 * the blocks that hide ({@link Voxel#SIGHT}) stop it; glass, bars, fences and grass do not. The block the line starts
 * in is not asked (an eye may sit in a leaf), the one it ends in is.
 */
public final class VoxelRay {
    private VoxelRay() {
    }

    /** where the voxels come from: the live world on the server thread, or the map's snapshots on any */
    public interface Source {
        int voxel(int x, int y, int z);
    }

    /** true when no block on the way has any of the bits in mask */
    public static boolean clear(Source src, double x0, double y0, double z0, double x1, double y1, double z1, int mask) {
        return blocked(src, x0, y0, z0, x1, y1, z1, mask) < 0;
    }

    /**
     * How far along the line (0..1) the first block with a bit of mask starts, or -1 when none does.
     */
    public static double blocked(Source src, double x0, double y0, double z0, double x1, double y1, double z1, int mask) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        int x = floor(x0), y = floor(y0), z = floor(z0);
        int ex = floor(x1), ey = floor(y1), ez = floor(z1);
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sy = dy > 0 ? 1 : dy < 0 ? -1 : 0, sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tdx = sx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double tdy = sy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double tdz = sz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double tx = sx == 0 ? Double.POSITIVE_INFINITY : (sx > 0 ? (x + 1 - x0) : (x0 - x)) * tdx;
        double ty = sy == 0 ? Double.POSITIVE_INFINITY : (sy > 0 ? (y + 1 - y0) : (y0 - y)) * tdy;
        double tz = sz == 0 ? Double.POSITIVE_INFINITY : (sz > 0 ? (z + 1 - z0) : (z0 - z)) * tdz;
        int steps = Math.abs(ex - x) + Math.abs(ey - y) + Math.abs(ez - z);
        for (int i = 0; i < steps; i++) {
            double t;
            if (tx <= ty && tx <= tz) {
                t = tx;
                x += sx;
                tx += tdx;
            } else if (ty <= tz) {
                t = ty;
                y += sy;
                ty += tdy;
            } else {
                t = tz;
                z += sz;
                tz += tdz;
            }
            if (t > 1) break;
            if ((src.voxel(x, y, z) & mask) != 0) return t;
        }
        return -1;
    }

    /**
     * A look along the line: how thick a veil it went through, in blocks of leaves (the length of the line in each block
     * marked {@link Voxel#LEAVES}, and in grass times `grass`); -1 when a block that hides otherwise ({@link Voxel#SIGHT}
     * with no mark) stops it, or the veil grows past `max`. From a source with no marks (the map's snapshots) leaves
     * stop it as a wall.
     */
    public static double veil(Source src, double x0, double y0, double z0, double x1, double y1, double z1, double grass, double max) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int x = floor(x0), y = floor(y0), z = floor(z0);
        int ex = floor(x1), ey = floor(y1), ez = floor(z1);
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sy = dy > 0 ? 1 : dy < 0 ? -1 : 0, sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tdx = sx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double tdy = sy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double tdz = sz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double tx = sx == 0 ? Double.POSITIVE_INFINITY : (sx > 0 ? (x + 1 - x0) : (x0 - x)) * tdx;
        double ty = sy == 0 ? Double.POSITIVE_INFINITY : (sy > 0 ? (y + 1 - y0) : (y0 - y)) * tdy;
        double tz = sz == 0 ? Double.POSITIVE_INFINITY : (sz > 0 ? (z + 1 - z0) : (z0 - z)) * tdz;
        int steps = Math.abs(ex - x) + Math.abs(ey - y) + Math.abs(ez - z);
        double sum = 0;
        for (int i = 0; i < steps; i++) {
            double t;
            if (tx <= ty && tx <= tz) {
                t = tx;
                x += sx;
                tx += tdx;
            } else if (ty <= tz) {
                t = ty;
                y += sy;
                ty += tdy;
            } else {
                t = tz;
                z += sz;
                tz += tdz;
            }
            if (t > 1) break;
            int v = src.voxel(x, y, z);
            int mark = Voxel.veil(v);
            if (mark != 0) {
                // the line leaves this block where it enters the next one, or ends in it
                double through = (Math.min(1, Math.min(tx, Math.min(ty, tz))) - t) * len;
                sum += (mark & Voxel.LEAVES) != 0 ? through : through * grass;
                if (sum > max) return -1;
            } else if ((v & Voxel.SIGHT) != 0) return -1;
        }
        return sum;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
