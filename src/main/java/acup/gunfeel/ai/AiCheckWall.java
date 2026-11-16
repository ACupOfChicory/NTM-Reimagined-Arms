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
 * The AI check's house with no way in (the owner's woodland mansion: a squad sent by its staff pressed itself to its
 * wall and stood there): on flat ground a house of full blocks, BOX a side, H high, roofed; a squad of four FBI of a
 * side 24 blocks south of it, with an order to an area (as its staff gives one, set by the stand; AiCommander). Three
 * places: the area's middle in the shut house (its top the sky sees is the roof), behind it (the way round it), and in
 * the same house with a doorway on its far side (reached: a place in a yard with a gate stays one to go to). Rounds by
 * twos: before (a part of a way that brings it no nearer costs no try; the place taken as the sky's top: as X1284) and
 * now. Counted: each one's longest stand at the wall (within NEAR of it, moving less than a block in STILL ticks), the
 * squad's ticks at it in all (going round and round it: the owner's "pressed to the wall"),
 * whether the squad gave the place up and when, whether one got to the area.
 */
final class AiCheckWall {
    static final int X0 = 5184, Z0 = -32, SIZE = 104, RUN = 1600, SQUAD = 4;
    /** the house: its corner, side and height */
    static final int BX = 5226, BZ = 12, BOX = 11, H = 6;
    /** the squad's start */
    static final double SX = 5231.5, SZ = 47.5;
    /** at the wall: this near its outer face; standing: moved less than a block in this many ticks */
    static final double NEAR = 2.5;
    static final int STILL = 20;
    /** the area's radius */
    static final double R = 8;
    static final String[] PLACES = {"in the shut house (on its roof)", "behind the house", "in the house through a doorway on its far side"};

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int round = -1, ticks, gaveUp, inArea;
    private final List<Mob> squad = new ArrayList<>();
    private final AiCommander.Order[] orders = new AiCommander.Order[SQUAD];
    private final int[] stand = new int[SQUAD], longest = new int[SQUAD];
    /** the squad's ticks at the wall in all (standing or going round it) */
    private int atWallTicks;
    private final Vec3[] still = new Vec3[SQUAD];
    private final int[] stillAt = new int[SQUAD];
    /** per round: {place, now, longest stand at the wall, gave up at (-1: not), first in the area at (-1: not)} */
    private final List<int[]> rounds = new ArrayList<>();

