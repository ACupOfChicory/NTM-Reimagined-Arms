package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import com.hbm.items.weapon.grenade.ItemGrenadeFilling.EnumGrenadeFilling;

import acup.gunfeel.ReaimTimes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.phys.Vec3;

/**
 * Into a building under the ground down a shaft (stage 6, after T8): a foe lost well under a squad, its way there down a
 * ladder. The squad gathers at the hatch; one of it stays by the hatch as its security (its base of fire if it has one)
 * the while the others go down, for on the ladder a soldier can do nothing; a grenade goes down first only when it
 * gets to the foot of the shaft before its fuse is out (in a deep shaft it bursts in the air), and none of its own is in
 * its reach unshielded; then they go down one after another, the next once the one before is SPACING rungs down (not
 * so far apart that the first is alone at the foot long), the last one down holding the shaft's way out till the next
 * is down (then it goes on: AiClearing, room after room); the security last. The foe is kept looked for the while (TargetMemory.keep). Server thread, from AiSoldier's search.
 */
final class AiHatch {
    /** how far up or down a ladder its top or foot is looked for, blocks */
    static final int LADDER_REACH = 12;

    /** a ladder's rung, or a trapdoor open over one (the game climbs both) */
    private static boolean rung(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.is(BlockTags.CLIMBABLE) || s.getBlock() instanceof TrapDoorBlock && s.getValue(TrapDoorBlock.OPEN) && level.getBlockState(p.below()).is(BlockTags.CLIMBABLE);
    }

