package acup.gunfeel.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;

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
public final class AiMoveControl extends MoveControl<Mob> {
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
    public boolean stuck() {
        boolean s = stuck;
        stuck = false;
        return s;
    }

    @Override
    public void tick() {
        boolean walks = operation == Operation.MOVE_TO, jumps = operation == Operation.JUMPING;
        legs();
        if (walks || jumps) inWater();
        if (walks || jumps) ladder();
    }

    /** off a ladder's way up or down this far at least the next point is, blocks: it climbs, not walks */
    static final double LADDER_DY = 0.5;
    /** the same, the next point straight over or under it on the ladder, blocks */
    static final double LADDER_NEAR = 0.05;

    /**
     * On a ladder with the next point of its way well over or under it: no step on (the path's next point may be the
     * cell beside the shaft's foot, and a mob pushing into the shaft's wall on a ladder is lifted up it, as a player
     * holding a key against it), kept to the middle of the shaft; down it slides, up it climbs as a player jumping on it.
     */
    private void ladder() {
        if (!mob.onClimbable()) return;
        double dy = wantedY - mob.getY();
        // the next point a rung straight over or under it: on to it all the way (let go short of it, it slid back)
        var path = mob.getNavigation().getPath();
        BlockPos next = path == null || path.isDone() ? null : path.getNextNodePos();
        boolean column = next != null && next.getX() == mob.getBlockX() && next.getZ() == mob.getBlockZ();
        // up at the rung it went for: on to the next point (the game's walk takes the next point only on the ground, or
        // falling past one: up a ladder it kept to the same rung)
        if (column && mob.getY() >= next.getY() - LADDER_NEAR && mob.getY() < next.getY() + 0.5 && path.getNextNodeIndex() + 1 < path.getNodeCount()
                && path.getNodePos(path.getNextNodeIndex() + 1).getY() > next.getY()) {
            path.advance();
            next = path.getNextNodePos();
            dy = next.getY() - mob.getY();
            column = next.getX() == mob.getBlockX() && next.getZ() == mob.getBlockZ();
        }
        // down the same: at the rung it slid to with the next point under it, on to that one (a way found from halfway
        // down a long shaft begins at its own rung: held there, it hung in the shaft for good)
        else if (column && Math.abs(mob.getY() - next.getY()) < 0.5 && path.getNextNodeIndex() + 1 < path.getNodeCount()
                && path.getNodePos(path.getNextNodeIndex() + 1).getY() < next.getY()) {
            path.advance();
            next = path.getNextNodePos();
            dy = next.getY() - mob.getY();
            column = next.getX() == mob.getBlockX() && next.getZ() == mob.getBlockZ();
        }
        // the next point beside the ladder's top a little over its feet: it climbs on till its feet are up to it (off
        // the ground a step up is no step: it walked against the shaft's rim, slid back and climbed again, over and over)
        boolean off = dy > 0 && !column && !mob.onGround();
        if (Math.abs(dy) < (column || off ? LADDER_NEAR : LADDER_DY)) return;
        // the same a little under it (the step up beside a shaft's floor, a ladder's top): on to it climbing as it goes
        // (held still under it, it slid back under it each tick it walked: it bobbed in the column for good)
        if (off && dy < LADDER_DY) {
            mob.getJumpControl().jump();
            return;
        }
        // on its feet on a ladder's foot, the way on off it and down (a step down beside it), or up a slab's height
        // beside it (the game steps it up): it walks
        if (!column && mob.onGround() && (dy < 0 || dy <= mob.maxUpStep())) return;
        mob.setXxa(0F);
        mob.setZza(0F);
        mob.setSpeed(0F);
        double cx = mob.getBlockX() + 0.5 - mob.getX(), cz = mob.getBlockZ() + 0.5 - mob.getZ();
        var v = mob.getDeltaMovement();
        mob.setDeltaMovement(Math.clamp(cx * 0.3, -0.05, 0.05), v.y, Math.clamp(cz * 0.3, -0.05, 0.05));
        // (the jump control ticks after the legs and sets the jump it was asked for: set straight, it was undone)
        if (dy > 0) mob.getJumpControl().jump();
    }

    private void legs() {
        boolean turned = turned();
        boolean walks = operation == Operation.MOVE_TO, jumps = operation == Operation.JUMPING, keep = facing && !turned && (walks || jumps && steerJumps);
        double wx = wantedX, wz = wantedZ;
        facing = false;
        // the game sets sideways steps only for its own strafing, and never takes them back
        if (operation != Operation.STRAFE) mob.setXxa(0F);
        super.tick();
        // on its feet in a ladder's cell, its way on off it beside, not up: the game jumps a mob out of any block with a
        // shape it stands in, the ladder's too, and on the ladder the jump is a climb (it rose, slid back on to its
        // floor and again, in the shaft's column at its foot for good). It walks off as on the ground
        // (not in water: there the jump asked before is its float up, FloatGoal)
        if (operation == Operation.JUMPING && !jumps && mob.onGround() && mob.getInBlockState().is(BlockTags.CLIMBABLE) && wantedY - mob.getY() <= mob.maxUpStep()
                && !mob.isInWater() && mob.getJumpControl() instanceof AiJumpControl j) {
            j.cancel();
            operation = Operation.WAIT;
        }
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

    /**
     * In water the game pushes a mob on by 0.02 of its input a tick, and a mob's input is its speed (0.3 or so), a
     * player's a whole key: a mob walking into a river made a quarter of a block a second and stood in the ford for
     * half a minute. In water its input is a player's, of the length of its pace (the speed modifier: a creep 0.7, a
     * walk 1, a run no more); on land the game's own.
     */
    private void inWater() {
        if (!mob.isInWater()) return;
        double len = Math.sqrt(mob.xxa * mob.xxa + mob.zza * mob.zza);
        if (len < 1e-4) return;
        double k = Math.min(1.0, speedModifier) / len;
        mob.setXxa((float) (mob.xxa * k));
        mob.setZza((float) (mob.zza * k));
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
