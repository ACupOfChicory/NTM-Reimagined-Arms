package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The AI check's way round a shaft (AiHatch's bypass): a stone plain walled round, under it a room SHAFT blocks down (too
 * deep for a frag dropped down), its ways in a ladder up a shaft to an oak trapdoor and a stair drift, a block up a
 * block, up to the ground some seventy blocks off, its foot round a corner from the room. Two of a team hold the shaft's foot; a squad
 * of four of another on the ground by the hatch, knowing of one of them there. Rounds by threes: the drill on with the
 * drift open, on with it shut (a block at its foot: no way round), off (the way round not looked for) with it open.
 * Counted: who of the squad went down the shaft, who came in by the drift, who fell in the shaft, ways round found.
 */
final class AiCheckBypass {
    static final int X0 = 4600, Z0 = 0, SX = 4610, SZ = 0, SHAFT = 64, RUN = 2400, ROUNDS = 6, SQUAD = 4;
    /** the room: x SX-RX..SX+RX, z SZ..SZ+RZ, three high; the plot walled round: x X0-8..X0+PX, z Z0-PZ..Z0+PZ */
    static final int RX = 3, RZ = 6, PX = 80, PZ = 8;
    /**
     * the drift down this row south of the room, its foot DRIFT_X; from there a passage west along the row to the
     * room's south wall and in through it at DOOR_X: round a corner from the shaft's foot (a drift straight into the room
     * was a lane of fire up it from those below, and none came through)
     */
    static final int DRIFT_Z = SZ + RZ + 2, DRIFT_X = SX + RX + 2, DOOR_X = SX + 2;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private final boolean hatchOn = AiHatch.on;
    private boolean started;
    private int round = -1, ticks;
    private final List<Mob> squad = new ArrayList<>(), held = new ArrayList<>();
    private final boolean[] inShaft = new boolean[SQUAD], inRoom = new boolean[SQUAD], fell = new boolean[SQUAD], atDoor = new boolean[SQUAD];
    private int byDeep0, byHeld0, byKept0, begun0, down0, firstIn;
    private int[] none0 = new int[2];
    /** per round: {arm (0 on open, 1 on shut, 2 off), left, in the shaft, in by the drift, fell in the shaft, ways round found, none (no way), none (too long), the first in at, the two below left} */
    private final List<int[]> results = new ArrayList<>();