    /**
     * Where one on a ladder steps off it: at its top a cell to stand in beside where the rungs end (out of a hatch on
     * to the ground), else, the way up shut, its foot. MIN_VALUE: none known (the map not read there yet)
     */
    static long offLadder(ServerLevel level, Mob mob) {
        TacticalMap map = TacticalMap.of(level);
        int x = mob.getBlockX(), z = mob.getBlockZ(), y0 = mob.getBlockY();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int top = y0;
        while (top < y0 + LADDER_REACH && rung(level, p.set(x, top, z))) top++;
        if (top > y0 || !rung(level, p.set(x, y0, z))) {
            if (walkable(map, x, top, z)) return BlockPos.asLong(x, top, z);
            int[][] side = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : side) if (walkable(map, x + d[0], top, z + d[1])) return BlockPos.asLong(x + d[0], top, z + d[1]);
        }
        for (int y = y0; y > y0 - LADDER_REACH && y > level.getMinY(); y--) {
            if (!rung(level, p.set(x, y - 1, z)) && walkable(map, x, y, z)) return BlockPos.asLong(x, y, z);
        }
        return Long.MIN_VALUE;
    }

    private static boolean walkable(TacticalMap map, int x, int y, int z) {
        SectionCells c = map.cellsAt(x, y, z);
        return c != null && c.find(x & 15, y & 15, z & 15) >= 0;
    }

    public static final String KEY = "aiHatches";
    /** off only for the stands' "before" */
    static boolean on = true;
    /** a foe this far under it at least, blocks; a ladder of this many rungs at least on the way */
    static final int DEEP = 4, RUNGS = 3;
    /** a side looks for a way down this often at most, ticks; again after one is given up this long after */
    static final int BEGIN_EVERY = 40, AGAIN = 400;
    /** its squad this near it goes with it, blocks; at the hatch: this near the rim; gathered this long at most, ticks */
    static final double NEAR = 40, RIM = 2.5, GATHERED = RIM;
    static final int GATHER_MAX = 400, FRAG_MAX = 200, OP_MAX = 9000;
    /** none of it thought of its way down this long (all of it fighting): given up, ticks */
    static final int STALE = 200;
    /** a foe lost this near the shaft's foot across is one down it, blocks */
    static final double FOE_NEAR = 32;
    /** a grenade down: it gets to the foot so many ticks before its fuse is out at least; thrown down at this share of a throw */
    static final int FUSE_LEFT = 10;
    /** the others wait this far off the shaft while it goes down, blocks */
    static final int BACK = 4;
    static final float DROP_FORCE = 0.4F;
    /** a soldier down the ladder this long at most (and so many ticks a rung more), ticks */
    static final int CLIMB_BASE = 200, CLIMB_RUNG = 12;
    /** the next one starts down once the one before is this many rungs under the top, blocks */
    static final int SPACING = 4;

    /** the stands: ways down begun, grenades down, none thrown (a deep shaft, its own in reach, none with one), soldiers down */
    public static int begun, frags, noFrag, deepNoFrag, down;
    /** the stands: looks for a way down that found none: the foe not under it, no cell, no way there, no shaft on it */
    public static final int[] none = new int[4];
    /** the stands: each look for a way down that found no way there, as words (trace on) */
    public static boolean trace;
    public static final List<String> noWay = new ArrayList<>();
    /** the stands: ticks the thrower waited at the rim for its own side out of the grenade's reach, ticks it was not at the rim */
    public static int unsafe, notAtRim;

    /** a shaft on a way: the cell before it (its rim), its top and foot rungs, the cell its way goes on to at its foot, the hatch over it */
    record Shaft(long rim, long top, long foot, long out, @Nullable BlockPos hatch, int rungs, long back) {
        Vec3 mid() {
            return new Vec3(BlockPos.getX(top) + 0.5, BlockPos.getY(top) + 1.5, BlockPos.getZ(top) + 0.5);
        }

        /** where the one holding its way out looks: on along that way */
        Vec3 onward() {
            int dx = BlockPos.getX(out) - BlockPos.getX(foot), dz = BlockPos.getZ(out) - BlockPos.getZ(foot);
            return new Vec3(BlockPos.getX(out) + 0.5 + dx * 4, BlockPos.getY(out) + 1.5, BlockPos.getZ(out) + 0.5 + dz * 4);
        }
    }

    private static final int GATHER = 0, FRAG = 1, DOWN = 2, OVER = 3;

    private static final class Op {
        final String team;
        final Shaft shaft;
        final List<AiBot> members;
        final @Nullable AiBot guard;
        final long made;
        /** when one of it last thought of it */
        long lastStep;
        int phase = GATHER, fragId = -1;
        long phaseAt;
        /** when each was sent down the ladder */
        final Map<AiBot, Long> sent = new HashMap<>();
        @Nullable AiBot thrower, holder;
        final Set<AiBot> downs = new HashSet<>();

        Op(String team, Shaft shaft, List<AiBot> members, @Nullable AiBot guard, long tick) {
            // (the others wait at shaft.back, BACK off the hatch: none stands over it when it is lifted)
            this.team = team;
            this.shaft = shaft;
            this.members = members;
            this.guard = guard;
            this.made = phaseAt = lastStep = tick;
        }

        List<AiBot> order() {
            List<AiBot> o = new ArrayList<>(members);
            o.remove(guard);
            return o;
        }
    }

    private static final class PerLevel {
        final Map<String, Op> ops = new HashMap<>();
        final Map<String, Long> next = new HashMap<>();
    }

    private static final Map<ServerLevel, PerLevel> LEVELS = new WeakHashMap<>();

    private AiHatch() {
    }

    static boolean on() {
        return on && ReaimTimes.flag(KEY, true);
    }

    public static void forget(ServerLevel level) {
        LEVELS.remove(level);
    }

    private static PerLevel of(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, k -> new PerLevel());
    }

    private static Vec3 mid(long cell, double up) {
        return new Vec3(BlockPos.getX(cell) + 0.5, BlockPos.getY(cell) + up, BlockPos.getZ(cell) + 0.5);
    }

    /** the first run of RUNGS rungs at least straight down on a path, with a cell before it: {the cell before, its top, its foot, the cell after (its foot if none)}, or null */
    static long @Nullable [] run(long[] cells) {
        for (int i = 1; i + 1 < cells.length; i++) {
            long a = cells[i], b = cells[i + 1];
            if (BlockPos.getX(a) != BlockPos.getX(b) || BlockPos.getZ(a) != BlockPos.getZ(b) || BlockPos.getY(b) != BlockPos.getY(a) - 1) continue;
            int j = i + 1;
            while (j + 1 < cells.length && BlockPos.getX(cells[j + 1]) == BlockPos.getX(a) && BlockPos.getZ(cells[j + 1]) == BlockPos.getZ(a)
                    && BlockPos.getY(cells[j + 1]) == BlockPos.getY(cells[j]) - 1) j++;
            if (j - i + 1 < RUNGS) {
                i = j;
                continue;
            }
            // the way on from its foot (none: the foe at the foot itself, the foot is the way out)
            return new long[] {cells[i - 1], a, cells[j], j + 1 < cells.length ? cells[j + 1] : cells[j]};
        }
        return null;
    }

    /** the first shaft on a path (run), its hatch: at its top cell (one shut is a floor walked onto) or over it, up to the rim's height */
    static @Nullable Shaft shaft(ServerLevel level, long[] cells) {
        long[] r = run(cells);
        if (r == null) return null;
        long rim = r[0], top = r[1];
        BlockPos hatch = null;
        for (int y = BlockPos.getY(top); y <= Math.max(BlockPos.getY(rim), BlockPos.getY(top) + 1); y++) {
            BlockPos p = new BlockPos(BlockPos.getX(top), y, BlockPos.getZ(top));
            if (level.getBlockState(p).getBlock() instanceof TrapDoorBlock) hatch = p;
        }
        // none down a hatch a hand cannot lift (an iron one), nor any while the AI opens no doors
        if (hatch != null && (!AiDoors.pass || !AiDoors.byHand(level.getBlockState(hatch)))) return null;
        return new Shaft(rim, top, r[2], r[3], hatch, BlockPos.getY(top) - BlockPos.getY(r[2]) + 1, back(level, rim, top));
    }

    /**
     * One of its side's way down sent down the ladder, not at its foot yet: on down to it whatever it sees, firing at
     * a foe in sight (on a ladder there is no cover to take, and one that stops is shot off it); at the foot it is down
     * (it holds the shaft's way out, AiHatch.step). Whether it was.
     */
    static boolean onLadder(AiSoldier s, AiBot bot, ServerLevel level, long tick) {
        if (!on()) return false;
        PerLevel l = LEVELS.get(level);
        Op op = l == null ? null : l.ops.get(AiSquads.team(bot));
        if (op == null || op.phase != DOWN || !climbing(op, bot, tick)) return false;
        Shaft sh = op.shaft;
        if (at(bot, sh.out, 1.0) || bot.mob.onGround() && bot.mob.getY() < BlockPos.getY(sh.foot) + 0.5) {
            down++;
            op.downs.add(bot);
            op.holder = bot;
            return false;
        }
        if (sh.hatch != null) AiDoors.open(level, bot.mob, sh.hatch);
        s.moveFiring(bot, sh.out, AiSoldier.RUN, sh.onward(), tick, "down the ladder of a shaft, one after another", AiThoughts.Why.HATCH, 0.6);
        return true;
    }

    /** sent down the ladder, not at its foot yet, not given up on */
    private static boolean climbing(Op op, AiBot b, long tick) {
        Long sent = op.sent.get(b);
        return sent != null && !op.downs.contains(b) && tick - sent <= CLIMB_BASE + CLIMB_RUNG * op.shaft.rungs;
    }

    private static boolean anyClimbing(Op op, long tick) {
        for (AiBot b : op.members) if (AiSquads.live(b) && climbing(op, b, tick)) return true;
        return false;
    }

    /** a foe lost out of sight down this shaft: under its top, this near its foot across */
    static boolean down(Shaft sh, TargetMemory.Contact k) {
        if (k.state == TargetMemory.State.VISIBLE) return false;
        double dx = k.lostX - BlockPos.getX(sh.foot) - 0.5, dz = k.lostZ - BlockPos.getZ(sh.foot) - 0.5;
        return k.lostY <= BlockPos.getY(sh.top) - DEEP && dx * dx + dz * dz <= FOE_NEAR * FOE_NEAR;
    }

    /** it looks for a foe down this shaft */
    private static boolean looksDown(AiBot b, Shaft sh) {
        for (TargetMemory.Contact k : b.memory.contacts()) if (down(sh, k)) return true;
        return false;
    }

    /** where the others wait while the grenade goes down: BACK blocks off the shaft out past its rim (the rim if no cell there) */
    private static long back(ServerLevel level, long rim, long top) {
        int dx = Integer.signum(BlockPos.getX(rim) - BlockPos.getX(top)), dz = Integer.signum(BlockPos.getZ(rim) - BlockPos.getZ(top));
        if (dx == 0 && dz == 0) return rim;
        TacticalMap map = TacticalMap.of(level);
        long c = PathSearch.snap(map, BlockPos.getX(top) + dx * BACK + 0.5, BlockPos.getY(rim), BlockPos.getZ(top) + dz * BACK + 0.5);
        return c == Long.MIN_VALUE ? rim : c;
    }

    /**
     * A soldier looking for a foe out of sight, this tick: where it looks when it is one of its side's going down a
     * shaft (it goes, waits, holds as its part is), or null (none: it searches as before).
     */
    static @Nullable Vec3 step(AiSoldier s, AiBot bot, ServerLevel level, TargetMemory.Contact c, long tick) {
        if (!on()) return null;
        PerLevel l = of(level);
        String team = AiSquads.team(bot);
        Op op = l.ops.get(team);
        if (op != null) {
            // over once none of it alive still looks for its foe (seen again: they fight; told it is down: forgotten),
            // or none of it thought of it a while (all of it in a fight)
            boolean live = false;
            // (one of it on the ladder now keeps it: on the way down it may see the foe and fight from there)
            for (AiBot b : op.members) if (AiSquads.live(b) && (looksDown(b, op.shaft) || climbing(op, b, tick))) live = true;
            if (op.phase == OVER || !live || tick - op.made > OP_MAX || tick - op.lastStep > STALE && !anyClimbing(op, tick)) {
                l.ops.remove(team);
                l.next.put(team, tick + (op.phase == OVER ? BEGIN_EVERY : AGAIN));
                op = null;
            }
        }
        if (op == null) {
            if (tick < l.next.getOrDefault(team, Long.MIN_VALUE) || (tick + bot.mob.getId()) % BEGIN_EVERY != 0) return null;
            l.next.put(team, tick + BEGIN_EVERY);
            op = begin(l, bot, level, team, c, tick);
            if (op == null) return null;
        }
        // another foe than one down its side's shaft: it searches as before
        if (!down(op.shaft, c)) return null;
        // one of its side come up while its side goes down: it goes with them
        if (!op.members.contains(bot) && op.phase != OVER && AiSquads.live(bot) && bot.mob.distanceToSqr(mid(op.shaft.rim, 0)) <= NEAR * NEAR && AiCrossing.of(bot) == null
                && bot.mob.getY() > BlockPos.getY(op.shaft.foot) + 1) op.members.add(bot);
        if (!op.members.contains(bot) || op.downs.contains(bot) && op.holder != bot) return null;
        op.lastStep = tick;
        bot.memory.keep(c, tick);
        try {
            return advance(op, s, bot, level, tick);
        } catch (RuntimeException e) {
            AiRooms.fault("going down a shaft", op.shaft.top, e);
            op.phase = OVER;
            return null;
        }
    }

    private static @Nullable Op begin(PerLevel l, AiBot bot, ServerLevel level, String team, TargetMemory.Contact c, long tick) {
        if (c.lostY > bot.mob.getY() - DEEP || AiWater.swims(bot.mob)) {
            none[0]++;
            return null;
        }
        TacticalMap map = TacticalMap.of(level);
        long start = PathSearch.snap(map, bot.mob.getX(), bot.mob.getY(), bot.mob.getZ()), goal = PathSearch.snap(map, c.lostX, c.lostY, c.lostZ);
        if (start == Long.MIN_VALUE || goal == Long.MIN_VALUE) {
            none[1]++;
            return null;
        }
        PathSearch.Result r = PathSearch.find(map, start, goal, 1.5, AiMover.MAX_NODES, PathSearch.Cost.NONE, true);
        if (!r.reached) {
            none[2]++;
            if (trace) {
                long end = r.cells.length == 0 ? start : r.cells[r.cells.length - 1];
                StringBuilder col = new StringBuilder();
                int gx = BlockPos.getX(goal), gz = BlockPos.getZ(goal);
                noWay.add(String.format(java.util.Locale.ROOT, "from %s to %s: got to %s (%d cells, %d expanded, exhausted %s, missing %d, nodes out %s)",
                        BlockPos.of(start).toShortString(), BlockPos.of(goal).toShortString(), BlockPos.of(end).toShortString(), r.cells.length, r.expanded, r.exhausted, r.missing.length,
                        r.expanded >= AiMover.MAX_NODES));
            }
            // the map not built yet on the way (a long shaft section after section): soon again; else not for a while
            if (r.missing.length > 0) AiMover.wantMissing(map, r);
            else l.next.put(team, tick + AGAIN);
            return null;
        }
        Shaft sh = shaft(level, r.cells);
        if (sh == null) {
            none[3]++;
            l.next.put(team, tick + AGAIN);
            return null;
        }
        // its side's near it (its squad and any other of its side here: one alone down the ladder would go down alone)
        List<AiBot> members = new ArrayList<>();
        // (those of them looking for the same foe: one fighting another is no part of it)
        for (AiBot b : AiBots.bots(level))
            if (AiSquads.live(b) && AiSquads.team(b).equals(team) && b.mob.distanceToSqr(bot.mob) <= NEAR * NEAR && AiCrossing.of(b) == null
                    && looksDown(b, sh)) members.add(b);
        if (!members.contains(bot)) members.add(bot);
        // the security at the hatch: its base of fire, else the last of it; none for one alone
        AiBot guard = null;
        if (members.size() > 1) {
            for (AiBot b : members) if (AiRole.of(b) == AiRole.BASE) guard = b;
            if (guard == null) guard = members.get(members.size() - 1);
        }
        Op op = new Op(team, sh, members, guard, tick);
        l.ops.put(team, op);
        begun++;
        if (bot.brain instanceof AiSoldier so) so.note(bot, tick, "a foe under the ground down a shaft: the squad to its hatch");
        return op;
    }

    private static void phase(Op op, int phase, long tick) {
        op.phase = phase;
        op.phaseAt = tick;
    }

    private static boolean at(AiBot b, long cell, double r) {
        Vec3 m = mid(cell, 0);
        double dx = b.mob.getX() - m.x, dz = b.mob.getZ() - m.z;
        return dx * dx + dz * dz <= r * r && Math.abs(b.mob.getY() - m.y) <= 1.2;
    }

    private static @Nullable Vec3 advance(Op op, AiSoldier s, AiBot bot, ServerLevel level, long tick) {
        Shaft sh = op.shaft;
        Vec3 hatch = sh.mid();
        switch (op.phase) {
            case GATHER -> {
                boolean all = true;
                for (AiBot b : op.members) if (AiSquads.live(b) && !at(b, sh.back, GATHERED)) all = false;
                if (all || tick - op.phaseAt > GATHER_MAX) {
                    phase(op, FRAG, tick);
                    op.thrower = thrower(op, level);
                }
                // gathered off the shaft, none over the hatch (lifted, it is a hole)
                s.moveFiring(bot, sh.back, AiSoldier.RUN, hatch, tick, "to the hatch of a shaft down to the foe", AiThoughts.Why.HATCH, RIM);
                return hatch;
            }
            case FRAG -> {
                AiBot t = op.thrower;
                Entity g = op.fragId < 0 ? null : level.getEntity(op.fragId);
                if (t == null || !AiSquads.live(t) || op.fragId >= 0 && (g == null || g.isRemoved()) || tick - op.phaseAt > FRAG_MAX + AiGrenades.FUSE) {
                    phase(op, DOWN, tick);
                    return hatch;
                }
                if (bot == t && op.fragId < 0) {
                    s.moveFiring(bot, sh.rim, AiSoldier.RUN, hatch, tick, "at the hatch, a grenade down the shaft", AiThoughts.Why.HATCH, 0.6);
                    boolean rimmed = at(bot, sh.rim, 0.8), safe = rimmed && safe(op, level, bot);
                    // the hatch lifted from its rim, none of its side on it
                    if (safe && sh.hatch != null && !over(op, level)) AiDoors.open(level, bot.mob, sh.hatch);
                    if (!rimmed) notAtRim++;
                    else if (!safe) unsafe++;
                    if (safe && (sh.hatch == null || level.getBlockState(sh.hatch).getValue(TrapDoorBlock.OPEN))) {
                        // dropped down the middle of the shaft from over it, sticking where it lands, bursting there
                        Vec3 burst = mid(sh.foot, 0.2);
                        AiGrenades.Throw down = new AiGrenades.Throw(bot.mob.getYRot(), 90F, burst, DROP_FORCE);
                        Entity thrown = s.threwInto(bot, level, down, EnumGrenadeFilling.HE, tick);
                        thrown.setPos(hatch.x, Math.max(hatch.y, thrown.getY()), hatch.z);
                        thrown.setOldPosAndRot();
                        op.fragId = thrown.getId();
                        frags++;
                    }
                    return hatch;
                }
                if (op.fragId < 0 && tick - op.phaseAt > FRAG_MAX) {
                    phase(op, DOWN, tick);
                }
                s.moveFiring(bot, sh.back, AiSoldier.RUN, hatch, tick, "off the hatch, its grenade going down", AiThoughts.Why.HATCH, RIM);
                return hatch;
            }
            case DOWN -> {
                List<AiBot> order = op.order();
                if (op.guard != null) order.add(op.guard);
                AiBot last = null;
                long lastAt = Long.MIN_VALUE;
                boolean all = true;
                for (AiBot b : order) {
                    Long sent = op.sent.get(b);
                    if (sent == null) {
                        if (AiSquads.live(b)) all = false;
                        continue;
                    }
                    if (sent > lastAt) {
                        lastAt = sent;
                        last = b;
                    }
                    if (op.downs.contains(b)) continue;
                    if (!AiSquads.live(b) || tick - sent > CLIMB_BASE + CLIMB_RUNG * sh.rungs) {
                        // fallen, or stuck on the way down: it goes on as it may
                        op.downs.add(b);
                        continue;
                    }
                    all = false;
                    if (at(b, sh.out, 1.0) || b.mob.onGround() && b.mob.getY() < BlockPos.getY(sh.foot) + 0.5) {
                        // at the foot: it holds the shaft's way out till the next is down, the one before goes on
                        down++;
                        op.downs.add(b);
                        op.holder = b;
                    }
                }
                // the next one down once the one before is SPACING rungs under the top (or down, or fallen)
                if (last == null || op.downs.contains(last) || last.mob.getY() <= BlockPos.getY(sh.top) + 1 - SPACING)
                    for (AiBot b : order)
                        if (!op.sent.containsKey(b) && AiSquads.live(b)) {
                            op.sent.put(b, tick);
                            break;
                        }
                if (all) {
                    phase(op, OVER, tick);
                    return null;
                }
                if (op.sent.containsKey(bot) && !op.downs.contains(bot)) {
                    if (sh.hatch != null) AiDoors.open(level, bot.mob, sh.hatch);
                    s.moveFiring(bot, sh.out, AiSoldier.RUN, sh.onward(), tick, "down the ladder of a shaft, one after another", AiThoughts.Why.HATCH, 0.6);
                    return sh.onward();
                }
                if (bot == op.holder) {
                    if (op.downs.contains(bot) && !AiSquads.live(bot)) return null;
                    s.moveFiring(bot, sh.out, AiSoldier.WALK, sh.onward(), tick, "at the foot of the shaft holding its way out till the next is down", AiThoughts.Why.HATCH, 0.6);
                    return sh.onward();
                }
                if (op.downs.contains(bot)) return null;
                // the others and the security up by the hatch, watching it
                s.moveFiring(bot, sh.back, AiSoldier.RUN, hatch, tick, bot == op.guard ? "by the hatch, its security while the others go down" : "by the hatch, waiting to go down",
                        AiThoughts.Why.HATCH, RIM);
                return hatch;
            }
            default -> {
                return null;
            }
        }
    }

    /** the one to drop a grenade down, or null: none down this shaft (too deep for the fuse, its own in reach, none with one) */
    private static @Nullable AiBot thrower(Op op, ServerLevel level) {
        Shaft sh = op.shaft;
        if (!AiGrenades.on || !AiEntry.frag) {
            noFrag++;
            return null;
        }
        double fall = BlockPos.getY(sh.top) + 1.5 - BlockPos.getY(sh.foot);
        if (fallTicks(fall) + FUSE_LEFT > AiGrenades.FUSE) {
            deepNoFrag++;
            noFrag++;
            return null;
        }
        AiBot best = null;
        double bd = Double.MAX_VALUE;
        for (AiBot b : op.members) {
            if (b == op.guard || !AiSquads.live(b) || b.grenades <= 0 || overColumn(op.shaft, b)) continue;
            double d = b.mob.distanceToSqr(mid(sh.rim, 0));
            if (d < bd) {
                bd = d;
                best = b;
            }
        }
        if (best == null) noFrag++;
        return best;
    }

    /**
     * None of its own side in the grenade's reach at the shaft's foot unshielded, now; the thrower aside: it is off
     * the rim BACK blocks long before the fuse is out (its grenade at the foot in a few ticks, bursting at FUSE)
     */
    private static boolean safe(Op op, ServerLevel level, AiBot thrower) {
        Vec3 burst = mid(op.shaft.foot, 0.2);
        VoxelRay.Source src = AiBots.voxels(level);
        double s2 = AiEntry.HE_SAFE * AiEntry.HE_SAFE;
        for (AiBot b : AiBots.bots(level))
            if (b != thrower && b.mob.isAlive() && AiTeams.own(b.mob, op.members.get(0).mob) && b.mob.distanceToSqr(burst) < s2
                    && !AiGrenades.shielded(src, b.mob.getX(), b.mob.getY(), b.mob.getZ(), b.mob.getBbHeight(), burst)) return false;
        for (var p : level.players())
            if (p.isAlive() && !p.isSpectator() && AiTeams.own(p, op.members.get(0).mob) && p.distanceToSqr(burst) < s2
                    && !AiGrenades.shielded(src, p.getX(), p.getY(), p.getZ(), p.getBbHeight(), burst)) return false;
        return true;
    }

    /** one of its side stands over the shaft (on its hatch shut): lifted, the hatch would drop it down */
    private static boolean over(Op op, ServerLevel level) {
        for (AiBot b : op.members) if (AiSquads.live(b) && overColumn(op.shaft, b)) return true;
        return false;
    }

    private static boolean overColumn(Shaft sh, AiBot b) {
        double x = BlockPos.getX(sh.top) + 0.5, z = BlockPos.getZ(sh.top) + 0.5;
        return Math.abs(b.mob.getX() - x) < 0.8 && Math.abs(b.mob.getZ() - z) < 0.8 && b.mob.getY() >= BlockPos.getY(sh.top);
    }

    /** ticks a grenade dropped down at DROP_FORCE takes to fall so far (the game's throwable: gravity, then drag) */
    static int fallTicks(double fall) {
        double y = 0, v = -DROP_FORCE * 1.5;
        for (int t = 1; t <= 400; t++) {
            y += v;
            v = v * AiGrenades.DRAG - AiGrenades.GRAVITY;
            if (-y >= fall) return t;
        }
        return Integer.MAX_VALUE / 2;
    }

    /** whether this is its side's grenade down a shaft and it out of the shaft: it does not run */
    static boolean keeps(ServerLevel level, AiBot bot, int grenade) {
        PerLevel l = LEVELS.get(level);
        if (l == null) return false;
        for (Op op : l.ops.values())
            if (op.fragId == grenade && op.phase != OVER) return bot.mob.getY() >= BlockPos.getY(op.shaft.top) + 1;
        return false;
    }

    /** its side's way down now, as words (the stands' traces) */
    static String describe(ServerLevel level, String team) {
        PerLevel l = LEVELS.get(level);
        Op op = l == null ? null : l.ops.get(team);
        if (op == null) return "none";
        return "phase " + op.phase + " members " + op.members.size() + " sent " + op.sent.size() + " downs " + op.downs.size() + " last " + (level.getGameTime() - op.lastStep) + " rim "
                + BlockPos.of(op.shaft.rim).toShortString() + " back " + BlockPos.of(op.shaft.back).toShortString() + " top " + BlockPos.of(op.shaft.top).toShortString() + " foot "
                + BlockPos.of(op.shaft.foot).toShortString();
    }

    /** whether it is going down a shaft with its side now (the stands) */
    static boolean going(AiBot bot) {
        if (!(bot.mob.level() instanceof ServerLevel level)) return false;
        PerLevel l = LEVELS.get(level);
        if (l == null) return false;
        long now = level.getGameTime();
        for (Op op : l.ops.values()) if (op.phase != OVER && now - op.lastStep <= STALE && op.members.contains(bot) && !op.downs.contains(bot)) return true;
        return false;
    }
}
