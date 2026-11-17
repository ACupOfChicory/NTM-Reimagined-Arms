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
    private final int stackMoved0 = AiEntry.stackMoved, stackSeen0 = AiEntry.stackSeen;
    private final int goes0 = AiEntry.goes;
    private final long goTicks0 = AiEntry.goTicks;
    private final int primed0 = AiEntry.primedNoticed;
    private int round = -1, ticks;
    private final List<Mob> squad = new ArrayList<>(), held = new ArrayList<>();
    private final boolean[] inShaft = new boolean[SQUAD], inRoom = new boolean[SQUAD], fell = new boolean[SQUAD], atDoor = new boolean[SQUAD];
    private int byDeep0, byHeld0, byKept0, begun0, down0, firstIn;
    private int[] none0 = new int[2];
    /** AiEntry's counts as the round began: entries, grenades in, no throw, gone in unstacked, no throw planned; its why */
    private int[] entry0 = new int[5], entryWhy0 = new int[4];
    /** how each of the squad came into the room: its mode and why then */
    private final String[] cameIn = new String[SQUAD];
    /** of the squad fallen in the passage by the room's way in this round; AiHatch's hand-overs to AiEntry as it began */
    private int fellAtDoor, bypassEntry0, matesDown0, slices0;
    private final int held0 = AiEntry.held, heldOut0 = AiEntry.heldOut;
    /** the two below: when each was put down this round (-1 not), and by what (the first is the one the squad knows of) */
    private final int[] heldDown = new int[2];
    private final String[] heldBy = new String[2];
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
                    if (byWayIn(m)) fellAtDoor++;
                    // (who shot it, where it was, and whether it knew of that one from one of its own shot down before)
                    var by = m.getLastDamageSource();
                    var who = by == null ? null : by.getEntity();
                    AiBot db = AiBots.get(m);
                    TargetMemory.Contact kc = db == null || who == null ? null : db.memory.contact(who.getId());
                    check.out(String.format(Locale.ROOT, "     bypass %d, fell at %d: %.1f %.1f %.1f %s%s, by %s %s; it knew of it %s",
                            round + 1, ticks, m.getX(), m.getY(), m.getZ(), db != null && db.brain instanceof AiSoldier so ? so.mode() + "/" + so.why : "?",
                            byWayIn(m) ? " (by the way in)" : "", by == null ? "?" : by.type().msgId(),
                            who == null ? "-" : String.format(Locale.ROOT, "%s at %.1f %.1f", held.contains(who) ? "one below" : squad.contains(who) ? "its own" : "another", who.getX(), who.getZ()),
                            kc == null ? "not" : kc.state + (kc.mateDown != Long.MIN_VALUE ? " (told " + (level.getGameTime() - kc.mateDown) + " ticks before)" : "")));
                }
                continue;
            }
            if (col && m.getY() < top() - 1 && m.getY() > floor() + 1.5) inShaft[i] = true;
            // down the drift to the passage at its foot, by the room's way in (the way round gone; going in is a room's fight)
            if (m.getY() < floor() + 3.5 && m.getZ() >= SZ + RZ + 0.5 && m.getX() >= DOOR_X - 0.5 && m.getX() <= DRIFT_X + 0.5) atDoor[i] = true;
            if (!inRoom[i] && m.getY() < floor() + 3.5 && Math.abs(m.getX() - SX - 0.5) <= RX + 1 && m.getZ() >= SZ - 0.5 && m.getZ() <= SZ + RZ + 1.5) {
                inRoom[i] = true;
                if (firstIn < 0) {
                    firstIn = ticks;
                    cornerAtIn = corner();
                }
                AiBot b = AiBots.get(m);
                cameIn[i] = ticks + " " + (b != null && b.brain instanceof AiSoldier so ? so.mode() + "/" + so.why : "?");
            }
        }
        for (int i = 0; i < held.size(); i++)
            if (heldDown[i] < 0 && !held.get(i).isAlive()) {
                heldDown[i] = ticks;
                var by = held.get(i).getLastDamageSource();
                heldBy[i] = by == null ? "?" : by.type().msgId() + (by.getDirectEntity() == null ? "" : " " + by.getDirectEntity().getType().toShortString());
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

    /** as the first of the squad comes in: the one below it is not told of 0 down, 1 known of by one of the squad, 2 not */
    private int cornerAtIn = -1;

    private int corner() {
        // (the first of the two is the one the squad is told of; the other, the one it does not know of)
        Mob far = held.size() > 1 ? held.get(1) : null;
        if (far == null || !far.isAlive()) return 0;
        for (Mob m : squad) {
            AiBot b = AiBots.get(m);
            TargetMemory.Contact c = b == null || !m.isAlive() ? null : b.memory.contact(far.getId());
            if (c != null && c.known()) return 1;
        }
        return 2;
    }

    /** this near the room's way in a fall inside is one going in by it, blocks (its rows by the wall it is in, not half the one next to it) */
    static final double WAY_IN_NEAR = 3;

    /** in the passage from the drift's foot, the way in itself, or in the room by it */
    private boolean byWayIn(Mob m) {
        if (m.getY() >= floor() + 3.5) return false;
        if (m.getZ() >= SZ + RZ + 1 && m.getX() >= DOOR_X - 0.5 && m.getX() <= DRIFT_X + 0.5) return true;
        return m.getZ() >= SZ - 0.5 && Math.hypot(m.getX() - DOOR_X - 0.5, m.getZ() - SZ - RZ - 1.5) <= WAY_IN_NEAR;
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
        // the room built (stone bricks round it, floor and roof): a dug-out in bare stone is a cave to AiRooms, no
        // building, and none goes into it by AiEntry's drill
        BlockState bricks = Blocks.STONE_BRICKS.defaultBlockState();
        for (int x = SX - RX - 1; x <= SX + RX + 1; x++)
            for (int z = SZ - 1; z <= SZ + RZ + 1; z++)
                for (int yy = floor(); yy <= floor() + 4; yy++) level.setBlock(p.set(x, yy, z), bricks, Block.UPDATE_CLIENTS);
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
        for (int i = 0; i < SQUAD; i++) {
            inShaft[i] = inRoom[i] = fell[i] = atDoor[i] = false;
            cameIn[i] = "-";
        }
        entry0 = new int[] {AiEntry.entries, AiEntry.frags, AiEntry.noThrow, AiEntry.unstacked, AiEntry.noPlan};
        entryWhy0 = AiEntry.why.clone();
        AiEntry.fates.clear();
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
        results.add(new int[20]);
        cornerAtIn = -1;
        slices0 = AiEntry.slices;
        fellAtDoor = 0;
        matesDown0 = AiBots.matesDownHeard;
        heldDown[0] = heldDown[1] = -1;
        heldBy[0] = heldBy[1] = "-";
        bypassEntry0 = AiHatch.bypassEntry;
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
        res[12] = AiEntry.entries - entry0[0];
        res[13] = AiEntry.frags - entry0[1];
        res[14] = fellAtDoor;
        res[15] = AiHatch.bypassEntry - bypassEntry0;
        // the one the squad knows of shot down by it from the way in before a grenade went in (the entry is over with it)
        res[17] = AiBots.matesDownHeard - matesDown0;
        res[18] = cornerAtIn;
        res[19] = AiEntry.slices - slices0;
        res[16] = heldDown[0] >= 0 && heldBy[0].contains("bullet") && AiEntry.frags - entry0[1] == 0 ? 1 : 0;
        check.out(String.format(Locale.ROOT, "     bypass %d (%s): %d ticks; of the squad %d of %d left, down the shaft %d, down the drift to the room's way in %d, in by the drift %d, fell in the shaft %d, the first in at %d;"
                + " the two below %d left; ways round found %d (at the deep shaft %d, held %d), none (no way %d, too long %d), begins put off %d; ways down begun %d, down the ladder %d;%s",
                round + 1, new String[] {"on, drift open", "on, drift shut", "off, drift open"}[arm()], ticks, left, SQUAD, shaft, door, drift, res[4], firstIn, res[9], res[5], AiHatch.bypassDeep - byDeep0,
                AiHatch.bypassHeld - byHeld0, res[6], res[7], AiHatch.bypassKept - byKept0, AiHatch.begun - begun0, AiHatch.down - down0, where()));
        // (the room's assault from the drift: did AiEntry take it, and what AiRooms read there)
        StringBuilder rooms = new StringBuilder();
        AiRooms.Plan plan = AiTrenches.place(TacticalMap.of(level), BlockPos.asLong(SX + 1, floor() + 1, SZ + 2));
        if (plan == null) rooms.append(" no plan");
        else {
            int room = plan.room(BlockPos.asLong(SX + 1, floor() + 1, SZ + 2));
            rooms.append(" room ").append(room);
            if (room != AiRooms.OUTSIDE)
                for (AiRooms.Way w : plan.waysOf(room))
                    rooms.append(String.format(Locale.ROOT, "; a way %d wide, %d cells from %s, beside it out %d, in %d, to room %d", w.width, w.cells.length,
                            w.cells.length == 0 ? "-" : BlockPos.of(w.cells[0]).toShortString(), w.beside(w.other(room)).length, w.beside(room).length, w.other(room)));
        }
        check.out(String.format(Locale.ROOT, "     bypass %d: entries %d, grenades in %d, no throw %d, unstacked %d, no throw planned %d, why %s, fates %s; came into the room %s; the two below down at %d by %s, %d by %s; AiRooms:%s",
                round + 1, AiEntry.entries - entry0[0], AiEntry.frags - entry0[1], AiEntry.noThrow - entry0[2], AiEntry.unstacked - entry0[3], AiEntry.noPlan - entry0[4],
                java.util.Arrays.toString(new int[] {AiEntry.why[0] - entryWhy0[0], AiEntry.why[1] - entryWhy0[1], AiEntry.why[2] - entryWhy0[2], AiEntry.why[3] - entryWhy0[3]}),
                AiEntry.fates, java.util.Arrays.toString(cameIn), heldDown[0], heldBy[0], heldDown[1], heldBy[1], rooms.length() > 120 ? rooms.substring(0, 120) : rooms));
        check.out("     bypass " + (round + 1) + ": the last way round looked for: " + AiHatch.bypassLast + "; looked for on a map not built yet, in all " + AiHatch.bypassMissing);
        AiHatch.bypassLast = "-";
        for (Mob m : squad) m.discard();
        for (Mob m : held) m.discard();
        for (var e : level.getEntitiesOfClass(Entity.class, new AABB(X0 - 10, floor() - 2, Z0 - PZ - 2, X0 + PX + 2, y + 30, Z0 + PZ + 2),
                e -> e instanceof com.hbm.entity.grenade.EntityGrenadeUniversal || e instanceof net.minecraft.world.entity.item.ItemEntity)) e.discard();
    }

    private boolean finish() {
        check.out(String.format(Locale.ROOT, "     bypass: places beside the way in moved out of sight of the room %d, left in it %d", AiEntry.stackMoved - stackMoved0, AiEntry.stackSeen - stackSeen0));
        check.out("     bypass: going in after its grenade, foes in there noticed while looking for them " + (AiEntry.primedNoticed - primed0));
        check.out(String.format(Locale.ROOT, "     bypass: entries all gone in %d, ticks from in to all in their places %.1f a time", AiEntry.goes - goes0, AiEntry.goes == goes0 ? 0.0 : (AiEntry.goTicks - goTicks0) / (double) (AiEntry.goes - goes0)));
        AiHatch.on = hatchOn;
        AiHatch.bypassOn = true;
        AiHatch.forget(level);
        // by arm: rounds, left, down the shaft, in by the drift, fell in the shaft, ways round found, rounds with one in by the drift, rounds with one down the shaft
        int[][] s = new int[3][24];
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
            a[11] += r[12] > 0 ? 1 : 0;
            a[12] += r[13] > 0 ? 1 : 0;
            a[13] += r[14];
            a[14] += r[11] > 1 ? 1 : 0;
            a[15] += r[15] > 0 ? 1 : 0;
            a[16] += r[16];
            a[17] += r[3] > 0 || r[9] == 0 ? 1 : 0;
            a[18] = Math.max(a[18], r[14]);
            a[19] += r[17];
            if (r[18] >= 0) a[20 + r[18]]++;
            a[23] += r[19] > 0 ? 1 : 0;
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
            // the room taken from the drift: handed over to AiEntry, a grenade in, in by it, none more by the way in
            check.out(String.format(Locale.ROOT, "     bypass: on, drift open: handed over to the entry in %d rounds, entries in %d, a grenade in in %d; fallen by the way in %d (at most %d a round); rounds with a way down begun again %d; told of a foe by one of theirs falling %d; entries held by the way in %d (in after their time %d); rounds sliced %d; as the first went in, the one in the corner down %d, known of %d, not %d",
                    on[15], on[11], on[12], on[13], on[18], on[14], on[19], AiEntry.held - held0, AiEntry.heldOut - heldOut0, on[23], on[20], on[21], on[22]));
            // (a grenade in, or the one it knows of shot down from the way in first: then it knows of none in the room, and the
            // entry is over; the other one there is a room cleared of foes not known of, AiClearing's, not the way in's)
            check.check(String.format(Locale.ROOT, "bypass: at the room's way in from the drift, its entry, a grenade in or the foe known of shot down first, in every round (handed over %d, entries %d, grenades %d, shot first %d of %d)",
                    on[15], on[11], on[12], on[16], on[0]), on[11] == on[0] && on[12] + on[16] == on[0]);
            // (the foe known of shot down from the way in first: the room fought in is gone into after all, AiBots.fought)
            // (or the room taken from its way in: the two below both down, the last by the grenade in, the round over before any went in)
            check.check(String.format(Locale.ROOT, "bypass: the squad comes into the room by the drift, or puts the two below down from its way in, in every round (%d of %d; in it %d; the foe known of shot down first in %d)",
                    on[17], on[0], on[6], on[16]), on[17] == on[0]);
            // (by the way in: the passage, the opening, or the room within WAY_IN_NEAR of it: the ones shot going in)
            // (one a round on the whole: the first in may meet one in there none knew of; a duel lost after to a foe lying in
            // wait for it is no fault of theirs, walking in one after another to it is)
            check.check(String.format(Locale.ROOT, "bypass: of the squad at most one a round fallen by the way in (%d in %d rounds, at most %d in one)", on[13], on[0], on[18]), on[13] <= on[0]);
            check.check(String.format(Locale.ROOT, "bypass: the way round over by the room, no way down the shaft begun again (%d rounds)", on[14]), on[14] == 0);
        }
        return true;
    }
}
