package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The AI check's crowd, on an open field east of the fight: ten soldiers in two columns of five, one behind another,
 * all on one target to the north, the back ones shooting past the front ones. Twice for the same time: first with
 * the line of fire unchecked (as before), then checked; the hits they take from each other are counted both times.
 * Everyone's health is put back every tick: nobody dies, the counts stay comparable.
 */
final class AiCheckCrowd {
    private enum Phase {
        SETUP, RUN, DONE
    }

    static final int X0 = 90, Z0 = 4, SIZE = 40, RUN_TICKS = 400;
    static final double TX = 110.5, TZ = 8.5;
    static final double[] COLUMNS = {108.5, 112.5};
    static final double[] ROWS = {18.5, 21.5, 24.5, 27.5, 30.5};

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private Phase phase = Phase.SETUP;
    private int ticks, round;
    private Mob target;
    private final List<Mob> soldiers = new ArrayList<>();
    // per round: hits on their own, trigger pulls, the target's health lost, ticks held, steps aside
    private final int[] friendly = new int[2], pulls = new int[2], holds = new int[2], shifts = new int[2];
    private final double[] lost = new double[2];
    private int hitsAtStart;

    AiCheckCrowd(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    boolean tick() {
        ticks++;
        switch (phase) {
            case SETUP -> setup();
            case RUN -> run();
            case DONE -> {
                return true;
            }
        }
        return phase == Phase.DONE;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private static void health(Mob m) {
        var hp = m.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(1000);
        m.setHealth(m.getMaxHealth());
    }

    /** the open field: a stone floor with air over it, its chunks kept loaded (the stages after the crowd's stand on it) */
    static void field(ServerLevel level, int y) {
        for (int cx = X0 >> 4; cx <= (X0 + SIZE) >> 4; cx++)
            for (int cz = Z0 >> 4; cz <= (Z0 + SIZE) >> 4; cz++) level.setChunkForced(cx, cz, true);
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                level.setBlock(new BlockPos(x, y - 1, z), stone, Block.UPDATE_CLIENTS);
                for (int h = 0; h < 4; h++) level.setBlock(new BlockPos(x, y + h, z), air, Block.UPDATE_CLIENTS);
            }
        TacticalMap.of(level).want((int) TX, y, 20, 24, 6);
    }

    private void setup() {
        if (round == 0) {
            field(level, y);
            target = (Mob) net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("hbm", "entity_test_dummy"))
                    .create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            target.setPos(TX, y, TZ);
            target.addTag(AiBots.TARGET_TAG);
            health(target);
            level.addFreshEntity(target);
            AiBots.refreshTargets(level);
        }
        for (double x : COLUMNS)
            for (double z : ROWS) {
                List<Mob> made = AiSpawn.spawn(level, new net.minecraft.world.phys.Vec3(x, y, z), AiSpawn.Kind.DUMMY, 1, null);
                if (made.isEmpty()) continue;
                Mob m = made.get(0);
                m.setPos(x, y, z);
                // facing the target, north
                m.setYRot(180F);
                m.setYHeadRot(180F);
                m.setYBodyRot(180F);
                health(m);
                AiBot b = AiBots.get(m);
                if (b != null) b.gunner.ammo.setInfinite();
                soldiers.add(m);
            }
        AiGunner.checkLine = round == 1;
        hitsAtStart = AiBots.friendlyHits;
        phase = Phase.RUN;
        ticks = 0;
    }

    private void run() {
        lost[round] += target.getMaxHealth() - target.getHealth();
        target.setHealth(target.getMaxHealth());
        for (Mob m : soldiers) m.setHealth(m.getMaxHealth());
        if (ticks < RUN_TICKS) return;
        friendly[round] = AiBots.friendlyHits - hitsAtStart;
        for (Mob m : soldiers) {
            AiBot b = AiBots.get(m);
            if (b == null) continue;
            pulls[round] += b.gunner.pulls;
            holds[round] += b.gunner.holds;
            if (b.brain instanceof AiSoldier s) shifts[round] += s.shifts;
            m.discard();
        }
        check.out(String.format(Locale.ROOT, "     %d soldiers on one target, %d ticks, line of fire %s: %d hits on their own, %d pulls, the target lost %.0f health; held fire %d ticks, moved %d times",
                soldiers.size(), RUN_TICKS, round == 0 ? "unchecked" : "checked", friendly[round], pulls[round], lost[round], holds[round], shifts[round]));
        soldiers.clear();
        if (round == 0) {
            round = 1;
            phase = Phase.SETUP;
            return;
        }
        AiGunner.checkLine = true;
        check.check("unchecked, the back ones hit the front ones (the crowd shows it)", friendly[0] > 0);
        check.check("with the line of fire checked they hardly hit their own (a quarter at most, 2 at most)",
                friendly[1] <= 2 && friendly[1] * 4 <= Math.max(friendly[0], 1));
        check.check("and they still shoot the target", lost[1] > 0);
        target.discard();
        phase = Phase.DONE;
    }
}
