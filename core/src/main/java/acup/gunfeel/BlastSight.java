package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.RayHit;
import acup.gunfeel.host.WorldView;

/**
 * How much of a body's part a blast sees (BlastParts on each version): the game's rays from the part's points to the
 * blast's nodes, as NTM's getBlockDensity's, a point inside a block's collision shape sending none. The world's ray and
 * shapes are the game's (WorldView); the arithmetic is 26.2's BlastParts' in its order, on core's vector (Vec3d, Vec3's
 * own), so the share comes out the same to the bit.
 */
public final class BlastSight {
    private BlastSight() {
    }

    /** the part's points the rays start from: its middle, then pairs along its longest edge */
    public static List<Vec3d> points(ModelBox b, int rays) {
        double lu = Math.sqrt(b.ux() * b.ux() + b.uy() * b.uy() + b.uz() * b.uz());
        double lv = Math.sqrt(b.vx() * b.vx() + b.vy() * b.vy() + b.vz() * b.vz());
        double lw = Math.sqrt(b.wx() * b.wx() + b.wy() * b.wy() + b.wz() * b.wz());
        int axis = lu >= lv && lu >= lw ? 0 : lv >= lw ? 1 : 2;
        List<Vec3d> out = new ArrayList<>(rays);
        out.add(b.point(.5, .5, .5));
        for (int i = 1; out.size() < rays; i++) {
            // 0.15 and 0.85 of the length, then halfway in to the middle and so on
            double off = 0.35 / i;
            for (double t : new double[] { .5 - off, .5 + off }) {
                if (out.size() >= rays) break;
                out.add(b.point(axis == 0 ? t : .5, axis == 1 ? t : .5, axis == 2 ? t : .5));
            }
        }
        return out;
    }

    /**
     * The share of the part's rays that reach the blast from its best node, as NTM's getBlockDensity's: the game's ray
     * (its collision shapes, no fluids, {@code by}'s collision context) from each of its points not inside a block to
     * each node.
     */
    public static double visibility(WorldView w, @Nullable Body by, ModelBox b, Vec3d[] nodes, int rays) {
        // a point inside a block (a big model reaches into a wall) sends no ray: the part's others go by themselves
        List<Vec3d> from = new ArrayList<>(rays);
        for (Vec3d p : points(b, rays)) if (!solid(w, p)) from.add(p);
        if (from.isEmpty()) return 0;
        RayHit hit = new RayHit();
        double best = 0;
        for (Vec3d node : nodes) {
            int clear = 0;
            for (Vec3d p : from)
                if (!w.clip(p.x(), p.y(), p.z(), node.x(), node.y(), node.z(), WorldView.COLLIDER, WorldView.FLUID_NONE, by, hit)) clear++;
            best = Math.max(best, clear / (double) from.size());
            if (best >= 1) break;
        }
        return best;
    }

    /**
     * The point is inside its block's collision shape (the game's, with no entity's context): the point less the
     * block's place (Vec3.subtract: the place's negation added) in one of the shape's boxes (AABB.contains: from its
     * min, short of its max).
     */
    public static boolean solid(WorldView w, Vec3d p) {
        int x = GameMath.floor(p.x()), y = GameMath.floor(p.y()), z = GameMath.floor(p.z());
        double[] boxes = new double[24];
        int n = w.boxes(x, y, z, WorldView.COLLIDER, null, boxes);
        if (n < 0) n = w.boxes(x, y, z, WorldView.COLLIDER, null, boxes = new double[-n * 6]);
        if (n == 0) return false;
        Vec3d in = p.subtract(x, y, z);
        for (int i = 0; i < n * 6; i += 6)
            if (in.x() >= boxes[i] && in.x() < boxes[i + 3] && in.y() >= boxes[i + 1] && in.y() < boxes[i + 4] && in.z() >= boxes[i + 2]
                    && in.z() < boxes[i + 5]) return true;
        return false;
    }
}
