package acup.gunfeel;

/**
 * The blocks a bullet's segment crosses after its first, in order, for the blocks it always flies through (bullet
 * rules "through", PassableClip.clip with a visitor): a plain grid walk from the start's block, a step across the
 * nearest face each time (a tie: x, then y, then z), up to 512 steps, till each face's next crossing is past the end.
 * Not the game's walk (BlockWalk): no ends pushed out, the start's block not visited. A segment shorter than 1e-6:
 * nothing. The same operations in the same order as 26.2's PassableClip had them.
 */
public final class ThroughWalk {
    private ThroughWalk() {
    }

    /** A block the segment crosses (not the start's). */
    public interface Visit<C> {
        void at(C context, int x, int y, int z);
    }

    /** the walk from (fx, fy, fz) to (ex, ey, ez) */
    public static <C> void walk(double fx, double fy, double fz, double ex, double ey, double ez, C context, Visit<C> visit) {
        double ddx = ex - fx, ddy = ey - fy, ddz = ez - fz;
        double len = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
        if (len < 1e-6) return;
        // the cells the segment crosses, in order (a grid walk)
        int x = GameMath.floor(fx), y = GameMath.floor(fy), z = GameMath.floor(fz);
        int sx = ddx > 0 ? 1 : -1, sy = ddy > 0 ? 1 : -1, sz = ddz > 0 ? 1 : -1;
        double tx = ddx == 0 ? Double.MAX_VALUE : ((sx > 0 ? x + 1 - fx : fx - x) / Math.abs(ddx));
        double ty = ddy == 0 ? Double.MAX_VALUE : ((sy > 0 ? y + 1 - fy : fy - y) / Math.abs(ddy));
        double tz = ddz == 0 ? Double.MAX_VALUE : ((sz > 0 ? z + 1 - fz : fz - z) / Math.abs(ddz));
        double dx = ddx == 0 ? Double.MAX_VALUE : 1 / Math.abs(ddx), dy = ddy == 0 ? Double.MAX_VALUE : 1 / Math.abs(ddy),
                dz = ddz == 0 ? Double.MAX_VALUE : 1 / Math.abs(ddz);
        for (int i = 0; i < 512; i++) {
            if (tx <= ty && tx <= tz) {
                if (tx > 1) break;
                x += sx;
                tx += dx;
            } else if (ty <= tz) {
                if (ty > 1) break;
                y += sy;
                ty += dy;
            } else {
                if (tz > 1) break;
                z += sz;
                tz += dz;
            }
            visit.at(context, x, y, z);
        }
    }
}
