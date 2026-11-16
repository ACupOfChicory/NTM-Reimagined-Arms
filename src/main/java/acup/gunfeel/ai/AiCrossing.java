package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ReaimTimes;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * A river crossed under fire (stage 6, T7). A squad one of which has a way to a foe it knows of (one within
 * {@link #NEAR}) with {@link #SWIMS} cells or more to swim on it, or whose way was kept out of water it meant to swim
 * (under fire none is begun: AiMover.dry), or that knows of a foe out of its sight a while ({@link #OUT_OF_SIGHT})
 * across such water, crosses by waves: its base of fire, its marksman and a rifleman or two stay
 * on the near bank and fight from there; the first wave, half of it ({@link #WAVE} at most), gathers at the water's
 * edge, one of them throws a smoke grenade onto the far bank, and they swim over (fire or no fire) to places
 * {@link #HOLD_MIN} to {@link #HOLD_MAX} blocks past it and hold there; then the rest, the base of fire last. One
 * swimming who is shot at, nearer the bank it left, swims back and goes with the next wave. Till the squad is over,
 * none of it begins a swim but those sent. Kept by side and target, as the squads are made again every second.
 * Server thread.
 */
public final class AiCrossing {
    /** the server setting: squads cross a river under fire by waves, covered and behind smoke */
    public static final String KEY = "aiCrossing";
    /** off only for the stands' "before" */
    static boolean on = true;
    /** a foe this near, blocks; this many cells to swim on the way to it, at least; a squad this big, at least */
    static final double NEAR = 64;
    static final int SWIMS = 3, SQUAD = 3;
    /** the first wave: half the squad, this many at most; on the near bank stay this many riflemen besides the base and the marksman, at most */
    static final int WAVE = 4, RIFLES = 2;
    /** the places held past the far bank, blocks from it; and across, between one and the next */
    static final double HOLD_MIN = 6, HOLD_MAX = 10, ACROSS = 1.5;
    /**
     * ticks: the first wave's wait at the edge for the rest of it; for a smoke grenade to be thrown; for the cloud to
     * spread; the second wave's for its last but the base to get over; the crossing as a whole
     */
    static final int GATHER_MAX = 200, SMOKE_TRY = 80, SMOKE_SETTLE = 30, FIRST_MAX = 400, SECOND_MAX = 300, MAX = 2400;
    /**
     * a soldier looks for a river to cross on its way every this many ticks, for one between it and a foe out of its
     * sight every LOOK_FAR; a foe out of its sight this long, ticks, is gone after across one
     */
    static final int LOOK = 10, LOOK_FAR = 40, OUT_OF_SIGHT = 100;
    /** a dry way found over to a foe (a bridge, a ford) is not looked for again this long, ticks; the search's cells at most */
    static final int DRY_KEEP = 200, DRY_NODES = 3000;
    /** one sent over that cannot get to its place this many times is let be; a crossing none of whose soldiers came by this long, ticks, is let go */
    static final int FAILS = 3, STALE = 200;
    /** one joins its side's crossing to the foe only this near its near bank, blocks */
    static final double JOIN = 32;
    /** the smoke's burst this far past the far bank, blocks */
    static final double SMOKE_PAST = 2;

    enum Phase {
        GATHER, SMOKE, FIRST, SECOND, LAST
    }

    static final class Op {
        final String team;
        final int target;
        /** the near bank's cell, the far bank's; the way across, a unit vector; across it */
        final long near, far;
        final double ux, uz;
        final List<AiBot> members = new ArrayList<>(), cover = new ArrayList<>(), first = new ArrayList<>(), second = new ArrayList<>();
        /** sent over now, and where each goes; over and at its place (let go to fight as it will) */
        final Map<AiBot, Long> going = new HashMap<>();
        final java.util.Set<AiBot> over = new java.util.HashSet<>();
        /** its base of fire when the waves were made (last over, whatever the squads made since); those let be, stuck */
        final java.util.Set<AiBot> base = new java.util.HashSet<>(), stuck = new java.util.HashSet<>();
        final Map<AiBot, Integer> fails = new HashMap<>();
        /** the first wave's places at the edge */
        final Map<AiBot, Long> edge = new HashMap<>();
        Phase phase = Phase.GATHER;
        long made, phaseAt, last = Long.MIN_VALUE;
        boolean smoked;
        int holds;

        Op(String team, int target, long near, long far, double ux, double uz, long tick) {
            this.team = team;
            this.target = target;
            this.near = near;
            this.far = far;
            this.ux = ux;
            this.uz = uz;
            this.made = phaseAt = tick;
        }
    }

    private static final class PerLevel {
        final Map<String, Op> ops = new HashMap<>();
        final Int2ObjectOpenHashMap<Op> of = new Int2ObjectOpenHashMap<>();
        /** side:foe with a dry way over found, till when */
        final Map<String, Long> dryWay = new HashMap<>();
        long swept;
    }

    private static final Map<ServerLevel, PerLevel> LEVELS = new WeakHashMap<>();

    /** the stands: crossings begun, with smoke, first waves sent, second, those who swam back, ended over / not */
    public static int begun, smoked, firstSent, secondSent, back, over, notOver;

    private AiCrossing() {
    }

    static boolean on() {
        return on && ReaimTimes.flag(KEY, true);
    }

    /** crossings let go (the stands) */
    public static void forget(ServerLevel level) {
        LEVELS.remove(level);
    }

    static @Nullable Op of(AiBot bot) {
        if (!(bot.mob.level() instanceof ServerLevel level)) return null;
        PerLevel l = LEVELS.get(level);
        return l == null ? null : l.of.get(bot.mob.getId());
    }

    /**
     * Whether its paths begin no swim now: in a crossing, unless it is sent over (or swims back); else under fire
     * (AiWater.underFire, given).
     */
    static boolean dry(AiBot bot, boolean underFire) {
        Op op = of(bot);
        if (op == null) return underFire;
        return !op.going.containsKey(bot);
    }

    /**
     * Its part in a crossing this tick (AiSoldier.decide, before its fight): a crossing begun if it is to cross; on the
     * way to its place at the edge, or over, it does nothing else. Whether it did that (else it fights as it will).
     */
    static boolean step(AiSoldier s, AiBot bot, ServerLevel level, long tick) {
        PerLevel l = LEVELS.computeIfAbsent(level, k -> new PerLevel());
        if (tick - l.swept >= STALE) sweep(l, tick);
        Op op = l.of.get(bot.mob.getId());
        if (op != null && !on()) {
            end(l, op, false);
            return false;
        }
        if (op == null) op = begin(l, bot, level, tick);
        if (op == null) return false;
        if (op.last != tick) {
            op.last = tick;
            if (!advance(l, op, s, level, tick)) return false;
        }
        Long to = op.going.get(bot);
        if (to != null) {
            // one shot at swimming, nearer the bank it left: back there, over with the next wave
            if (AiWater.swims(bot.mob) && AiWater.underFire(bot, tick) && op.phase != Phase.LAST && side(op, bot.mob.position()) < 0) {
                op.going.remove(bot);
                op.first.remove(bot);
                if (!op.second.contains(bot)) op.second.add(0, bot);
                back++;
                s.crossing(bot, op.near, AiSoldier.RUN, null, tick, "shot at swimming over, nearer the bank it left: back");
                return true;
            }
            if (bot.mover.goalIs(to) && (bot.mover.arrived() || bot.mover.failed()) && !AiWater.swims(bot.mob) && side(op, bot.mob.position()) > 0) {
                op.going.remove(bot);
                op.over.add(bot);
                return false;
            }
            // its place out of reach again and again: let be (it fights as it will)
            if (bot.mover.goalIs(to) && bot.mover.failed() && op.fails.merge(bot, 1, Integer::sum) >= FAILS) {
                op.going.remove(bot);
                op.stuck.add(bot);
                return false;
            }
            s.crossing(bot, to, AiSoldier.RUN, null, tick, "over the river with its wave");
            return true;
        }
        // the cover, while some of it are over the water: keeps the foe under fire from where it stands, a burst every
        // few seconds, at it in sight, else where it was last (behind the smoke, behind its wall)
        if (op.cover.contains(bot) && !op.going.isEmpty() && !AiWater.swims(bot.mob) && side(op, bot.mob.position()) < 0) {
            TargetMemory.Contact c = bot.memory.focus(bot.mob.getX(), bot.mob.getZ());
            if (c != null) {
                boolean seen = c.state == TargetMemory.State.VISIBLE;
                s.coverFire(bot, seen ? new Vec3(c.x, c.y + 1.1, c.z) : new Vec3(c.lostX, c.lostY + 1.1, c.lostZ), tick);
                return true;
            }
        }
        Long at = op.edge.get(bot);
        if (at != null && (op.phase == Phase.GATHER || op.phase == Phase.SMOKE)) {
            s.crossing(bot, at, AiSoldier.RUN, centre(op.far), tick, "at the water's edge, waits for its wave");
            return true;
        }
        return false;
    }

    /** crossings none of whose soldiers came by a while (all down, asleep, gone): let go */
    private static void sweep(PerLevel l, long tick) {
        l.swept = tick;
        for (Op op : new ArrayList<>(l.ops.values())) if (op.last != Long.MIN_VALUE && tick - op.last > STALE) end(l, op, false);
        l.dryWay.values().removeIf(until -> until < tick);
    }

    /** a way's end nearer the foe than the place it begins, by a few blocks */
    private static boolean toward(Vec3 from, Vec3 end, Vec3 foe) {
        double a = Math.hypot(from.x - foe.x, from.z - foe.z), b = Math.hypot(end.x - foe.x, end.z - foe.z);
        return b < a - 4;
    }

    /** banks the foe is past: the far one nearer it than the near one */
    private static boolean across(long[] banks, Vec3 foe) {
        return centre(banks[1]).distanceToSqr(foe) < centre(banks[0]).distanceToSqr(foe);
    }

    /** how far past the river's middle a place is, along the way across (less than nothing: on the near side) */
    static double side(Op op, Vec3 p) {
        double mx = (BlockPos.getX(op.near) + BlockPos.getX(op.far)) / 2.0 + 0.5, mz = (BlockPos.getZ(op.near) + BlockPos.getZ(op.far)) / 2.0 + 0.5;
        return (p.x - mx) * op.ux + (p.z - mz) * op.uz;
    }

    /** where a foe is (in sight) or was last */
    private static Vec3 place(TargetMemory.Contact c) {
        return c.state == TargetMemory.State.VISIBLE ? new Vec3(c.x, c.y, c.z) : new Vec3(c.lostX, c.lostY, c.lostZ);
    }

    static Vec3 centre(long cell) {
        return new Vec3(BlockPos.getX(cell) + 0.5, BlockPos.getY(cell) + 1.1, BlockPos.getZ(cell) + 0.5);
    }

    /** a crossing for its squad, if it is to cross a river to a foe near; null if not */
    private static @Nullable Op begin(PerLevel l, AiBot bot, ServerLevel level, long tick) {
        // looked into now and then (the way's cells, a line across the map)
        if (!on() || (tick + bot.mob.getId()) % LOOK != 0 || AiWater.swims(bot.mob)) return null;
        AiSquads.Squad sq = AiSquads.of(bot);
        if (sq == null || sq.bots.size() < SQUAD) return null;
        TacticalMap map = TacticalMap.of(level);
        // the foe it deals with, its way there swimming (or kept out of the water)
        TargetMemory.Contact c = bot.memory.contact(bot.memory.focusId);
        Vec3 pos = bot.mob.position();
        long[] banks = null;
        if (c != null && bot.mob.distanceToSqr(c.x, c.y, c.z) <= NEAR * NEAR) {
            Vec3 foe = place(c);
            // its way to the foe (not to cover or a medic this side) swims; or, kept out of the water, it has no way to it
            long[] path = bot.mover.path();
            if (path.length > 0 && toward(pos, centre(path[path.length - 1]), foe)) banks = banks(map, path);
            if (banks == null && bot.mover.refused() && (bot.mover.failed() || bot.mover.noWay()) && bot.mover.goal() != Long.MIN_VALUE
                    && toward(pos, centre(bot.mover.goal()), foe)) banks = line(map, pos, foe);
            if (banks != null && !across(banks, foe)) banks = null;
        }
        String team = AiSquads.team(bot);
        // or a foe out of its sight a while across water, and no dry way over (a bridge, a ford): from this bank it cannot
        // be fought, only gone after
        if (banks == null && (tick + bot.mob.getId()) % LOOK_FAR == 0)
            for (TargetMemory.Contact o : bot.memory.contacts()) {
                if (o.state == TargetMemory.State.VISIBLE || o.state == TargetMemory.State.GLIMPSED || tick - o.lostTick < OUT_OF_SIGHT) continue;
                if (bot.mob.distanceToSqr(o.lostX, o.lostY, o.lostZ) > NEAR * NEAR || l.dryWay.containsKey(team + ":" + o.id)) continue;
                long[] b = line(map, pos, place(o));
                if (b == null) continue;
                // no crossing to it yet: only with no dry way over
                long start = PathSearch.snap(map, pos.x, pos.y, pos.z);
                if (!l.ops.containsKey(team + ":" + o.id) && start != PathSearch.NONE && PathSearch.find(map, start, b[1], 1.5, DRY_NODES, PathSearch.Cost.NONE, true).reached) {
                    l.dryWay.put(team + ":" + o.id, tick + DRY_KEEP);
                    continue;
                }
                banks = b;
                c = o;
                break;
            }
        if (banks == null || c == null) return null;
        String key = team + ":" + c.id;
        Op op = l.ops.get(key);
        if (op != null) {
            // its side's crossing to that foe under way, here: one of a squad made since joins it, to go with the last
            if (bot.mob.distanceToSqr(centre(op.near)) > JOIN * JOIN) return null;
            if (!op.members.contains(bot)) {
                op.members.add(bot);
                op.second.add(bot);
                l.of.put(bot.mob.getId(), op);
            }
            return op;
        }
        long near = banks[0], far = banks[1];
        double dx = BlockPos.getX(far) - BlockPos.getX(near), dz = BlockPos.getZ(far) - BlockPos.getZ(near), d = Math.hypot(dx, dz);
        if (d < 1) return null;
        op = new Op(team, c.id, near, far, dx / d, dz / d, tick);
        for (AiBot b : sq.bots) {
            if (!AiSquads.live(b) || l.of.containsKey(b.mob.getId())) continue;
            op.members.add(b);
            l.of.put(b.mob.getId(), op);
        }
        waves(op, map);
        l.ops.put(key, op);
        begun++;
        return op;
    }

    /**
     * Who stays and who goes: the base of fire, the marksman and up to RIFLES riflemen stay to cover; of the rest, the
     * first wave (half the squad, WAVE at most), nearest the near bank first, each with its place at the edge; the
     * rest with the second, the base of fire last.
     */
    private static void waves(Op op, TacticalMap map) {
        List<AiBot> rest = new ArrayList<>(op.members);
        Vec3 bank = centre(op.near);
        rest.sort(java.util.Comparator.comparingDouble(b -> b.mob.position().distanceToSqr(bank)));
        int firstN = Math.max(1, Math.min(WAVE, op.members.size() / 2));
        List<AiBot> stay = new ArrayList<>();
        for (AiBot b : rest) if (AiSquads.base(b)) op.base.add(b);
        for (AiBot b : rest) if (op.base.contains(b) || AiMarksman.is(b)) stay.add(b);
        rest.removeAll(stay);
        for (int rifles = 0; rest.size() > firstN && rifles < RIFLES; rifles++) stay.add(rest.remove(rest.size() - 1));
        op.first.addAll(rest.subList(0, Math.min(firstN, rest.size())));
        for (AiBot b : rest) if (!op.first.contains(b)) stay.add(b);
        op.cover.addAll(stay);
        // the second wave: riflemen and the marksman, the base of fire last
        for (AiBot b : stay) if (!op.base.contains(b)) op.second.add(b);
        for (AiBot b : stay) if (op.base.contains(b)) op.second.add(b);
        for (int i = 0; i < op.first.size(); i++) {
            double lat = (i - (op.first.size() - 1) / 2.0) * ACROSS;
            long cell = PathSearch.snap(map, BlockPos.getX(op.near) + 0.5 - op.ux * 1.5 - op.uz * lat, BlockPos.getY(op.near), BlockPos.getZ(op.near) + 0.5 - op.uz * 1.5 + op.ux * lat);
            op.edge.put(op.first.get(i), cell != PathSearch.NONE ? cell : op.near);
        }
    }

    /** the place past the far bank one sent over holds: HOLD_MIN to HOLD_MAX past it, each beside the one before */
    private static long hold(Op op, TacticalMap map, int k) {
        double past = HOLD_MIN + (k * 2) % (HOLD_MAX - HOLD_MIN + 1), lat = ((k % 4) - 1.5) * ACROSS * 2;
        double x = BlockPos.getX(op.far) + 0.5 + op.ux * past - op.uz * lat, z = BlockPos.getZ(op.far) + 0.5 + op.uz * past + op.ux * lat;
        PathSearch.Reader r = new PathSearch.Reader(map);
        long cell = PathSearch.snap(r, x, BlockPos.getY(op.far), z);
        // not in the water (a lake past the bank, another arm of the river): the far bank itself then
        return cell != PathSearch.NONE && !swim(r, cell) ? cell : op.far;
    }

    private static void send(Op op, TacticalMap map, AiBot b) {
        if (op.going.containsKey(b) || op.over.contains(b) || op.stuck.contains(b)) return;
        op.going.put(b, hold(op, map, op.holds++));
    }

    /** the crossing's next part when its time has come; false when it is over (let go) */
    private static boolean advance(PerLevel l, Op op, AiSoldier s, ServerLevel level, long tick) {
        // one down or asleep is out of it (and free to swim once about again)
        for (var it = op.members.iterator(); it.hasNext(); ) {
            AiBot b = it.next();
            if (AiSquads.live(b)) continue;
            it.remove();
            if (l.of.get(b.mob.getId()) == op) l.of.remove(b.mob.getId());
        }
        op.cover.removeIf(b -> !AiSquads.live(b));
        op.first.removeIf(b -> !AiSquads.live(b));
        op.second.removeIf(b -> !AiSquads.live(b));
        op.going.keySet().removeIf(b -> !AiSquads.live(b));
        boolean known = false;
        for (AiBot b : op.members) known |= b.memory.contact(op.target) != null;
        boolean all = !op.members.isEmpty();
        for (AiBot b : op.members) all &= op.over.contains(b) || op.stuck.contains(b) || !AiWater.swims(b.mob) && side(op, b.mob.position()) > 0 && !op.going.containsKey(b);
        if (op.members.isEmpty() || all || tick - op.made > MAX || !known && op.going.isEmpty()) {
            end(l, op, all);
            return false;
        }
        TacticalMap map = TacticalMap.of(level);
        switch (op.phase) {
            case GATHER -> {
                boolean there = true;
                for (AiBot b : op.first) there &= op.edge.containsKey(b) && b.mover.goalIs(op.edge.get(b)) && (b.mover.arrived() || b.mover.failed());
                if (there || tick - op.phaseAt >= GATHER_MAX) phase(op, Phase.SMOKE, tick);
            }
            case SMOKE -> {
                if (!op.smoked) {
                    // the far bank, a little past it, from one of the first wave or the cover that has one
                    Vec3 at = new Vec3(BlockPos.getX(op.far) + 0.5 + op.ux * SMOKE_PAST, BlockPos.getY(op.far), BlockPos.getZ(op.far) + 0.5 + op.uz * SMOKE_PAST);
                    List<AiBot> by = new ArrayList<>(op.first);
                    by.addAll(op.cover);
                    for (AiBot b : by)
                        if (AiSquads.live(b) && b.brain instanceof AiSoldier o && o.smokeOver(b, level, at, tick)) {
                            op.smoked = true;
                            op.phaseAt = tick;
                            smoked++;
                            break;
                        }
                }
                if (op.smoked ? tick - op.phaseAt >= SMOKE_SETTLE : tick - op.phaseAt >= SMOKE_TRY) {
                    for (AiBot b : op.first) send(op, map, b);
                    firstSent++;
                    phase(op, Phase.FIRST, tick);
                }
            }
            case FIRST -> {
                boolean done = true;
                for (AiBot b : op.first) done &= !op.going.containsKey(b);
                if (done || tick - op.phaseAt >= FIRST_MAX) {
                    for (AiBot b : op.second) if (!op.base.contains(b) || op.second.size() == 1) send(op, map, b);
                    secondSent++;
                    phase(op, Phase.SECOND, tick);
                }
            }
            case SECOND -> {
                boolean done = true;
                for (AiBot b : op.second) done &= op.base.contains(b) || !op.going.containsKey(b);
                if (done || tick - op.phaseAt >= SECOND_MAX) {
                    for (AiBot b : op.second) send(op, map, b);
                    phase(op, Phase.LAST, tick);
                }
            }
            case LAST -> {
                // any back from the water since: over again
                for (AiBot b : op.second) if (!op.over.contains(b) && !op.going.containsKey(b) && !AiWater.swims(b.mob)) send(op, map, b);
            }
        }
        return true;
    }

    private static void phase(Op op, Phase p, long tick) {
        op.phase = p;
        op.phaseAt = tick;
    }

    private static void end(PerLevel l, Op op, boolean all) {
        if (all) over++;
        else notOver++;
        l.ops.values().remove(op);
        for (var it = l.of.int2ObjectEntrySet().iterator(); it.hasNext(); ) if (it.next().getValue() == op) it.remove();
    }

    /** the banks of the first stretch of SWIMS cells or more to swim on a path: {near, far}, or null */
    static long @Nullable [] banks(CellSource src, long[] path) {
        PathSearch.Reader r = new PathSearch.Reader(src);
        for (int i = 1; i < path.length; i++) {
            if (!swim(r, path[i])) continue;
            int j = i;
            while (j < path.length && swim(r, path[j])) j++;
            if (j - i >= SWIMS && j < path.length) return new long[] {path[i - 1], path[j]};
            i = j;
        }
        return null;
    }

    private static boolean swim(PathSearch.Reader r, long cell) {
        int x = BlockPos.getX(cell), y = BlockPos.getY(cell), z = BlockPos.getZ(cell);
        return r.has(x, y, z) && (r.flags(x, y, z) & SectionCells.SWIM) != 0;
    }

    /** the banks of the first stretch of SWIMS cells or more to swim on the straight line from one place to another: {near, far}, or null */
    static long @Nullable [] line(CellSource src, Vec3 from, Vec3 to) {
        PathSearch.Reader r = new PathSearch.Reader(src);
        double dx = to.x - from.x, dz = to.z - from.z, d = Math.hypot(dx, dz);
        long last = PathSearch.NONE, start = PathSearch.NONE;
        int run = 0;
        double y = from.y;
        for (double t = 0; t <= d; t += 1) {
            long cell = PathSearch.snap(r, from.x + dx * t / d, y, from.z + dz * t / d);
            if (cell == PathSearch.NONE) {
                // a column with no cell near this height (a step down to the water, a bank up): lower, then higher
                cell = PathSearch.snap(r, from.x + dx * t / d, y - 2, from.z + dz * t / d);
                if (cell == PathSearch.NONE) continue;
            }
            if (cell == last) continue;
            boolean sw = swim(r, cell);
            if (sw) {
                if (run == 0) start = last;
                run++;
            } else {
                if (run >= SWIMS && start != PathSearch.NONE) return new long[] {start, cell};
                run = 0;
            }
            y = BlockPos.getY(cell);
            last = cell;
        }
        return null;
    }
}
