package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The AI check's step up a block walking back: an AI mob with no mind of its own aims ahead (north) and walks to a
 * place 5 blocks behind it up a ledge a block high that starts 2 blocks behind it; straight back, to its side, and back
 * and aside. Each once with its jumps pushed the way it faces, as before (AiMoveControl.steerJumps off: it jumped where
 * it stood, the owner's fight; the guard, a few jumps with no way made, turns it to its path), then as now. Counted:
 * whether it got there and when, its jumps, the times it was stuck.
 */
final class AiCheckStep {
    static final int X0 = 700, Z0 = 4, SIZE = 32, RUN = 200;
    /** where it stands; the ledge from 2 blocks on its way, the place 5 on */
    static final int SX = 716, SZ = 18, LEDGE = 2, TO = 5;
    /** its ways (x, z): back (south), its side (east), back and aside */
    static final int[][] WAYS = { { 0, 1 }, { 1, 0 }, { 1, 1 } };
    static final String[] NAMES = { "back", "aside", "back and aside" };

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int round = -1, ticks, jumps, reachedAt;
    private boolean wasOnGround;
    private Mob mob;
    /** per round: {way, steered, reached at (-1: not), jumps, stuck} */
    private final List<int[]> rounds = new ArrayList<>();

    AiCheckStep(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    boolean tick() {
        if (!started) {
            started = true;
            for (int cx = X0 >> 4; cx <= (X0 + SIZE) >> 4; cx++)
                for (int cz = Z0 >> 4; cz <= (Z0 + SIZE) >> 4; cz++) level.setChunkForced(cx, cz, true);
            AiBots.awake = true;
            start(0);
            return false;
        }
        AiBot bot = AiBots.get(mob);
        if (bot == null) {
            check.check("step: the walker is an AI mob", false);
            return finish();
        }
        ticks++;
        // it aims ahead all the way, as one falling back firing; no shot
        bot.gunner.aim(new Vec3(mob.getX(), mob.getEyeY(), mob.getZ() - 12));
        bot.gunner.fire(false);
        boolean on = mob.onGround();
        if (wasOnGround && !on && mob.getDeltaMovement().y > 0) jumps++;
        wasOnGround = on;
        if (reachedAt < 0 && bot.mover.arrived() && mob.getY() > y + 0.9) reachedAt = ticks;
        if (ticks < RUN) return false;
        rounds.add(new int[] { round / 2, AiMoveControl.steerJumps ? 1 : 0, reachedAt, jumps, bot.mover.stuckJumps });
        mob.discard();
        mob = null;
        if (round + 1 >= WAYS.length * 2) return finish();
        start(round + 1);
        return false;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private void start(int r) {
        round = r;
        ticks = jumps = 0;
        reachedAt = -1;
        wasOnGround = true;
        int[] w = WAYS[r / 2];
        AiMoveControl.steerJumps = r % 2 == 1;
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                set(x, y - 1, z, stone);
                // the ledge: every cell LEDGE or more along its way
                int along = (x - SX) * w[0] + (z - SZ) * w[1];
                set(x, y, z, along >= LEDGE * (w[0] + w[1]) ? stone : air);
                for (int h = 1; h < 4; h++) set(x, y + h, z, air);
            }
        TacticalMap.of(level).want(SX, y, SZ, 12, 6);
        List<Mob> made = AiSpawn.spawn(level, new Vec3(SX + 0.5, y, SZ + 0.5), AiSpawn.Kind.DUMMY, 1, null);
        mob = made.get(0);
        mob.setPos(SX + 0.5, y, SZ + 0.5);
        mob.setYRot(180F);
        mob.setYHeadRot(180F);
        mob.setYBodyRot(180F);
        AiBot bot = AiBots.get(mob);
        if (bot != null) {
            bot.brain = null;
            bot.gunner.ammo.setInfinite();
            bot.mover.goTo(BlockPos.asLong(SX + w[0] * TO, y + 1, SZ + w[1] * TO), 0.8, AiSoldier.WALK);
        }
    }

    private boolean finish() {
        AiMoveControl.steerJumps = true;
        if (mob != null) mob.discard();
        boolean nowOk = true, beforeUp = true;
        int beforeStuck = 0;
        for (int[] r : rounds) {
            check.out(String.format(Locale.ROOT, "     step up a block walking %s, %s: %s, %d jumps, stuck %d times", NAMES[r[0]],
                    r[1] == 1 ? "jumps on its way (now)" : "jumps the way it faces (before; the guard on)", r[2] < 0 ? "never up" : "up at tick " + r[2], r[3], r[4]));
            if (r[1] == 1) nowOk &= r[2] >= 0 && r[2] <= RUN / 2 && r[3] <= 2 && r[4] == 0;
            else {
                beforeUp &= r[2] >= 0;
                beforeStuck += r[4];
            }
        }
        if (rounds.size() < WAYS.length * 2) {
            check.check("step: all rounds ran", false);
            return true;
        }
        check.check("walking back or aside up a block, aiming ahead: up within 5 seconds, 2 jumps at most, never stuck", nowOk);
        check.check("its jumps the way it faces (as before, the guard on): stuck jumping where it stands, and the guard gets it up all the same", beforeUp && beforeStuck > 0);
        return true;
    }
}
