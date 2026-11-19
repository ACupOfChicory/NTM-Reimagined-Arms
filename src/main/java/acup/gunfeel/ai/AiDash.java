package acup.gunfeel.ai;

import acup.gunfeel.EntityBody;
import acup.gunfeel.Vec3d;
import acup.gunfeel.Wiring;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * 26.2's slide and roll (AiDashBase in core): the mob and its level passed as core's Body and WorldView, so AiSoldier
 * and the tests call it as before.
 */
public final class AiDash extends AiDashBase {
    /** On a run to cover ({@code goal} its middle): AiDashBase.consider for this mob in its level. */
    byte consider(ServerLevel level, Mob mob, long cover, Vec3 goal, boolean lowCover, boolean busy, long tick) {
        return consider(Wiring.world(level), EntityBody.of(mob), cover, goal.x, goal.y, goal.z, lowCover, busy, tick);
    }

    /** it runs toward the goal (within 30°): AiDashBase.heading */
    static boolean heading(Vec3 step, double dx, double dz) {
        return heading(new Vec3d(step.x, step.y, step.z), dx, dz);
    }
}