    AiCheckBypass(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    private int top() {
        return y - 1;
    }

    private int floor() {
        return top() - SHAFT;
    }

    private int arm() {
        return round % 3;
    }

    boolean tick() {
        if (!started) {
            started = true;
            for (int cx = (X0 - 10) >> 4; cx <= (X0 + PX + 2) >> 4; cx++)
                for (int cz = (Z0 - PZ - 2) >> 4; cz <= (Z0 + PZ + 2) >> 4; cz++) level.setChunkForced(cx, cz, true);
            AiBots.awake = true;
            build();
            start(0);
            return false;
        }
        ticks++;
        for (int i = 0; i < squad.size(); i++) {
            Mob m = squad.get(i);
            boolean col = Math.abs(m.getX() - SX - 0.5) < 0.8 && Math.abs(m.getZ() - SZ - 0.5) < 0.8;
            if (!m.isAlive()) {
                if (!fell[i]) {
                    fell[i] = true;
                    if (col && m.getY() < top() - 1 && m.getY() > floor() + 2) results.get(results.size() - 1)[4]++;
                }
                continue;
            }
            if (col && m.getY() < top() - 1 && m.getY() > floor() + 1.5) inShaft[i] = true;
            // down the drift to the passage at its foot, by the room's way in (the way round gone; going in is a room's fight)
            if (m.getY() < floor() + 3.5 && m.getZ() >= SZ + RZ + 0.5 && m.getX() >= DOOR_X - 0.5 && m.getX() <= DRIFT_X + 0.5) atDoor[i] = true;
            if (!inRoom[i] && m.getY() < floor() + 3.5 && Math.abs(m.getX() - SX - 0.5) <= RX + 1 && m.getZ() >= SZ - 0.5 && m.getZ() <= SZ + RZ + 1.5) {
                inRoom[i] = true;
                if (firstIn < 0) firstIn = ticks;
            }
        }
        if (ticks % 400 == 0) check.out(String.format(Locale.ROOT, "     bypass %d, tick %d:%s", round + 1, ticks, where()));
        boolean anyOut = squad.stream().anyMatch(Mob::isAlive), anyIn = held.stream().anyMatch(Mob::isAlive);
        if (ticks < RUN && anyOut && anyIn) return false;
        end();
        if (round + 1 < ROUNDS) {
            start(round + 1);
            return false;
        }
        return finish();
    }

    private String where() {
        StringBuilder w = new StringBuilder();
        for (Mob m : squad) {
            AiBot b = AiBots.get(m);
            w.append(String.format(Locale.ROOT, " %s %.1f %.1f %.1f %s;", m.isAlive() ? "up" : "down", m.getX(), m.getY(), m.getZ(),
                    b != null && b.brain instanceof AiSoldier so ? so.mode() + "/" + so.why : ""));
        }
        return w.toString();
    }

    private void build() {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = X0 - 10; x <= X0 + PX + 2; x++)
            for (int z = Z0 - PZ - 2; z <= Z0 + PZ + 2; z++) {
                boolean wall = x < X0 - 8 || x > X0 + PX || z < Z0 - PZ || z > Z0 + PZ;
                for (int yy = floor() - 2; yy <= top(); yy++) level.setBlock(p.set(x, yy, z), stone, Block.UPDATE_CLIENTS);
                // walled round, three high: no way off the plot (the ways round are its own)
                for (int yy = y; yy <= y + 8; yy++) level.setBlock(p.set(x, yy, z), wall && yy <= y + 2 ? stone : air, Block.UPDATE_CLIENTS);
            }
        for (int x = SX - RX; x <= SX + RX; x++)
            for (int z = SZ; z <= SZ + RZ; z++)
                for (int yy = floor() + 1; yy <= floor() + 3; yy++) level.setBlock(p.set(x, yy, z), air, Block.UPDATE_CLIENTS);
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH);
        for (int yy = floor() + 1; yy < top(); yy++) level.setBlock(p.set(SX, yy, SZ), ladder, Block.UPDATE_CLIENTS);
        level.setBlock(new BlockPos(SX, top(), SZ), Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, Direction.SOUTH).setValue(TrapDoorBlock.HALF, Half.TOP)
                .setValue(TrapDoorBlock.OPEN, false), Block.UPDATE_ALL);
        // the drift: from its foot a block up a block east to the ground, three high over each step
        for (int k = 0; k <= SHAFT; k++) {
            int x = DRIFT_X + k, feet = floor() + 1 + k;
            for (int yy = feet; yy <= feet + 2; yy++) level.setBlock(p.set(x, yy, DRIFT_Z), air, Block.UPDATE_CLIENTS);
        }
        // the passage to the room: west along the row, then north through its south wall
        for (int x = DOOR_X; x < DRIFT_X; x++)
            for (int yy = floor() + 1; yy <= floor() + 3; yy++) level.setBlock(p.set(x, yy, DRIFT_Z), air, Block.UPDATE_CLIENTS);
        for (int z = SZ + RZ + 1; z < DRIFT_Z; z++)
            for (int yy = floor() + 1; yy <= floor() + 3; yy++) level.setBlock(p.set(DOOR_X, yy, z), air, Block.UPDATE_CLIENTS);
    }

    /** the drift shut at its foot (a block across it by the room), or open */
    private void drift(boolean shut) {
        BlockState b = shut ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        for (int yy = floor() + 1; yy <= floor() + 3; yy++) level.setBlock(new BlockPos(DRIFT_X - 1, yy, DRIFT_Z), b, Block.UPDATE_CLIENTS);
    }

    private void start(int r) {
        round = r;
        ticks = 0;
        firstIn = -1;
        for (int i = 0; i < SQUAD; i++) inShaft[i] = inRoom[i] = fell[i] = atDoor[i] = false;
        AiCheck.clearShots(level);
        if (r > 0) build();
        drift(arm() == 1);
        AiHatch.on = hatchOn;
        AiHatch.bypassOn = arm() != 2;
        AiHatch.forget(level);
        byDeep0 = AiHatch.bypassDeep;
        byHeld0 = AiHatch.bypassHeld;
        byKept0 = AiHatch.bypassKept;
        none0 = AiHatch.bypassNone.clone();
        begun0 = AiHatch.begun;
        down0 = AiHatch.down;
        results.add(new int[12]);
        double fy = floor() + 1;
        held.clear();
        for (int i = 0; i < 2; i++) {
            Vec3 at = new Vec3(SX + (i == 0 ? 1.5 : -0.5), fy, SZ + 2.5);
            Mob m = AiSpawn.spawn(level, at, AiSpawn.Kind.DUMMY, 1, null).get(0);
            m.setPos(at.x, at.y, at.z);
            // they hold the foot (up the ladder after the squad they would be no holding of it)
            var speed = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(0);
            AiTeams.join(level, m, "bypass_in" + r);
            held.add(m);
        }
        squad.clear();
        for (int i = 0; i < SQUAD; i++) {
            Vec3 at = new Vec3(SX - 5.5, y, SZ + i * 2 - 2.5);
            Mob m = AiSpawn.spawn(level, at, AiSpawn.Kind.DUMMY, 1, null).get(0);
            m.setPos(at.x, at.y, at.z);
            AiTeams.join(level, m, "bypass_out" + r);
            AiBot b = AiBots.get(m);
            if (b != null) {
                b.grenades = Math.max(b.grenades, 1);
                b.smokes = 0;
            }
            squad.add(m);
        }
        AiBots.refreshTargets(level);
        long now = level.getGameTime();
        Mob foe = held.get(0);
        for (Mob m : squad) {
            AiBot b = AiBots.get(m);
            if (b != null) b.memory.seen(foe.getId(), foe.getX(), foe.getY(), foe.getZ(), now, 0);
        }
    }

    private void end() {
        int[] res = results.get(results.size() - 1);
        int left = (int) squad.stream().filter(Mob::isAlive).count();
        int shaft = 0, drift = 0, door = 0;
        for (int i = 0; i < SQUAD; i++) {
            if (atDoor[i]) door++;
            if (inShaft[i]) shaft++;
            if (inRoom[i] && !inShaft[i]) drift++;
        }
        res[0] = arm();
        res[1] = left;
        res[2] = shaft;
        res[3] = drift;
        res[5] = AiHatch.bypassDeep - byDeep0 + AiHatch.bypassHeld - byHeld0;
        res[6] = AiHatch.bypassNone[0] - none0[0];
        res[7] = AiHatch.bypassNone[1] - none0[1];
        res[8] = firstIn;
        res[9] = (int) held.stream().filter(Mob::isAlive).count();
        res[10] = door;
        res[11] = AiHatch.begun - begun0;
        check.out(String.format(Locale.ROOT, "     bypass %d (%s): %d ticks; of the squad %d of %d left, down the shaft %d, down the drift to the room's way in %d, in by the drift %d, fell in the shaft %d, the first in at %d;"
                + " the two below %d left; ways round found %d (at the deep shaft %d, held %d), none (no way %d, too long %d), begins put off %d; ways down begun %d, down the ladder %d;%s",
                round + 1, new String[] {"on, drift open", "on, drift shut", "off, drift open"}[arm()], ticks, left, SQUAD, shaft, door, drift, res[4], firstIn, res[9], res[5], AiHatch.bypassDeep - byDeep0,
                AiHatch.bypassHeld - byHeld0, res[6], res[7], AiHatch.bypassKept - byKept0, AiHatch.begun - begun0, AiHatch.down - down0, where()));
        check.out("     bypass " + (round + 1) + ": the last way round looked for: " + AiHatch.bypassLast + "; looked for on a map not built yet, in all " + AiHatch.bypassMissing);
        AiHatch.bypassLast = "-";
        for (Mob m : squad) m.discard();
        for (Mob m : held) m.discard();
        for (var e : level.getEntitiesOfClass(Entity.class, new AABB(X0 - 10, floor() - 2, Z0 - PZ - 2, X0 + PX + 2, y + 30, Z0 + PZ + 2),
                e -> e instanceof com.hbm.entity.grenade.EntityGrenadeUniversal || e instanceof net.minecraft.world.entity.item.ItemEntity)) e.discard();
    }

    private boolean finish() {
        AiHatch.on = hatchOn;
        AiHatch.bypassOn = true;
        AiHatch.forget(level);
        // by arm: rounds, left, down the shaft, in by the drift, fell in the shaft, ways round found, rounds with one in by the drift, rounds with one down the shaft
        int[][] s = new int[3][11];
        for (int[] r : results) {
            int[] a = s[r[0]];
            a[0]++;
            a[1] += r[1];
            a[2] += r[2];
            a[3] += r[3];
            a[4] += r[4];
            a[5] += r[5] > 0 ? 1 : 0;
            a[6] += r[3] > 0 ? 1 : 0;
            a[7] += r[2] > 0 ? 1 : 0;
            a[8] += r[10] > 0 ? 1 : 0;
            a[9] += r[11] > 0 ? 1 : 0;
            a[10] += r[6] > 0 ? 1 : 0;
        }
        String[] names = {"on, drift open", "on, drift shut", "off, drift open"};
        for (int k = 0; k < 3; k++)
            check.out(String.format(Locale.ROOT, "     bypass: %s: %d rounds, of the squad left %d, down the shaft %d, in by the drift %d, fell in the shaft %d; rounds with a way round found %d, with one down the drift to the room's way in %d, with one in by the drift %d, with one down the shaft %d",
                    names[k], s[k][0], s[k][1], s[k][2], s[k][3], s[k][4], s[k][5], s[k][8], s[k][6], s[k][7]));
        if (hatchOn) {
            int[] on = s[0], shut = s[1];
            check.check(String.format(Locale.ROOT, "bypass: the shaft too deep and held, a way round found in every round with one (%d of %d)", on[5], on[0]), on[5] == on[0]);
            check.check(String.format(Locale.ROOT, "bypass: with a way round, none of the squad goes down the shaft (%d)", on[2]), on[2] == 0);
            // (to the room's way in by the drift: going in after that is the fight for a room, AiEntry's, not the way round's)
            check.check(String.format(Locale.ROOT, "bypass: with a way round, the squad goes round by it to the room's way in, in every round (%d of %d; in the room %d)", on[8], on[0], on[6]),
                    on[8] == on[0]);
            // (and looked for: a way down begun, no way round, in every such round)
            check.check(String.format(Locale.ROOT, "bypass: with the drift shut, no way round found, looked for in every round (found %d rounds of %d; a way down begun %d, none found %d)", shut[5], shut[0],
                    shut[9], shut[10]), shut[5] == 0 && shut[9] == shut[0] && shut[10] == shut[0]);
            int[] off = s[2];
            check.check(String.format(Locale.ROOT, "bypass: with no way round looked for, the squad goes down the shaft in every round (%d of %d)", off[7], off[0]), off[7] == off[0]);
        }
        return true;
    }
}
