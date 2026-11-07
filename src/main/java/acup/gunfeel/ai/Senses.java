package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.smoke.SmokeClouds;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Whether a mob sees a target now: within its range and field of view (around where its head looks), and a line from
 * its eyes to the target's head, chest or legs that no hiding block crosses (glass, bars and fences do not hide) and no
 * thick smoke ({@link SmokeClouds#opticalDepth} past 3, about 5% let through); and which of the three it sees (it aims
 * at what it sees). Nothing else: no light level yet.
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
    /** the parts the last {@link #look} that saw its target saw (server thread) */
    static int parts;

    private Senses() {
    }

    // for /armsai info: rays cast and time on them
    static long rays, looks, nanos;

    /** -1: not seen; else the ticks it takes to notice the target from here */
    public static int look(VoxelRay.Source voxels, @Nullable SmokeClouds smoke, Mob mob, LivingEntity target, AiProfile p) {
        long t0 = System.nanoTime();
        looks++;
        try {
            Vec3 eye = mob.getEyePosition();
            double h = target.getBbHeight();
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
            for (int i = 0; i < POINTS.length; i++) {
                double ty = target.getY() + h * POINTS[i];
                rays++;
                if (!VoxelRay.clear(voxels, eye.x, eye.y, eye.z, target.getX(), ty, target.getZ(), Voxel.SIGHT)) continue;
                if (smoky && smoke.opticalDepth(eye, new Vec3(target.getX(), ty, target.getZ())) > SMOKE_DEPTH) continue;
                seen |= PART[i];
            }
            if (seen == 0) return -1;
            parts = seen;
            return p.reactionTicks(d, cos < COS_CENTRE);
        } finally {
            nanos += System.nanoTime() - t0;
        }
    }

    /** whether a point is in plain sight from the eye (blocks and smoke only, no field of view) */
    public static boolean clearLine(VoxelRay.Source voxels, @Nullable SmokeClouds smoke, Vec3 eye, Vec3 point) {
        rays++;
        if (!VoxelRay.clear(voxels, eye.x, eye.y, eye.z, point.x, point.y, point.z, Voxel.SIGHT)) return false;
        return smoke == null || smoke.isEmpty() || smoke.opticalDepth(eye, point) <= SMOKE_DEPTH;
    }

    static String info() {
        return String.format(java.util.Locale.ROOT, "looks %d, rays %d, %.1f us a look", looks, rays,
                looks == 0 ? 0 : nanos / 1000.0 / looks);
    }
}
