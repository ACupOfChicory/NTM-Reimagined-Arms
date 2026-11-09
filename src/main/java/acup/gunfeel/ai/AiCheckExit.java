package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The AI check's "where it may come out" (ExitSpots), on a field of its own: four soldiers behind a low wall, a stone
 * wall three high and eleven long 8 blocks beyond it. A target shows at one end of the wall, walks in behind it, stands
 * there a while and comes out at the other end, then stands in the open. From the tick it is in plain sight of a
 * soldier again: the ticks until one of them sees it, and until one hits it; and how many watched where it came out.
 * Rounds with no soldier choosing the exit (the share 0: all watch where it went) against the game's share, the walk
 * each way in turn. Held to: none chooses it at the share 0, some do and watch it at the game's, the target is seen
 * again in all rounds of each setting but {@link #MISSED} at most (it comes out in plain sight in all) (one round in about sixty all four looked away the
 * whole time: soldiers searching round the middle of the wall, the target out at its end); the ticks are told (the
 * soldier at the far end of the wall sees it come out either way).
 */
final class AiCheckExit {
    static final int X0 = 640, Z0 = 4, SIZE = 48;
    static final int WALL_X0 = 656, WALL_X1 = 666, WALL_Z = 30, LOW_Z = 24, SOLDIER_Z = 22;
    static final double[] SOLDIER_X = {655.5, 659.5, 663.5, 667.5};
    /** the target's walk: z, from x to x (west to east; mirrored the other way), blocks a tick */
    static final double WALK_Z = 31.5, WEST = 652.5, EAST = 670.5, STEP = 0.15;
    /** ticks shown before the walk, ticks stood hidden in the middle of it, ticks after it is out at most */
    static final int SHOW = 80, PAUSE = 30, AFTER = 120;
    static final int ROUNDS = 16;
    /** rounds of a setting it may not be seen again in */
    static final int MISSED = 1;
    static final Identifier TARGET = Identifier.fromNamespaceAndPath("hbm", "entity_test_dummy");

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int round = -1, ticks;
    private final List<Mob> soldiers = new ArrayList<>();
    private Mob target;
    private double x;
    private int paused;
    /** this round: the tick it was out in plain sight again (-1: not yet), seen again, hit again (-1: not) */
    private int out = -1, seenAgain = -1, hitAgain = -1, watchedExit, knewBefore;
    /** per setting (0: share 0, 1: the game's): rounds, sums of ticks to seen and hit, rounds seen and hit, watchers */
    private final int[] rounds = new int[2], seenSum = new int[2], hitSum = new int[2], seenRounds = new int[2], hitRounds = new int[2],
            watchers = new int[2], exitsChosen = new int[2], outRounds = new int[2];
    private final List<String> lines = new ArrayList<>();

