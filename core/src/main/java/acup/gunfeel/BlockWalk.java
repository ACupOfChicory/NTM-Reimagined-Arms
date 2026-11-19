package acup.gunfeel;

import org.jspecify.annotations.Nullable;

/**
 * The blocks a line passes, in order, from its start to its end: the walk Minecraft's {@code BlockGetter.clip} makes
 * (26.2's {@code BlockGetter.traverseBlocks}), done here so a bullet goes through the same blocks on every version (on
 * 1.7.10 the game's own walk has other margins and stops after 200 blocks). The same operations in the same order as
 * 26.2's, so each block and the order come out the same: both ends pushed out by 1e-7 of the line (an end on a face
 * reaches into the block beyond it), the start's block first, then a step across the nearest of the three block faces
 * each time (on a tie the face the game takes: z before x, y before x, z before y), till each face's next crossing is
 * past the end. The visitor's answer stops the walk; none, the miss's. BlockWalkVanillaTest on 26.2 holds it to the
 * game's walk.
 */
public final class BlockWalk {
    private BlockWalk() {
    }

    /** A block the line passes: the walk's answer when not null (the walk stops there). */
    public interface Visit<C, T> {
        @Nullable T at(C context, int x, int y, int z);
    }

    /** What the walk answers when no block did (or the line has no length). */
    public interface Miss<C, T> {
        T miss(C context);
    }

    /** the walk from (fx, fy, fz) to (tx, ty, tz); the same ends (each coordinate by Double.compare): the miss at once */
    public static <C, T> T walk(double fx, double fy, double fz, double tx, double ty, double tz, C context, Visit<C, T> visit, Miss<C, T> miss) {
        if (Double.compare(tx, fx) == 0 && Double.compare(ty, fy) == 0 && Double.compare(tz, fz) == 0) return miss.miss(context);
        double endX = lerp(-1.0E-7, tx, fx), endY = lerp(-1.0E-7, ty, fy), endZ = lerp(-1.0E-7, tz, fz);
        double startX = lerp(-1.0E-7, fx, tx), startY = lerp(-1.0E-7, fy, ty), startZ = lerp(-1.0E-7, fz, tz);
        int x = floor(startX), y = floor(startY), z = floor(startZ);
        T first = visit.at(context, x, y, z);
        if (first != null) return first;
        double dx = endX - startX, dy = endY - startY, dz = endZ - startZ;
        int sx = sign(dx), sy = sign(dy), sz = sign(dz);
        double stepX = sx == 0 ? Double.MAX_VALUE : sx / dx;
        double stepY = sy == 0 ? Double.MAX_VALUE : sy / dy;
        double stepZ = sz == 0 ? Double.MAX_VALUE : sz / dz;
        double nextX = stepX * (sx > 0 ? 1.0 - frac(startX) : frac(startX));
        double nextY = stepY * (sy > 0 ? 1.0 - frac(startY) : frac(startY));
        double nextZ = stepZ * (sz > 0 ? 1.0 - frac(startZ) : frac(startZ));
        while (nextX <= 1.0 || nextY <= 1.0 || nextZ <= 1.0) {
            if (nextX < nextY) {
                if (nextX < nextZ) {
                    x += sx;
                    nextX += stepX;
                } else {
                    z += sz;
                    nextZ += stepZ;
                }
            } else if (nextY < nextZ) {
                y += sy;
                nextY += stepY;
            } else {
                z += sz;
                nextZ += stepZ;
            }
            T found = visit.at(context, x, y, z);
            if (found != null) return found;
        }
        return miss.miss(context);
    }

    // Mth's, as 26.2 has them

    static double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }

    static int floor(double v) {
        return (int) Math.floor(v);
    }

    static double frac(double v) {
        return v - (double) (long) Math.floor(v);
    }

    static int sign(double v) {
        if (v == 0.0) return 0;
        return v > 0.0 ? 1 : -1;
    }
}
