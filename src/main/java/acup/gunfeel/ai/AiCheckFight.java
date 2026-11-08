package acup.gunfeel.ai;

import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The AI check's fight, on a field of its own east of the yard: a stone wall two high and five long in the middle, a
 * stone block three high off to the north-east. A soldier (a dummy with a revolver and the rifleman's mind) sees a
 * target in the open, gets behind the wall, steps out to shoot and back; the target goes round its flank and shoots
 * it, it runs to other cover; the target is shut in a box far off, the soldier stays and watches where it was last
 * seen, then searches round that place (not where the target is). One step a server tick, each with a time limit.
 */
final class AiCheckFight {
    private enum Phase {
        SETUP, COVER, FIRE, FLANK, HOLD, SEARCH, DONE
    }

    static final int X0 = 48, Z0 = 4, SIZE = 36;
    // the wall: z = 20, x 60..64, two high; the block: x 66..68, z 7..9, three high
    static final Vec3 SEEN = new Vec3(62.5, 0, 10.5), FLANK = new Vec3(72.5, 0, 21.5), BOX = new Vec3(52.5, 0, 36.5);

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private Phase phase = Phase.SETUP;
    private int ticks;
    /** its moves (relocations and shifts) before the flanker shot it */
    private int movesBefore;
    /** the check's tick it went searching for the lost target (-1: not yet) */
    private int searchSince = -1;
    /** where it was when it went searching (a look out may have taken it a step or two off from where it held) */
    private Vec3 searchFrom;
    private Mob soldier, target;
    private AiBot bot;
    private AiSoldier mind;
    private Vec3 lostAt, holdStart;
    private double nearestToLost = Double.MAX_VALUE, nearestToBox = Double.MAX_VALUE;
    private long firstSearch = Long.MIN_VALUE;
    private boolean searchedOffLost;

    AiCheckFight(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    private String traced = "";
    private final List<String> traces = new java.util.ArrayList<>();

    /** for a failing check: a line each time the soldier's mode or contact changes, up to 40 */
    private void trace() {
        if (mind == null || target == null || traces.size() >= 60) return;
        TargetMemory.Contact c = bot.memory.contact(target.getId());
        String now = mind.mode() + " " + (c == null ? "-" : c.state) + " " + bot.gunner.loaded() + (bot.gunner.reloading() ? "r" : "") + " | "
                + bot.mover.state();
        if (now.equals(traced)) return;
        traced = now;
        traces.add(String.format(Locale.ROOT, "       %s t%d %s at %.1f %.1f yaw %.0f", phase, ticks, now, soldier.getX(), soldier.getZ(),
                soldier.getYHeadRot()));
    }

    /** a check of the fight; failed, the soldier's trace so far goes out under it */
    private void verdict(String name, boolean ok) {
        check.check(name, ok);
        if (!ok) for (String t : traces) check.out(t);
    }

    boolean tick() {
        ticks++;
        if (phase != Phase.SETUP && phase != Phase.DONE) trace();
        switch (phase) {
            case SETUP -> setup();
            case COVER -> cover();
            case FIRE -> fire();
            case FLANK -> flank();
            case HOLD -> hold();
            case SEARCH -> search();
            case DONE -> {
                return true;
            }
        }
        return phase == Phase.DONE;
    }

    private void next(Phase p) {
        phase = p;
        ticks = 0;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private Vec3 at(Vec3 v) {
        return new Vec3(v.x, y, v.z);
    }

    /** the cell the soldier stands in, hidden from the target's eyes */
    private boolean hiddenFromTarget() {
        Vec3 eye = target.getEyePosition();
        Vec3 head = soldier.position().add(0, 1.6, 0);
        return !VoxelRay.clear(AiBots.voxels(level), eye.x, eye.y, eye.z, head.x, head.y, head.z, Voxel.SIGHT);
    }

    private void setup() {
        for (int cx = X0 >> 4; cx <= (X0 + SIZE) >> 4; cx++)
            for (int cz = Z0 >> 4; cz <= (Z0 + SIZE) >> 4; cz++) level.setChunkForced(cx, cz, true);
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                set(x, y - 1, z, stone);
                for (int h = 0; h < 4; h++) set(x, y + h, z, air);
            }
        for (int x = 60; x <= 64; x++) {
            set(x, y, 20, stone);
            set(x, y + 1, 20, stone);
        }
        for (int x = 66; x <= 68; x++)
            for (int z = 7; z <= 9; z++)
                for (int h = 0; h < 3; h++) set(x, y + h, z, stone);
        TacticalMap.of(level).want(64, y, 22, 24, 6);

        List<Mob> made = AiSpawn.spawn(level, new Vec3(56.5, y, 23.5), AiSpawn.Kind.DUMMY, 1, null);
        if (made.isEmpty()) {
            check.check("the fight: a soldier spawns", false);
            next(Phase.DONE);
            return;
        }
        soldier = made.get(0);
        soldier.setPos(56.5, y, 23.5);
        // facing the target, north-east
        float yaw = (float) (Mth.atan2(SEEN.z - 23.5, SEEN.x - 56.5) * Mth.RAD_TO_DEG) - 90F;
        soldier.setYRot(yaw);
        soldier.setYHeadRot(yaw);
        soldier.setYBodyRot(yaw);
        bot = AiBots.get(soldier);
        mind = bot != null && bot.brain instanceof AiSoldier s ? s : null;
        check.check("a mob the command brings in has the rifleman's mind", mind != null);
        if (mind == null) {
            next(Phase.DONE);
            return;
        }
        target = (Mob) net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("hbm", "entity_test_dummy"))
                .create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        Vec3 t = at(SEEN);
        target.setPos(t.x, t.y, t.z);
        target.addTag(AiBots.TARGET_TAG);
        var hp = target.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(1000);
        target.setHealth(1000);
        level.addFreshEntity(target);
        AiBots.refreshTargets(level);
        next(Phase.COVER);
    }

    private void cover() {
        BlockPos p = soldier.blockPosition();
        boolean behind = p.getZ() == 21 && p.getX() >= 59 && p.getX() <= 65;
        if (behind && (mind.mode() == AiSoldier.Mode.COVER || mind.mode() == AiSoldier.Mode.PEEK)) {
            check.out(String.format(Locale.ROOT, "     saw the target and got behind the wall in %d ticks, at %s, peek %s", ticks,
                    p.toShortString(), mind.spot() == null || !mind.spot().hasPeek() ? "-" : BlockPos.of(mind.spot().peek()).toShortString()));
            verdict("sees a target in the open and gets behind the wall from it", true);
            next(Phase.FIRE);
        } else if (ticks > 160) {
            verdict("sees a target in the open and gets behind the wall from it (" + mind.describe(bot) + " at " + p.toShortString() + ")", false);
            next(Phase.FIRE);
        }
    }

    private void fire() {
        if (target.getHealth() < 1000 && mind.mode() == AiSoldier.Mode.COVER && ticks > 20 || ticks > 300) {
            check.out(String.format(Locale.ROOT, "     from cover: %d bursts, %d pulls, the target lost %.1f health in %d ticks",
                    mind.bursts, bot.gunner.pulls, 1000 - target.getHealth(), ticks));
            verdict("shoots from its peek and gets back into cover", target.getHealth() < 1000 && mind.bursts > 0);
            Vec3 f = at(FLANK);
            target.setPos(f.x, f.y, f.z);
            // the flanker shoots it: it turns to where the hit came from
            soldier.hurtServer(level, level.damageSources().mobAttack(target), 1F);
            movesBefore = mind.relocations + mind.shifts;
            // what it makes of the flank goes to the server's log (the journal of /armsai log)
            AiThoughts.log = true;
            next(Phase.FLANK);
        }
    }

    private void flank() {
        boolean hidden = hiddenFromTarget();
        // out of the flanker's sight it has lost it (HOLD) by the time it gets there
        // its cover given up as seen into, or left for other cover as shot at in it: either way a move
        int moves = mind.relocations + mind.shifts - movesBefore;
        if (moves > 0 && hidden && mind.spot() != null && bot.mover.arrived() || ticks > 300) {
            check.out(String.format(Locale.ROOT, "     flanked: %d moves (%d relocations), now at %s, hidden %s, in %d ticks", moves, mind.relocations,
                    soldier.blockPosition().toShortString(), hidden, ticks));
            verdict("flanked and shot: runs to cover the flanker cannot see into", moves > 0 && hidden);
            TargetMemory.Contact c = bot.memory.contact(target.getId());
            // shut the target in a stone box far off: it is gone from sight
            int bx = (int) Math.floor(BOX.x), bz = (int) Math.floor(BOX.z);
            for (int x = bx - 1; x <= bx + 1; x++)
                for (int z = bz - 1; z <= bz + 1; z++)
                    for (int h = 0; h < 3; h++)
                        if (x != bx || z != bz || h == 2) set(x, y + h, z, Blocks.STONE.defaultBlockState());
            Vec3 b = at(BOX);
            target.setPos(b.x, b.y, b.z);
            lostAt = c == null ? null : new Vec3(c.x, c.y, c.z);
            holdStart = soldier.position();
            // hit by the flanker from out of sight: it takes the one it lost for them, where they stand (the hit heard
            // within a couple of blocks), not where it saw them last
            Vec3 f = at(FLANK);
            double off = lostAt == null ? -1 : Math.hypot(lostAt.x - f.x, lostAt.z - f.z);
            check.out(String.format(Locale.ROOT, "     it takes the flanker for being %.1f blocks off where they are", off));
            check.check("hit from the flank: it takes the target it lost for the shooter, there", lostAt != null && off < 4);
            next(Phase.HOLD);
        }
    }

    private void hold() {
        TargetMemory.Contact c = bot.memory.contact(target.getId());
        if (c != null && c.state == TargetMemory.State.LOST) lostAt = new Vec3(c.lostX, c.lostY, c.lostZ);
        if (lostAt == null && ticks < 40) return;
        // once it goes searching, a moment for it to set off; or, searching or not, well after its wait is over (a
        // reload in cover or a look out that ends late puts the search off a little)
        if (searchSince < 0 && mind.mode() == AiSoldier.Mode.SEARCH) {
            searchSince = ticks;
            searchFrom = soldier.position();
        }
        if (lostAt == null || searchSince >= 0 && ticks - searchSince >= 40 || ticks > AiProfile.SOLDIER.hold() + 120) {
            if (lostAt == null) {
                AiThoughts.log = false;
                check.check("loses the target it cannot see any more", false);
                soldier.discard();
                target.discard();
                next(Phase.DONE);
                return;
            }
            double moved = soldier.position().distanceTo(holdStart);
            Vec3 from = searchFrom != null ? searchFrom : holdStart;
            double before = Math.hypot(from.x - lostAt.x, from.z - lostAt.z), now = Math.hypot(soldier.getX() - lostAt.x, soldier.getZ() - lostAt.z);
            check.out(String.format(Locale.ROOT, "     target gone: lost at %.1f %.1f; %d ticks on it is %s, moved %.1f blocks, %.1f from that place (was %.1f)",
                    lostAt.x, lostAt.z, c == null ? -1 : level.getGameTime() - c.lostTick, mind.mode(), moved, now, before));
            // it looks out for them once, then goes for where they were last seen (AiSoldier.PUSH_MIN..PUSH_MAX)
            verdict("out of sight a few seconds after it looked, it goes for where the target was last seen", c != null
                    && mind.mode() == AiSoldier.Mode.SEARCH && now < before - 1);
            AiThoughts.log = false;
            next(Phase.SEARCH);
        }
    }

    private void search() {
        Vec3 here = soldier.position();
        nearestToLost = Math.min(nearestToLost, Math.hypot(here.x - lostAt.x, here.z - lostAt.z));
        nearestToBox = Math.min(nearestToBox, Math.hypot(here.x - BOX.x, here.z - BOX.z));
        if (mind.mode() == AiSoldier.Mode.SEARCH && mind.searchCell() != Long.MIN_VALUE) {
            long g = mind.searchCell();
            if (firstSearch == Long.MIN_VALUE) firstSearch = g;
            TargetMemory.Contact c = bot.memory.contact(target.getId());
            double r = c == null ? 32 : bot.memory.searchRadius(c, level.getGameTime()) + 1;
            searchedOffLost |= Math.hypot(BlockPos.getX(g) + 0.5 - lostAt.x, BlockPos.getZ(g) + 0.5 - lostAt.z) > r;
        }
        if (ticks == 300) {
            double start = Math.hypot(holdStart.x - lostAt.x, holdStart.z - lostAt.z);
            check.out(String.format(Locale.ROOT, "     searching: %d spots, first %s; came from %.1f to %.1f blocks of where it was lost; nearest to the target's box %.1f",
                    mind.searchSpots, firstSearch == Long.MIN_VALUE ? "-" : BlockPos.of(firstSearch).toShortString(), start, nearestToLost, nearestToBox));
            verdict("then searches round where it was lost, carefully", mind.searchSpots > 0 && nearestToLost < start - 2 && !searchedOffLost);
            check.check("and not where the target really is", nearestToBox > 12);
            soldier.discard();
            target.discard();
            next(Phase.DONE);
        }
    }
}
