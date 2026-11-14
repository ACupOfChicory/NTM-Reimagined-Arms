package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.smoke.SmokeClouds;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Whether a mob sees a target now: within its range and field of view (around where its head looks), and a line from
 * its eyes to the target's head, chest or legs that no hiding block crosses (glass, bars and fences do not hide; leaves
 * and grass veil it some way, {@link #VEIL}) and no thick smoke ({@link SmokeClouds#opticalDepth} past 3, about 5% let
 * through); and which of the three it sees (it aims at what it sees). How long it takes to notice: the distance, the
 * corner of the eye, the veil, the target lying, crouching or running. Nothing else: no light level yet.
 */
public final class Senses {
    /** the middle of the field of view, where a target is noticed fastest: 30 degrees off the look */
    private static final double COS_CENTRE = Math.cos(Math.toRadians(30));
    private static final float SMOKE_DEPTH = 3F;
    /** the points looked at, as parts of the target's height: head, chest, legs */
    private static final double[] POINTS = {0.9, 0.55, 0.2};
    /** the parts of a target a look sees: bits of {@link #parts} */
    public static final int HEAD = 1, CHEST = 2, LEGS = 4, WHOLE = 7;
    private static final int[] PART = {HEAD, CHEST, LEGS};
    /** the order a look that wants only whether it sees tries them: chest, head, legs (the chest the likeliest) */
    private static final int[] FIRST = {1, 0, 2};
    /** the parts the last {@link #look} that saw its target saw (server thread) */
    static int parts;

    /**
     * Leaves and grass veil a target (VoxelRay.veil, the live world's marks): seen through a veil this thick (in blocks
     * of leaves) it would be noticed never; past MAX_VEIL it is not seen at all (the owner's word: a player is seen
     * through about 8 blocks of leaves, no more). Behind a veil it takes longer to notice, as the share let through.
     */
    static final double VEIL = 9, MAX_VEIL = 8.5;
    /** grass, block for block, as a share of leaves: it hides little (the owner's word) */
    static final double GRASS = 0.15;
    /** the time to notice one lying, crouching, running, as a share of the time for one standing or walking */
    static final double LYING = 1.75, CROUCHING = 1.3, RUNNING = 0.75;
    /** faster than this (blocks over its last tick) a mob runs: a player walks at 0.216 */
    static final double RUN_PACE = 0.25;
    /**
     * A veil this thin or thinner is no hiding (noticed in under twice the time): one behind it is in plain sight, to its
     * cover's choice and its keeping (CoverQuery, AiSoldier) and to a peek round a corner ({@link #clearLine})
     */
    static final double PLAIN = 4;

    /** in plain sight along the line: no block that hides, and a veil of leaves and grass no thicker than {@link #PLAIN} */
    public static boolean plain(VoxelRay.Source voxels, double x0, double y0, double z0, double x1, double y1, double z1) {
        return VoxelRay.veil(voxels, x0, y0, z0, x1, y1, z1, GRASS, PLAIN) >= 0;
    }

    /** how its bearing moves the time to notice it: lying or crouching longer, running shorter */
    static double bearing(LivingEntity target) {
        double k = acup.gunfeel.Prone.lying(target) ? LYING : target.getPose() == net.minecraft.world.entity.Pose.CROUCHING ? CROUCHING : 1;
        Vec3 mv = ((acup.gunfeel.mixin.mc.EntityAccess) target).gunfeel$lastKnownSpeed();
        if (target.isSprinting() || mv.x * mv.x + mv.z * mv.z > RUN_PACE * RUN_PACE) k *= RUNNING;
        return k;
    }

    private Senses() {
    }

    // for /armsai info: rays cast and time on them
    static long rays, looks, nanos;

    /** -1: not seen; else the ticks it takes to notice the target from here; all three parts looked at */
    public static int look(VoxelRay.Source voxels, @Nullable SmokeClouds smoke, Mob mob, LivingEntity target, AiProfile p) {
        return look(voxels, smoke, mob, target, p, true);
    }

    /**
     * As above; with whole false (a target it does not aim at) it stops at the first part it sees, and {@link #parts}
     * holds just that one: three rays at each of fifty foes every look cost a hundred AI mobs 4.7 ms a tick (KT5)
     */
    public static int look(VoxelRay.Source voxels, @Nullable SmokeClouds smoke, Mob mob, LivingEntity target, AiProfile p, boolean whole) {
        long t0 = System.nanoTime();
        looks++;
        try {
            // its eye and the target's parts where a lean round a corner has them (AiLean)
            Vec3 eye = AiLean.eye(mob);
            double h = target.getBbHeight();
            boolean leaned = AiLean.frac(target) != 0F;
            double dx = target.getX() - eye.x, dz = target.getZ() - eye.z, dy = target.getY() + h * 0.6 - eye.y;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > p.viewRange() * p.viewRange() || d2 < 1e-6) {
                parts = WHOLE;
                return d2 < 1e-6 ? p.reaction() : -1;
            }
            double d = Math.sqrt(d2);
            Vec3 view = mob.getViewVector(1F);
            double cos = (dx * view.x + dy * view.y + dz * view.z) / d;
            if (cos < p.cosHalfFov()) return -1;
            boolean smoky = smoke != null && !smoke.isEmpty();
            int seen = 0;
            double thinnest = MAX_VEIL;
            for (int k = 0; k < POINTS.length; k++) {
                int i = whole ? k : FIRST[k];
                if (!whole && seen != 0) break;
                Vec3 at = leaned ? AiLean.point(target, h * POINTS[i]) : new Vec3(target.getX(), target.getY() + h * POINTS[i], target.getZ());
                rays++;
                double v = VoxelRay.veil(voxels, eye.x, eye.y, eye.z, at.x, at.y, at.z, GRASS, MAX_VEIL);
                if (v < 0) continue;
                if (smoky && smoke.opticalDepth(eye, at) > SMOKE_DEPTH) continue;
                seen |= PART[i];
                thinnest = Math.min(thinnest, v);
            }
            if (seen == 0) return -1;
            parts = seen;
            int t = p.reactionTicks(d, cos < COS_CENTRE);
            double k = bearing(target) / (1 - thinnest / VEIL);
            // a line that grazes a block of grass is no veil
            return Math.abs(k - 1) < 1e-3 ? t : (int) Math.ceil(t * k);
        } finally {
            nanos += System.nanoTime() - t0;
        }
    }

    /** whether a point is in plain sight from the eye (blocks, a veil past {@link #PLAIN} and smoke only, no field of view) */
    public static boolean clearLine(VoxelRay.Source voxels, @Nullable SmokeClouds smoke, Vec3 eye, Vec3 point) {
        rays++;
        if (!plain(voxels, eye.x, eye.y, eye.z, point.x, point.y, point.z)) return false;
        return smoke == null || smoke.isEmpty() || smoke.opticalDepth(eye, point) <= SMOKE_DEPTH;
    }

    static String info() {
        return String.format(java.util.Locale.ROOT, "looks %d, rays %d, %.1f us a look", looks, rays,
                looks == 0 ? 0 : nanos / 1000.0 / looks);
    }
}
