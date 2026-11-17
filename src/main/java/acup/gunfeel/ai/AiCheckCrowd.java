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
 * Everyone's health is put back every tick: nobody dies, the counts stay comparable. Then twice more, close: a knot of
 * grease guns shoulder to shoulder in two ranks (one close beside, a burst's kick), the line checked first without
 * the wider line of X1227 (AiBots.wideLine), then with it. Last a foe close at one's back (AiCheckBack).
 */
final class AiCheckCrowd {
    private enum Phase {
        SETUP, RUN, BACK, DONE
    }

    static final int X0 = 90, Z0 = 4, SIZE = 40, RUN_TICKS = 400;
    static final double TX = 110.5, TZ = 8.5;
    static final double[] COLUMNS = {108.5, 112.5};
    static final double[] ROWS = {18.5, 21.5, 24.5, 27.5, 30.5};
    /** the close knot: two ranks, shoulder to shoulder, the back one in the front one's gaps */
    static final double[][] KNOT = {{109.5, 18.5}, {110.15, 18.5}, {110.8, 18.5}, {111.45, 18.5}, {109.83, 19.0}, {110.48, 19.0}, {111.13, 19.0}};
    static final int ROUNDS = 4;
    static final net.minecraft.resources.Identifier GREASE = net.minecraft.resources.Identifier.fromNamespaceAndPath("hbm", "gun_greasegun");

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private Phase phase = Phase.SETUP;
    private int ticks, round;
    private Mob target;
    private final List<Mob> soldiers = new ArrayList<>();
    // per round: hits on their own, trigger pulls, the target's health lost, ticks held, steps aside
    private final int[] friendly = new int[ROUNDS], pulls = new int[ROUNDS], holds = new int[ROUNDS], shifts = new int[ROUNDS];
    private final double[] lost = new double[ROUNDS];
    private int hitsAtStart;
    private AiCheckBack back;

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
            case BACK -> {
                if (back.tick()) phase = Phase.DONE;
            }
            case DONE -> {
                return true;
            }
        }
        return phase == Phase.DONE;
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
        List<double[]> at = new ArrayList<>();
        if (round < 2) {
            for (double x : COLUMNS)
                for (double z : ROWS) at.add(new double[] {x, z});
        } else at.addAll(List.of(KNOT));
        var gun = round < 2 ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(GREASE);
        for (double[] p : at) {
            double x = p[0], z = p[1];
            {
                List<Mob> made = AiSpawn.spawn(level, new net.minecraft.world.phys.Vec3(x, y, z), AiSpawn.Kind.DUMMY, 1, gun);
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
        }
        // the target back where it stood (the shots of a round knock it off and down), the new ones told of it
        target.setPos(TX, y, TZ);
        target.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        AiBots.refreshTargets(level);
        AiGunner.checkLine = round != 0;
        AiBots.wideLine = round != 2;
        hitsAtStart = AiBots.friendlyHits;
        phase = Phase.RUN;
        ticks = 0;
    }

    /**
     * A shot at a place short of one of its own goes on past it (AiBots.friendInLine): one fires at a spot 3 blocks
     * ahead, one of its own stands 4 blocks past it behind glass, a shut wooden door, stone, or nothing. The bullet
     * pierces glass and the door: it holds fire; stone stops it: it fires.
     */
    private void barriers() {
        int z = 32, sx = 96, wx = 102;
        String[] names = {"nothing", "glass", "a shut oak door", "stone"};
        boolean[] want = {true, true, true, false};
        StringBuilder got = new StringBuilder();
        boolean ok = true;
        for (int i = 0; i < names.length; i++) {
            int zz = z + i * 3;
            for (int h = 0; h < 3; h++) level.setBlock(new BlockPos(wx, y + h, zz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            switch (i) {
                case 1 -> {
                    level.setBlock(new BlockPos(wx, y, zz), Blocks.GLASS.defaultBlockState(), Block.UPDATE_CLIENTS);
                    level.setBlock(new BlockPos(wx, y + 1, zz), Blocks.GLASS.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
                case 2 -> {
                    BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, net.minecraft.core.Direction.WEST);
                    level.setBlock(new BlockPos(wx, y, zz), door, Block.UPDATE_CLIENTS);
                    level.setBlock(new BlockPos(wx, y + 1, zz), door.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS);
                }
                case 3 -> {
                    level.setBlock(new BlockPos(wx, y, zz), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    level.setBlock(new BlockPos(wx, y + 1, zz), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
                default -> {
                }
            }
            List<Mob> a = AiSpawn.spawn(level, new net.minecraft.world.phys.Vec3(sx + 0.5, y, zz + 0.5), AiSpawn.Kind.DUMMY, 1, null);
            List<Mob> b = AiSpawn.spawn(level, new net.minecraft.world.phys.Vec3(wx + 4.5, y, zz + 0.5), AiSpawn.Kind.DUMMY, 1, null);
            if (a.isEmpty() || b.isEmpty()) {
                ok = false;
                got.append(names[i]).append(": no mob; ");
                continue;
            }
            Mob shooter = a.get(0), own = b.get(0);
            shooter.setPos(sx + 0.5, y, zz + 0.5);
            own.setPos(wx + 4.5, y, zz + 0.5);
            // facing east, along the line
            shooter.setYRot(-90F);
            shooter.setYHeadRot(-90F);
            shooter.setXRot(0F);
            var eye = shooter.getEyePosition();
            boolean held = AiBots.friendInLine(level, shooter, eye, eye.add(3, 0, 0), AiGunner.CLEAR_CONE);
            got.append(names[i]).append(held ? " holds" : " fires").append("; ");
            ok &= held == want[i];
            shooter.discard();
            own.discard();
        }
        // the glass, the door and the stone taken away: the next stages (prone) use this field
        for (int i = 0; i < names.length; i++)
            for (int h = 0; h < 3; h++) level.setBlock(new BlockPos(wx, y + h, z + i * 3), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        check.check("a shot at a place short of one of its own: it holds fire with it behind nothing, glass or a shut door past the place, fires with it behind stone ("
                + got.toString().trim() + ")", ok);
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
                soldiers.size(), RUN_TICKS, new String[] {"unchecked", "checked", "checked, close with grease guns, without X1227's wider line", "checked, close with grease guns, with X1227's wider line"}[round], friendly[round], pulls[round], lost[round], holds[round], shifts[round]));
        soldiers.clear();
        if (round + 1 < ROUNDS) {
            round++;
            phase = Phase.SETUP;
            return;
        }
        AiGunner.checkLine = true;
        AiBots.wideLine = true;
        check.check("unchecked, the back ones hit the front ones (the crowd shows it)", friendly[0] > 0);
        check.check("with the line of fire checked they hardly hit their own (a quarter at most, 2 at most)",
                friendly[1] <= 2 && friendly[1] * 4 <= Math.max(friendly[0], 1));
        check.check("and they still shoot the target", lost[1] > 0);
        check.check("close with grease guns, without X1227's wider line they hit their own (the knot shows it)", friendly[2] > 0);
        check.check(String.format(Locale.ROOT, "close with grease guns, with X1227's wider line they hardly hit their own (a quarter at most, 2 at most: %d against %d)", friendly[3], friendly[2]),
                friendly[3] <= 2 && friendly[3] * 4 <= Math.max(friendly[2], 1));
        check.check("and close they still shoot the target", lost[3] > 0);
        barriers();
        target.discard();
        AiBots.refreshTargets(level);
        back = new AiCheckBack(check, level, y);
        phase = Phase.BACK;
    }
}