    AiCheckExit(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    boolean tick() {
        if (!started) {
            started = true;
            for (int cx = X0 >> 4; cx <= (X0 + SIZE) >> 4; cx++)
                for (int cz = Z0 >> 4; cz <= (Z0 + SIZE) >> 4; cz++) level.setChunkForced(cx, cz, true);
            build();
            AiBots.awake = true;
            start(0);
            return false;
        }
        ticks++;
        boolean east = round % 2 == 0;
        // the walk: shown, in behind the wall, a stand there, out at the other end, then still
        if (ticks == SHOW)
            for (Mob m : soldiers) {
                AiBot b = AiBots.get(m);
                TargetMemory.Contact c = b == null ? null : b.memory.contact(target.getId());
                if (c != null && c.known()) knewBefore++;
            }
        if (ticks > SHOW) {
            double mid = (WALL_X0 + WALL_X1 + 1) / 2.0;
            boolean atMid = east ? x >= mid : x <= mid;
            if (atMid && paused < PAUSE) paused++;
            else if (east ? x < EAST : x > WEST) x += east ? STEP : -STEP;
        }
        target.setPos(x, y, WALK_Z);
        // any hit since the last tick, then whole again (it never dies)
        boolean hurt = target.getHealth() < target.getMaxHealth();
        target.setHealth(target.getMaxHealth());
        boolean pastWall = east ? x > WALL_X1 + 1 : x < WALL_X0;
        if (out < 0 && ticks > SHOW && pastWall && inSight()) {
            out = ticks;
            for (Mob m : soldiers) {
                AiBot b = AiBots.get(m);
                if (b != null && b.brain instanceof AiSoldier s && s.watch != null && (east ? s.watch.x > WALL_X1 - 2 : s.watch.x < WALL_X0 + 3)) watchedExit++;
            }
        }
        if (out >= 0 && hitAgain < 0 && ticks - out == 60)
            for (Mob m : soldiers) {
                AiBot b = AiBots.get(m);
                if (b != null && b.brain instanceof AiSoldier s)
                    lines.add("         not hit 60 ticks after: " + s.describe(b) + " at " + m.blockPosition().toShortString() + ", shots " + b.gunner.shots);
            }
        if (out >= 0) {
            if (seenAgain < 0 && anySees()) seenAgain = ticks;
            if (hitAgain < 0 && hurt) hitAgain = ticks;
        }
        if (out >= 0 && (ticks - out > AFTER || seenAgain >= 0 && hitAgain >= 0) || ticks > SHOW + 600) {
            finishRound();
            if (round + 1 >= ROUNDS) return finishAll();
            start(round + 1);
        }
        return false;
    }

    /** the target in plain sight of a soldier (its chest from the soldier's eye) */
    private boolean inSight() {
        Vec3 chest = target.position().add(0, 1.0, 0);
        for (Mob m : soldiers) {
            Vec3 e = AiLean.eye(m);
            if (VoxelRay.clear(AiBots.voxels(level), e.x, e.y, e.z, chest.x, chest.y, chest.z, Voxel.SIGHT)) return true;
        }
        return false;
    }

    private boolean anySees() {
        for (Mob m : soldiers) {
            AiBot b = AiBots.get(m);
            TargetMemory.Contact c = b == null ? null : b.memory.contact(target.getId());
            if (c != null && c.state == TargetMemory.State.VISIBLE) return true;
        }
        return false;
    }

    private void finishRound() {
        int set = setting(round);
        int exits = 0;
        for (Mob m : soldiers) {
            AiBot b = AiBots.get(m);
            if (b != null && b.brain instanceof AiSoldier s) exits += s.exits;
        }
        rounds[set]++;
        exitsChosen[set] += exits;
        watchers[set] += watchedExit;
        if (out >= 0) outRounds[set]++;
        if (out >= 0 && seenAgain >= 0) {
            seenSum[set] += seenAgain - out;
            seenRounds[set]++;
        }
        if (out >= 0 && hitAgain >= 0) {
            hitSum[set] += hitAgain - out;
            hitRounds[set]++;
        }
        lines.add(String.format(Locale.ROOT, "       round %d (%s, walking %s): knew of it before the walk %d; out at %d, seen again %s, hit again %s; exits chosen %d, watching it %d", round,
                set == 0 ? "share 0" : "share " + AiSoldier.exitShare, round % 2 == 0 ? "east" : "west", knewBefore, out, seenAgain < 0 ? "-" : "+" + (seenAgain - out),
                hitAgain < 0 ? "-" : "+" + (hitAgain - out), exits, watchedExit));
        for (Mob m : soldiers) m.discard();
        soldiers.clear();
        target.discard();
    }

    /** the first half of the rounds with the share 0, the second with the game's */
    private static int setting(int round) {
        return round < ROUNDS / 2 ? 0 : 1;
    }

    private void start(int r) {
        round = r;
        ticks = paused = watchedExit = knewBefore = 0;
        out = seenAgain = hitAgain = -1;
        AiSoldier.exitShare = setting(r) == 0 ? 0F : AiSoldier.EXIT_SHARE;
        boolean east = r % 2 == 0;
        x = east ? WEST : EAST;
        for (double sx : SOLDIER_X) {
            List<Mob> made = AiSpawn.spawn(level, new Vec3(sx, y, SOLDIER_Z + 0.5), AiSpawn.Kind.DUMMY, 1, null);
            if (made.isEmpty()) continue;
            Mob m = made.get(0);
            m.setPos(sx, y, SOLDIER_Z + 0.5);
            // facing the wall, south (+z)
            m.setYRot(0F);
            m.setYHeadRot(0F);
            m.setYBodyRot(0F);
            AiBot b = AiBots.get(m);
            if (b != null) b.gunner.ammo.setInfinite();
            soldiers.add(m);
        }
        target = (Mob) BuiltInRegistries.ENTITY_TYPE.getValue(TARGET).create(level, EntitySpawnReason.COMMAND);
        target.setPos(x, y, WALK_Z);
        target.addTag(AiBots.TARGET_TAG);
        var hp = target.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(1000);
        target.setHealth(1000);
        level.addFreshEntity(target);
        AiBots.refreshTargets(level);
    }

    private boolean finishAll() {
        AiSoldier.exitShare = AiSoldier.EXIT_SHARE;
        check.out(String.format(Locale.ROOT, "     where it may come out, %d rounds each: the target out again, seen again in %d and %d rounds, in %s ticks (share 0) and %s (share %.1f);"
                + " hit again in %s and %s; exits chosen %d and %d; watching where it came out %d and %d", ROUNDS / 2, seenRounds[0], seenRounds[1], mean(seenSum[0], seenRounds[0]),
                mean(seenSum[1], seenRounds[1]), AiSoldier.EXIT_SHARE, mean(hitSum[0], hitRounds[0]), mean(hitSum[1], hitRounds[1]), exitsChosen[0], exitsChosen[1],
                watchers[0], watchers[1]));
        for (String l : lines) check.out(l);
        check.check("where it may come out: with the share 0 no soldier chooses the exit", exitsChosen[0] == 0);
        check.check("where it may come out: with the game's share soldiers choose it, and some watch where the target comes out",
                exitsChosen[1] > 0 && watchers[1] > 0);
        check.check("where it may come out: the target comes out in plain sight each round, and is seen again in each setting's rounds but one at most",
                outRounds[0] == ROUNDS / 2 && outRounds[1] == ROUNDS / 2 && seenRounds[0] >= ROUNDS / 2 - MISSED && seenRounds[1] >= ROUNDS / 2 - MISSED);
        return true;
    }

    private static String mean(int sum, int n) {
        return n == 0 ? "-" : String.format(Locale.ROOT, "%.1f", (double) sum / n);
    }

    private void set(int xx, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(xx, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private void build() {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int xx = X0; xx < X0 + SIZE; xx++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                set(xx, y - 1, z, stone);
                for (int h = 0; h < 4; h++) set(xx, y + h, z, air);
            }
        for (int xx = WALL_X0; xx <= WALL_X1; xx++)
            for (int h = 0; h < 3; h++) set(xx, y + h, WALL_Z, stone);
        for (int xx = WALL_X0 - 3; xx <= WALL_X1 + 3; xx++) set(xx, y, LOW_Z, stone);
        TacticalMap.of(level).want((WALL_X0 + WALL_X1) / 2, y, (LOW_Z + WALL_Z) / 2, 24, 6);
    }
}