    AiCheckWall(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    private static boolean now(int r) {
        return r % 2 == 1;
    }

    private static int place(int r) {
        return r / 2;
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
        ticks++;
        AiCommander.on = true;
        AiCommander.tasks = false;
        long t = level.getGameTime();
        for (int i = 0; i < squad.size(); i++) {
            Mob m = squad.get(i);
            AiBot b = AiBots.get(m);
            if (b == null || !m.isAlive()) continue;
            // given up (AiCommander.giveUp): counted once, not ordered again
            if (b.orderFailed != null && b.orderFailedUntil > t) {
                if (gaveUp < 0) {
                    gaveUp = ticks;
                    AiCommander.Order g = orders[i];
                    check.out(String.format(Locale.ROOT, "     wall, round %d: %d gave up at tick %d at %.1f %.1f: fails %d, legs with no way made %d, ticks in the area %d, nearest %.1f", round + 1, i, ticks,
                            m.getX(), m.getZ(), g.fails, g.idleLegs, g.inside, g.bestD));
                }
                continue;
            }
            // the staff's reserve has none: the stand's order kept (the same one, its state with it)
            if (b.order != orders[i]) b.order = orders[i];
            AiCommander.Order o = orders[i];
            if (inArea < 0 && Math.hypot(o.x - m.getX(), o.z - m.getZ()) <= R * 0.5 + 0.5 && m.getY() < y + 1.5) inArea = ticks;
            // at the wall and standing
            Vec3 p = m.position();
            if (still[i] == null || p.distanceTo(still[i]) >= 1) {
                still[i] = p;
                stillAt[i] = ticks;
            }
            boolean atWall = m.getX() > BX - NEAR && m.getX() < BX + BOX + NEAR && m.getZ() > BZ - NEAR && m.getZ() < BZ + BOX + NEAR
                    && !(m.getX() > BX + 1 && m.getX() < BX + BOX - 1 && m.getZ() > BZ + 1 && m.getZ() < BZ + BOX - 1);
            if (atWall) atWallTicks++;
            if (atWall && ticks - stillAt[i] >= STILL) stand[i]++;
            else stand[i] = 0;
            longest[i] = Math.max(longest[i], stand[i]);
        }
        if (ticks % 200 == 0) {
            StringBuilder w = new StringBuilder();
            for (Mob m : squad) {
                AiBot b = AiBots.get(m);
                if (b == null || !m.isAlive()) continue;
                w.append(String.format(Locale.ROOT, " %.1f %.1f %.1f %s/%s order %s mover %s;", m.getX(), m.getY() - y, m.getZ(),
                        b.brain instanceof AiSoldier so ? so.mode() : "-", b.brain instanceof AiSoldier so2 ? so2.why : "-", b.order == null ? "none" : b.order == orders[squad.indexOf(m)] ? "ours" : "other",
                        b.mover.state()));
            }
            check.out(String.format(Locale.ROOT, "     wall, round %d, tick %d:%s", round + 1, ticks, w));
        }
        if (ticks < RUN) return false;
        int most = 0;
        for (int l : longest) most = Math.max(most, l);
        rounds.add(new int[] {place(round), now(round) ? 1 : 0, most, gaveUp, inArea, atWallTicks});
        for (Mob m : squad) m.discard();
        squad.clear();
        if (round + 1 >= PLACES.length * 2) return finish();
        start(round + 1);
        return false;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private void start(int r) {
        round = r;
        ticks = 0;
        gaveUp = inArea = -1;
        atWallTicks = 0;
        AiMover.partTries = AiCommander.snap = AiCommander.unstuck = now(r);
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState(), wall = Blocks.STONE_BRICKS.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                set(x, y - 1, z, stone);
                for (int h = 0; h <= H + 2; h++) set(x, y + h, z, air);
            }
        // the house: walls H high, the roof over them
        for (int x = BX; x < BX + BOX; x++)
            for (int z = BZ; z < BZ + BOX; z++) {
                boolean edge = x == BX || x == BX + BOX - 1 || z == BZ || z == BZ + BOX - 1;
                for (int h = 0; h < H; h++) if (edge) set(x, y + h, z, wall);
                set(x, y + H, z, wall);
            }
        // a doorway two high in its north wall, the far side from the squad
        if (place(r) == 2) for (int h = 0; h < 2; h++) set(BX + BOX / 2, y + h, BZ, air);
        TacticalMap.of(level).want(BX + BOX / 2, y, BZ + BOX / 2, 32, 10);
        double ax = BX + BOX / 2 + 0.5, az = place(r) == 1 ? BZ - 12.5 : BZ + BOX / 2 + 0.5;
        List<Mob> made = AiSpawn.spawn(level, new Vec3(SX, y, SZ), AiSpawn.Kind.FBI, SQUAD, null);
        long t = level.getGameTime();
        for (int i = 0; i < made.size(); i++) {
            Mob m = made.get(i);
            m.setPos(SX + (i - 1.5) * 2, y, SZ);
            AiTeams.join(level, m, "wall_out" + r);
            squad.add(m);
            AiCommander.Order o = new AiCommander.Order();
            o.x = ax;
            o.z = az;
            o.r = R;
            o.until = t + RUN + 100;
            o.slot = i;
            o.of = SQUAD;
            orders[i] = o;
            stand[i] = longest[i] = 0;
            still[i] = null;
            AiBot b = AiBots.get(m);
            if (b != null) {
                b.gunner.ammo.setInfinite();
                b.order = o;
            }
        }
    }

    private boolean finish() {
        AiMover.partTries = AiCommander.snap = AiCommander.unstuck = AiCommander.tasks = true;
        for (Mob m : squad) m.discard();
        if (rounds.size() < PLACES.length * 2) {
            check.check("wall: all rounds ran", false);
            return true;
        }
        int[][] by = new int[PLACES.length * 2][];
        for (int[] r : rounds) {
            by[r[0] * 2 + r[1]] = r;
            check.out(String.format(Locale.ROOT, "     wall, the area %s, %s: longest stand at the wall %d ticks, the squad's ticks at it %d, the place given up %s, in the area %s", PLACES[r[0]],
                    r[1] == 1 ? "now" : "before", r[2], r[5], r[3] < 0 ? "never" : "at tick " + r[3], r[4] < 0 ? "never" : "at tick " + r[4]));
        }
        int[] shutNow = by[1], shutBefore = by[0], behind = by[3], door = by[5];
        check.check(String.format(Locale.ROOT, "wall: sent into a shut house, none stands at its wall long (%d ticks at most, %d before)", shutNow[2], shutBefore[2]), shutNow[2] <= 200);
        check.check(String.format(Locale.ROOT, "wall: sent into a shut house, the squad gives the place up (at tick %d, %d before)", shutNow[3], shutBefore[3]),
                shutNow[3] >= 0 && shutNow[3] <= RUN * 3 / 4 && (shutBefore[3] < 0 || shutNow[3] < shutBefore[3]));
        check.check(String.format(Locale.ROOT, "wall: sent into a shut house, the squad is at its wall less (%d ticks, %d before)", shutNow[5], shutBefore[5]), shutNow[5] < shutBefore[5]);
        check.check(String.format(Locale.ROOT, "wall: sent behind a house, it gets there round it (at tick %d, %d before)", behind[4], by[2][4]), behind[4] >= 0);
        check.check(String.format(Locale.ROOT, "wall: sent into a house with a doorway, it gets in (at tick %d, %d before)", door[4], by[4][4]), door[4] >= 0);
        return true;
    }
}
