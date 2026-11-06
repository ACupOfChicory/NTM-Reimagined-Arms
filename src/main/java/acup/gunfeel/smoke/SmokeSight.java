package acup.gunfeel.smoke;

import acup.gunfeel.ReaimTimes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * A mob does not see through smoke ({@code smokeBlocksMobSight} in the server's properties, off by default): its line
 * of sight to a target fails when the smoke between their eyes lets less than about 5% through. A mob loses a target
 * it cannot see as it would behind a wall; one close enough to touch still finds it.
 */
public final class SmokeSight {
    public static final String KEY = "smokeBlocksMobSight";
    // optical depth past which nothing is seen (e^-3 = 5%), and how near a target is felt rather than seen
    private static final float DEPTH = 3F;
    private static final double TOUCH = 2.5;

    private SmokeSight() {
    }

    /** {@code eyeHeight}: the y of the target's eyes, as {@code hasLineOfSight} gets it. The cheap checks come first. */
    public static boolean hides(LivingEntity looker, Entity target, double eyeHeight) {
        if (!(looker instanceof Mob) || !(looker.level() instanceof ServerLevel level)) return false;
        SmokeClouds all = SmokeClouds.get(level);
        if (all == null || all.isEmpty()) return false;
        if (!ReaimTimes.flag(KEY, false)) return false;
        Vec3 from = new Vec3(looker.getX(), looker.getEyeY(), looker.getZ());
        Vec3 to = new Vec3(target.getX(), eyeHeight, target.getZ());
        if (from.distanceToSqr(to) < TOUCH * TOUCH) return false;
        return all.opticalDepth(from, to) > DEPTH;
    }
}
