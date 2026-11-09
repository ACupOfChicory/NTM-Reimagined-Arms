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
    /** jumps started from about the same place (within STUCK_ROOM), where the first was, and till when it faces its path */
    private int jumpsHere;
    private double jumpX, jumpZ;
    private int lastJump = Integer.MIN_VALUE / 2;
    private int turnUntil = Integer.MIN_VALUE;
    private boolean stuck;
    /** this many jumps with no way made: it is stuck (a step it cannot get up) */
    static final int STUCK_JUMPS = 3;
    /** no way made: the jumps start within this of the first, blocks */
    static final double STUCK_ROOM = 0.5;
    /** and each within this many ticks of the one before */
    static final int STUCK_WITHIN = 40;
    /** stuck: it faces its path for this many ticks, as the game walks a mob (ahead, up the step), ticks */
    static final int TURN = 30;
    /** jumps steered to the wanted place (off only for the step stage's "before") */
    static boolean steerJumps = true;

    AiMoveControl(Mob mob) {
        super(mob);
    }

    /** face this way this tick, walking or not (from the mob's goal, before the control ticks) */
    void face(float yaw) {
        faceYaw = yaw;
        facing = true;
    }

    /** stuck jumping a while ago: it faces its path now (the gunner does not turn its body to the aim) */
    boolean turned() {
        return mob.tickCount < turnUntil;
    }

    /** stuck since asked last (the mover looks for another way), and clears it */
    boolean stuck() {
        boolean s = stuck;
        stuck = false;
        return s;
    }

    @Override
    public void tick() {
        boolean turned = turned();
        boolean walks = operation == Operation.MOVE_TO, jumps = operation == Operation.JUMPING, keep = facing && !turned && (walks || jumps && steerJumps);
        double wx = wantedX, wz = wantedZ;
        facing = false;
        // the game sets sideways steps only for its own strafing, and never takes them back
        if (operation != Operation.STRAFE) mob.setXxa(0F);
        super.tick();
        if (operation == Operation.JUMPING && !jumps) jumped();
        // jumping, the game pushes it on the way its body faces: facing away from its path (stepping back up a block
        // while it fires ahead) that took it off the step, and it jumped again and again where it stood. On the way
        // to the wanted place instead, as a player jumps holding the keys
        if (jumps && !keep && steerJumps && operation == Operation.JUMPING) {
            steer(wx, wz, mob.getYRot());
            return;
        }
        if (!keep) return;
        mob.setYRot(faceYaw);
        steer(wx, wz, faceYaw);
    }

    /** a jump started this tick: the third from about the same place, it is stuck */
    private void jumped() {
        double dx = mob.getX() - jumpX, dz = mob.getZ() - jumpZ;
        boolean again = mob.tickCount - lastJump <= STUCK_WITHIN;
        lastJump = mob.tickCount;
        if (jumpsHere > 0 && again && dx * dx + dz * dz <= STUCK_ROOM * STUCK_ROOM) jumpsHere++;
        else {
            jumpsHere = 1;
            jumpX = mob.getX();
            jumpZ = mob.getZ();
        }
        if (jumpsHere >= STUCK_JUMPS) {
            jumpsHere = 0;
            stuck = true;
            turnUntil = mob.tickCount + TURN;
        }
    }

    /**
     * The step towards the wanted place, as forward and sideways from where it faces (the game's input vector:
     * x = sideways cos - forward sin, z = forward cos + sideways sin)
     */
    private void steer(double wx, double wz, float yaw) {
        double dx = wx - mob.getX(), dz = wz - mob.getZ(), len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4) return;
        float s = mob.getSpeed();
        double r = yaw * Mth.DEG_TO_RAD, sin = Math.sin(r), cos = Math.cos(r), ux = dx / len, uz = dz / len;
        mob.setZza((float) ((uz * cos - ux * sin) * s));
        mob.setXxa((float) ((ux * cos + uz * sin) * s));
    }
}
