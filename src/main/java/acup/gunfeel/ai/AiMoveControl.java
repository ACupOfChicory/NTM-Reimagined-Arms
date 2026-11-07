package acup.gunfeel.ai;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;

/**
 * An AI mob's legs as a player's: walking, it keeps facing where it aims and steps along its path sideways or back,
 * as a player walks with the keys while looking anywhere (and NTM fires a mob's bullet the way its body faces). The
 * game's own control turns a walking mob to its path, at most 90 degrees a tick: a mob made to face its target
 * every tick could never walk away from it, and stood turning on the spot. Running (faster than a walk: a player
 * sprints only ahead) it faces its path, as the game has it; so does a mob with nothing to face this tick.
 */
final class AiMoveControl extends MoveControl<Mob> {
    private float faceYaw;
    private boolean facing;

    AiMoveControl(Mob mob) {
        super(mob);
    }

    /** face this way this tick, walking or not (from the mob's goal, before the control ticks) */
    void face(float yaw) {
        faceYaw = yaw;
        facing = true;
    }

    @Override
    public void tick() {
        boolean walks = operation == Operation.MOVE_TO, keep = facing && walks;
        double wx = wantedX, wz = wantedZ;
        facing = false;
        // the game sets sideways steps only for its own strafing, and never takes them back
        if (operation != Operation.STRAFE) mob.setXxa(0F);
        super.tick();
        if (!keep) return;
        mob.setYRot(faceYaw);
        double dx = wx - mob.getX(), dz = wz - mob.getZ(), len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4) return;
        // the step towards the wanted place, as forward and sideways from where it faces (the game's input vector:
        // x = sideways cos - forward sin, z = forward cos + sideways sin)
        float s = mob.getSpeed();
        double r = faceYaw * Mth.DEG_TO_RAD, sin = Math.sin(r), cos = Math.cos(r), ux = dx / len, uz = dz / len;
        mob.setZza((float) ((uz * cos - ux * sin) * s));
        mob.setXxa((float) ((ux * cos + uz * sin) * s));
    }
}
