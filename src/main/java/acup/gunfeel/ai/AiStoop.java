package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.EntityBody;
import acup.gunfeel.Vec3d;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * 26.2's running low (AiStoopBase in core): the threat's eye and the mob passed as core's Vec3d and Body, so AiSoldier
 * and the stands call it as before.
 */
public final class AiStoop extends AiStoopBase {
    /** the eye of the threat it runs from; null: none (it runs upright) */
    void from(@Nullable Vec3 eye) {
        fromEye(eye == null ? null : new Vec3d(eye.x, eye.y, eye.z));
    }

    /** the threat it runs from, where it is now: taken if it moved FOLLOW blocks or more */
    void follow(Vec3 eye) {
        followEye(new Vec3d(eye.x, eye.y, eye.z));
    }

    /** AiStoopBase.want for this mob */
    boolean want(Mob mob, AiMover mover, boolean lying, VoxelRay.Source voxels, long tick) {
        return want(EntityBody.of(mob), mover, lying, voxels, tick);
    }
}
