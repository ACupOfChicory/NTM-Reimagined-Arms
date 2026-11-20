package acup.gunfeel;

import java.util.List;
import java.util.function.DoubleUnaryOperator;

import org.jspecify.annotations.Nullable;

/**
 * What BlastParts knows without the game: a part as the blast takes it (Part), its damage's arithmetic over the parts
 * (split: each part's distance over the range, its share of the rays clear, NTM's falloff with them, the shares and
 * the parts' mean multiplier), a box's surface, nearest point and its mesh's carrier box, the keys. BlastParts extends
 * it on each version with the mob's posed parts (ModelHits), the blast's run and the stand: its callers name these
 * members through it, BlastParts.Part, BlastParts.nearest and so on.
 */
public class BlastPartsBase {
    BlastPartsBase() {
    }

    /** a part as a blast counted it: its distance over the range, the share of its rays clear, NTM's damage */
    record Counted(String part, float multiplier, double share, double scaled, double seen, float raw) {
    }

    /**
     * blastPartsEven: the parts' multipliers divided by their mean over the whole mob (each part's share times its
     * multiplier), so a mob in the open takes about NTM's damage and a head still hurts more than a limb
     */
    static boolean even() {
        return ReaimTimes.flag("blastPartsEven", true);
    }

    /** blastPartRays: the rays from each part (its middle, then points along its length), 1 to 9 */
    static int rays() {
        return Math.clamp(Math.round(ReaimTimes.number("blastPartRays", 3F)), 1, 9);
    }

    /** NTM's damage with a part's distance (scaled by the range), visibility and push */
    @FunctionalInterface
    public interface Damage {
        float of(double scaled, double density, double knockback);
    }

    /** a part as the blast takes it: its box, multiplier and share */
    record Part(ModelBox box, float multiplier, double share) {
    }

    /** a part's share of the rays from the blast's nodes clear (BlastSight.visibility on the version's world) */
    @FunctionalInterface
    public interface Sight {
        double seen(ModelBox box);
    }

    /**
     * The mob's damage from the blast at {@code centre} with its range {@code size} by its {@code parts}; {@code whole}
     * is NTM's for the whole mob, and the answer when no part is in range or a sum is not finite. {@code scale} and
     * {@code push} are the water's (WaterBlast) changes to the distance and the push; {@code sight} is asked only below
     * density 1. {@code counted}: each part as counted, for the stand. {@code multiplier}: [0] the parts' multiplier,
     * [1] 1 when it was found (the answer is the parts' sum), left 0 when not.
     */
    static float split(List<Part> parts, Vec3d centre, float size, float whole, double density, Damage calc, DoubleUnaryOperator scale,
            DoubleUnaryOperator push, Sight sight, @Nullable List<Counted> counted, float[] multiplier) {
        double total = 0, weighted = 0;
        boolean reached = false, limbs = false;
        double mean = 0;
        for (Part p : parts) {
            limbs |= p.multiplier() < 1;
            mean += p.share() * p.multiplier();
            double scaled = scale.applyAsDouble(nearest(p.box(), centre.x(), centre.y(), centre.z()).distanceTo(centre) / size);
            reached |= scaled <= 1;
            if (!(scaled <= 1)) {
                if (counted != null) counted.add(new Counted(p.box().part(), p.multiplier(), p.share(), scaled, 0, 0));
                continue;
            }
            double seen = density >= 1 ? 1 : sight.seen(p.box());
            float raw = Math.max(0F, calc.of(scaled, seen, push.applyAsDouble((1 - scaled) * seen)));
            total += p.share() * raw;
            weighted += p.share() * raw * p.multiplier();
            if (counted != null) counted.add(new Counted(p.box().part(), p.multiplier(), p.share(), scaled, seen, raw));
        }
        // at the range's edge NTM's box is in it and no part is: NTM's damage, no part's multiplier
        if (!reached || !Double.isFinite(total) || !Double.isFinite(weighted)) return whole;
        float m = total > 0 ? (float) (weighted / total) : 1F;
        // even: by the whole mob's mean (a mob seen whole takes about NTM's); else a mob with a marked head and no
        // marked limbs (a pig, a chicken) takes no more than NTM's
        if (even() && mean > 0) m = (float) (m / mean);
        else if (!limbs) m = Math.min(m, 1F);
        multiplier[0] = m;
        multiplier[1] = 1F;
        return (float) total;
    }

    /** the most boxes a mob's pose may have for the blast to take it by its parts, and how far past its box they may go */
    static final int MAX_PARTS = 48;
    static final double REACH = 1.5;

    /** every corner of {@code b} in the box (minX, minY, minZ)..(maxX, maxY, maxZ), and none of them NaN */
    static boolean inside(ModelBox b, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        for (int i = 0; i < 8; i++) {
            double x = b.x() + (i & 1) * b.ux() + ((i >> 1) & 1) * b.vx() + ((i >> 2) & 1) * b.wx();
            double y = b.y() + (i & 1) * b.uy() + ((i >> 1) & 1) * b.vy() + ((i >> 2) & 1) * b.wy();
            double z = b.z() + (i & 1) * b.uz() + ((i >> 1) & 1) * b.vz() + ((i >> 2) & 1) * b.wz();
            if (!(x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ)) return false;
        }
        return true;
    }

    /** a mesh's box as the box around its mesh (ModelMesh's bounds in the box's own frame), a cube's as it is */
    static ModelBox carrier(ModelBox b) {
        if (b.mesh() == 0) return b;
        ModelMesh shape = ModelMesh.get(b.mesh());
        double[] k = shape == null ? null : shape.boundsData();
        if (k == null || k.length < 6) return b;
        double x = b.x() + b.ux() * k[0] + b.vx() * k[1] + b.wx() * k[2], y = b.y() + b.uy() * k[0] + b.vy() * k[1] + b.wy() * k[2],
                z = b.z() + b.uz() * k[0] + b.vz() * k[1] + b.wz() * k[2];
        double su = k[3] - k[0], sv = k[4] - k[1], sw = k[5] - k[2];
        return new ModelBox(b.part(), x, y, z, b.ux() * su, b.uy() * su, b.uz() * su, b.vx() * sv, b.vy() * sv, b.vz() * sv,
                b.wx() * sw, b.wy() * sw, b.wz() * sw);
    }

    static double surface(ModelBox b) {
        Vec3d u = new Vec3d(b.ux(), b.uy(), b.uz()), v = new Vec3d(b.vx(), b.vy(), b.vz()), w = new Vec3d(b.wx(), b.wy(), b.wz());
        return 2 * (u.cross(v).length() + v.cross(w).length() + w.cross(u).length());
    }

    /** the box's point nearest (px, py, pz) (its edges taken as square to each other, as a model's cube's are) */
    static Vec3d nearest(ModelBox b, double px, double py, double pz) {
        double[][] e = { { b.ux(), b.uy(), b.uz() }, { b.vx(), b.vy(), b.vz() }, { b.wx(), b.wy(), b.wz() } };
        double x = b.x(), y = b.y(), z = b.z();
        double dx = px - b.x(), dy = py - b.y(), dz = pz - b.z();
        for (double[] a : e) {
            double len2 = a[0] * a[0] + a[1] * a[1] + a[2] * a[2];
            if (len2 < 1e-12) continue;
            double t = Math.clamp((dx * a[0] + dy * a[1] + dz * a[2]) / len2, 0, 1);
            x += a[0] * t;
            y += a[1] * t;
            z += a[2] * t;
        }
        return new Vec3d(x, y, z);
    }
}
