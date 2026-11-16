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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * A river between a squad and its foe, by the field manuals (stage 6, T7 made over; FM 3-21.8 3-123..3-131, 3-168..3-171;
 * BU 018, 212). A squad does not cross a water obstacle of its own will. With a foe across one ({@link #SWIMS} cells or
 * more to swim on its way there, or the foe out of its sight across it) and no order to cross, it keeps to its bank:
 * none of it begins a swim; two of its riflemen go along the bank a way each side looking for a place to cross (a
 * bridge, a ford, water narrow enough to swim), and it reports the foe across the river and the places found to its
 * staff ({@link #reports}: AiCommander reads them). The staff orders the crossing and its place ({@link #task}); a
 * dry way over (a bridge, a ford) is walked as any way. An ordered crossing goes by the drill: all to the near bank at
 * the place; the near side's security (its base of fire, its marksman, a rifleman) stays and covers; the far side's
 * security, a pair, crosses first and holds the far bank, one of it a few blocks further on to watch; then the rest,
 * by pairs; the near side's security last, at the same place; all over, it holds the far bank till it is let go. Smoke
 * onto the far bank when the crossing is watched ({@link #watched}: under fire, its own fire heard, a foe known at the
 * far bank). Shot at swimming: to the nearer rally point in the line of the fire (the near bank
 * if the fire is from across the river and it is not half over yet, else on). Kept by side and target, as the squads are
 * made again every second. Server thread.
 */
public final class AiCrossing {
    /** the server setting: squads keep to their bank of a river without an order, report it, and cross by the drill when ordered */
    public static final String KEY = "aiCrossing";
    /** off only for the stands' "before" */
    static boolean on = true;
    /** a foe this near, blocks; this many cells to swim on the way to it, at least; a squad this big, at least */
    static final double NEAR = 64;
    static final int SWIMS = 3, SQUAD = 3;
    /** the near side's security: the base of fire, the marksman and this many riflemen at most; the far side's: this many */
    static final int RIFLES = 1, SECURITY = 2, PAIR = 2;
    /** the places held at the far bank's rally point, blocks across, and how far past it the one who watches goes */
    static final double ACROSS = 1.5, WATCH_PAST = 6, HOLD_DEEP = 3;
    /** ticks: the wait at the near bank; the security's crossing; a pair's; the last's; the crossing as a whole */
    static final int GATHER_MAX = 300, SMOKE_TRY = 80, SMOKE_SETTLE = 30, SECURITY_MAX = 400, PAIR_MAX = 200, LAST_MAX = 400, MAX = 3600;
    /** a soldier looks for a river between it and a foe on its way every this many ticks, out of its sight every LOOK_FAR; a foe out of its sight this long, ticks */
    static final int LOOK = 10, LOOK_FAR = 40, OUT_OF_SIGHT = 100;
    /** a dry way found over to a foe (a bridge, a ford) is not looked for again this long, ticks; the search's cells at most */
    static final int DRY_KEEP = 200, DRY_NODES = 3000;
    /** one sent over that cannot get to its place this many times is let be; a crossing none of whose soldiers came by this long, ticks, is let go */
    static final int FAILS = 3, STALE = 200;
    /** one that swam back goes again this long after at the soonest, ticks */
    static final int BACK_WAIT = 60;
    /** one joins its side's crossing only this near its near bank, blocks */
    static final double JOIN = 32;
    /** the cover lifts its fire off a place one of its own over the river is this near, blocks */
    static final double LIFT = 8;
    /** under fire this long ago at most, ticks: smoke before the crossing */
    static final int FIRED = 600;
    /** the smoke's burst this far past the far bank, blocks */
    static final double SMOKE_PAST = 2;
    /** the watch at a bank: the scouts' points this far apart along it, so many each way; water this narrow at most is a place to swim; a report is fresh this long, ticks; a watch is let go after */
    static final double SLANT = 1;
    static final int BAND = 4, FAN = 30, SCOUT_BACK = 10, SCOUT_STEP = 12, SCOUT_POINTS = 2, NARROW = 8, FRESH = 1200, WATCH_MAX = 2400, KNOWN = 1200;

    
    /** a place to cross a squad found: where (on the river), how */
    public record Spot(double x, double z, Kind kind) {
    }

    /** a squad's report to its staff: a foe across a river, where it is on its bank, the places to cross it found */
    public record Report(int squadId, long tick, double bankX, double bankZ, int[] foeIds, List<Spot> spots) {
    }

    /** an order to cross: at the place (on the near bank), how, and the far bank's rally point */
    public record Task(double crossX, double crossZ, Kind kind, double farX, double farZ) {
    }

    /** the stands' orders, by side (till the staff's AiCommander.crossing is in) */
    static final Map<String, Task> STAND_ORDERS = new HashMap<>();

    /** how a crossing goes over: a bridge, a ford waded, or swum (the staff's task CROSS, a squad's report) */
    public enum Kind {
        BRIDGE, FORD, SWIM
    }

    enum Phase {
        GATHER, SMOKE, SECURITY, MAIN, LAST
    }

    /** a role in the drill (the stands) */
    enum Role {
        NEAR, FAR, MAIN
    }

    static final class Op {
        final String team;
        final Task task;
        /** the near bank's cell, the far bank's (the rally point); the way across, a unit vector */
        final long near, far;
        final double ux, uz;
        final List<AiBot> members = new ArrayList<>(), cover = new ArrayList<>(), security = new ArrayList<>(), main = new ArrayList<>();
        /** sent over now, and where each goes; over and at its place; let be */
        final Map<AiBot, Long> going = new HashMap<>();
        final java.util.Set<AiBot> over = new java.util.HashSet<>(), stuck = new java.util.HashSet<>(), onward = new java.util.HashSet<>();
        final Map<AiBot, Integer> fails = new HashMap<>();
        /** each one's place at the far bank, once given; when one that swam back may go again */
        final Map<AiBot, Long> holdOf = new HashMap<>(), backUntil = new HashMap<>();
        /** each one's place at the near bank */
        final Map<AiBot, Long> bank = new HashMap<>();
        Phase phase = Phase.GATHER;
        long made, phaseAt, last = Long.MIN_VALUE;
        boolean smoked;
        int holds, sentMain, securityTries;

        Op(String team, Task task, long near, long far, double ux, double uz, long tick) {
            this.team = team;
            this.task = task;
            this.near = near;
            this.far = far;
            this.ux = ux;
            this.uz = uz;
            this.made = phaseAt = tick;
        }

        boolean swims() {
            return task.kind == Kind.SWIM;
        }
    }

    /** a squad at its bank with a foe across and no order: who, the foes, the banks, the scouts and their points, what it found */
    static final class Watch {
        final String team;
        final int id;
        final java.util.Set<Integer> foes = new java.util.HashSet<>();
        final long near, far;
        final double ux, uz;
        final List<AiBot> members = new ArrayList<>();
        final Map<AiBot, List<Long>> scouts = new HashMap<>();
        final List<Spot> spots = new ArrayList<>();
        long made;
        long last = Long.MIN_VALUE;
        Report report;

        Watch(String team, int id, long near, long far, double ux, double uz, long tick) {
            this.team = team;
            this.id = id;
            this.near = near;
            this.far = far;
            this.ux = ux;
            this.uz = uz;
            this.made = tick;
        }
    }

    private static final class PerLevel {
        final Map<String, Op> ops = new HashMap<>();
        final Int2ObjectOpenHashMap<Op> of = new Int2ObjectOpenHashMap<>();
        final List<Watch> watches = new ArrayList<>();
        final Int2ObjectOpenHashMap<Watch> watching = new Int2ObjectOpenHashMap<>();
        /** side:foe with a dry way over found, till when */
        final Map<String, Long> dryWay = new HashMap<>();
        /** each soldier's last tick under fire */
        final it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap fired = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap();
        long swept;
    }

    private static final Map<ServerLevel, PerLevel> LEVELS = new WeakHashMap<>();

    /**
     * the stands: crossings begun, with smoke, security pairs sent, pairs sent, the last sent, back to the near bank
     * shot at, on to the far shot at, over / not; watches begun, scouts' points looked from, places found
     */
    /** the stands: crossings ended not over: all of it down, too long, the order taken back, none of it came by */
    public static final int[] whyNot = new int[4];
    /** the stands: why a crossing went under smoke: under fire, its own fire heard, a foe known at the far bank */
    public static final int[] smokeWhy = new int[3];
    public static int begun, smoked, securitySent, pairsSent, lastSent, back, onward, over, notOver, watches, scouted, found;

    private AiCrossing() {
    }

    static boolean on() {
        return on && ReaimTimes.flag(KEY, true);
    }

    /** crossings and watches let go (the stands) */
    public static void forget(ServerLevel level) {
        LEVELS.remove(level);
    }

    static @Nullable Op of(AiBot bot) {
        if (!(bot.mob.level() instanceof ServerLevel level)) return null;
        PerLevel l = LEVELS.get(level);
        return l == null ? null : l.of.get(bot.mob.getId());
    }

    static @Nullable Watch watchOf(AiBot bot) {
        if (!(bot.mob.level() instanceof ServerLevel level)) return null;
        PerLevel l = LEVELS.get(level);
        return l == null ? null : l.watching.get(bot.mob.getId());
    }

    /** its part in the drill (the stands), or null */
    static @Nullable Role role(AiBot bot) {
        Op op = of(bot);
        if (op == null) return null;
        return op.cover.contains(bot) ? Role.NEAR : op.security.contains(bot) ? Role.FAR : Role.MAIN;
    }

    /**
     * Whether its paths begin no swim now: in a crossing, unless it is sent over swimming (or swims back); at a bank
     * with no order, never; else under fire (AiWater.underFire, given).
     */
    static boolean dry(AiBot bot, boolean underFire) {
        Op op = of(bot);
        if (op != null) return !(op.swims() && op.going.containsKey(bot));
        if (on() && watchOf(bot) != null) return true;
        return underFire;
    }

    /** its staff's order to cross (AiCommander.crossing), or the stands' for its side, or null */
    static @Nullable Task task(AiBot bot, long tick) {
        AiCommander.Order o = AiCommander.crossing(bot, tick);
        if (o != null && o.crossKind != null) return new Task(o.crossX, o.crossZ, o.crossKind, o.farX, o.farZ);
        return STAND_ORDERS.get(AiSquads.team(bot));
    }

    /** the side's fresh reports, one a squad at a bank (its staff reads them) */
    public static List<Report> reports(ServerLevel level, String team) {
        PerLevel l = LEVELS.get(level);
        List<Report> out = new ArrayList<>();
        if (l == null) return out;
        long now = level.getGameTime();
        for (Watch w : l.watches) if (w.team.equals(team) && w.report != null && now - w.report.tick() <= FRESH) out.add(w.report);
        return out;
    }

    /** a report put in (a squad's own; the stands may put one as well) */
    public static void report(ServerLevel level, String team, Report r) {
        PerLevel l = LEVELS.computeIfAbsent(level, k -> new PerLevel());
        for (Watch w : l.watches)
            if (w.team.equals(team) && w.id == r.squadId()) {
                w.report = r;
                AiCommander.reported(level, team);
                return;
            }
        Watch w = new Watch(team, r.squadId(), BlockPos.asLong((int) Math.floor(r.bankX()), 0, (int) Math.floor(r.bankZ())), 0, 0, 0, r.tick());
        w.report = r;
        l.watches.add(w);
        AiCommander.reported(level, team);
    }

    /**
     * Its part in a crossing or a watch this tick (AiSoldier.decide, before its fight): an ordered crossing begun for its
     * squad; a watch begun if a foe is across water and no order is; on the way to its place at the bank, over, or along
     * it as a scout, it does nothing else. Whether it did that (else it fights as it will).
     */
    static boolean step(AiSoldier s, AiBot bot, ServerLevel level, long tick) {
        PerLevel l = LEVELS.computeIfAbsent(level, k -> new PerLevel());
        if (tick - l.swept >= STALE) sweep(l, tick);
        if (AiWater.underFire(bot, tick)) l.fired.put(bot.mob.getId(), tick);
        Op op = l.of.get(bot.mob.getId());
        if (!on()) {
            if (op != null) end(l, op, false);
            Watch w = l.watching.get(bot.mob.getId());
            if (w != null) unwatch(l, w);
            return false;
        }
        if (op == null) op = ordered(l, bot, level, tick);
        if (op == null) return watch(l, s, bot, level, tick);
        if (op.last != tick) {
            op.last = tick;
            if (!advance(l, op, level, tick)) return false;
        }
        return drill(l, op, s, bot, level, tick);
    }

    // ------------------------------------------------------------------ the watch: no order

    /** at its bank without an order: no swim (dry), a scout along the bank; a watch begun if a foe is across */
    private static boolean watch(PerLevel l, AiSoldier s, AiBot bot, ServerLevel level, long tick) {
        Watch w = l.watching.get(bot.mob.getId());
        if (w == null) w = beginWatch(l, bot, level, tick);
        if (w == null) return false;
        if (w.last != tick) {
            w.last = tick;
            if (!keep(l, w, level, tick)) return false;
        }
        List<Long> points = w.scouts.get(bot);
        if (points == null || points.isEmpty()) return false;
        // shot at on its way along the bank: no more of it, it fights as it will
        if (AiWater.underFire(bot, tick)) {
            w.scouts.remove(bot);
            return false;
        }
        long to = points.get(0);
        if (bot.mover.goalIs(to) && (bot.mover.arrived() || bot.mover.failed())) {
            if (bot.mover.arrived()) look(w, TacticalMap.of(level), to, tick);
            points.remove(0);
            if (points.isEmpty()) {
                w.scouts.remove(bot);
                return false;
            }
            to = points.get(0);
        }
        s.moveFiring(bot, to, AiSoldier.WALK, centre(w.far), tick, "along the bank, looking for a place to cross", AiThoughts.Why.RECON);
        return true;
    }

    /** a watch for its squad, if a foe is across water from it and no dry way over; null if not */
    private static @Nullable Watch beginWatch(PerLevel l, AiBot bot, ServerLevel level, long tick) {
        if ((tick + bot.mob.getId()) % LOOK != 0 || AiWater.swims(bot.mob)) return null;
        AiSquads.Squad sq = AiSquads.of(bot);
        if (sq == null || sq.bots.size() < SQUAD) return null;
        TacticalMap map = TacticalMap.of(level);
        Vec3 pos = bot.mob.position();
        String team = AiSquads.team(bot);
        long[] banks = null;
        TargetMemory.Contact c = bot.memory.contact(bot.memory.focusId);
        // its way to the foe (not to cover or a medic this side) swims; or, kept out of the water, it has no way to it
        if (c != null && bot.mob.distanceToSqr(c.x, c.y, c.z) <= NEAR * NEAR) {
            Vec3 foe = place(c);
            long[] path = bot.mover.path();
            if (path.length > 0 && toward(pos, centre(path[path.length - 1]), foe)) banks = banks(map, path);
            if (banks == null && bot.mover.refused() && (bot.mover.failed() || bot.mover.noWay()) && bot.mover.goal() != Long.MIN_VALUE
                    && toward(pos, centre(bot.mover.goal()), foe)) banks = line(map, pos, foe);
            if (banks != null && !across(banks, foe)) banks = null;
        }
        // or a foe out of its sight a while across water, and no dry way over (a bridge, a ford: walked as any way)
        if (banks == null && (tick + bot.mob.getId()) % LOOK_FAR == 0)
            for (TargetMemory.Contact o : bot.memory.contacts()) {
                if (o.state == TargetMemory.State.VISIBLE || o.state == TargetMemory.State.GLIMPSED || tick - o.lostTick < OUT_OF_SIGHT || tick - o.lostTick > KNOWN) continue;
                if (bot.mob.distanceToSqr(o.lostX, o.lostY, o.lostZ) > NEAR * NEAR || l.dryWay.containsKey(team + ":" + o.id)) continue;
                long[] b = line(map, pos, place(o));
                if (b == null) continue;
                long start = PathSearch.snap(map, pos.x, pos.y, pos.z);
                if (start != PathSearch.NONE && PathSearch.find(map, start, b[1], 1.5, DRY_NODES, PathSearch.Cost.NONE, true).reached) {
                    l.dryWay.put(team + ":" + o.id, tick + DRY_KEEP);
                    continue;
                }
                banks = b;
                c = o;
                break;
            }
        if (banks == null || c == null) return null;
        // its side's watch at this bank already: it joins
        for (Watch w : l.watches)
            if (w.team.equals(team) && w.far != 0 && centre(w.near).distanceToSqr(centre(banks[0])) < JOIN * JOIN) {
                w.foes.add(c.id);
                for (AiBot b : sq.bots) if (AiSquads.live(b) && !w.members.contains(b) && !l.of.containsKey(b.mob.getId()) && !l.watching.containsKey(b.mob.getId())) {
                    w.members.add(b);
                    l.watching.put(b.mob.getId(), w);
                }
                return l.watching.get(bot.mob.getId());
            }
        long near = banks[0], far = banks[1];
        double dx = BlockPos.getX(far) - BlockPos.getX(near), dz = BlockPos.getZ(far) - BlockPos.getZ(near), d = Math.hypot(dx, dz);
        if (d < 1) return null;
        Watch w = new Watch(team, bot.mob.getId(), near, far, dx / d, dz / d, tick);
        w.foes.add(c.id);
        for (AiBot b : sq.bots) {
            if (!AiSquads.live(b) || l.of.containsKey(b.mob.getId()) || l.watching.containsKey(b.mob.getId())) continue;
            w.members.add(b);
            l.watching.put(b.mob.getId(), w);
        }
        if (w.members.isEmpty()) return null;
        // the scouts: two riflemen, one each way along the bank, a point every SCOUT_STEP blocks
        double vx = -w.uz, vz = w.ux;
        int way = 0;
        for (AiBot b : w.members) {
            if (way >= 2) break;
            if (AiSquads.base(b) || AiMarksman.is(b)) continue;
            int sign = way++ == 0 ? 1 : -1;
            List<Long> pts = new ArrayList<>();
            PathSearch.Reader r = new PathSearch.Reader(map);
            for (int k = 1; k <= SCOUT_POINTS; k++) {
                // along the bank, kept out of the water: back from it, away from the far bank, till dry (the way over
                // may be slant to the river, and a point straight along would be in it)
                long cell = PathSearch.NONE;
                for (int back = 2; back <= 2 + SCOUT_BACK && cell == PathSearch.NONE; back++) {
                    long q = PathSearch.snap(r, BlockPos.getX(near) + 0.5 - w.ux * back + vx * sign * k * SCOUT_STEP, BlockPos.getY(near),
                            BlockPos.getZ(near) + 0.5 - w.uz * back + vz * sign * k * SCOUT_STEP);
                    if (q != PathSearch.NONE && !wet(r, q)) cell = q;
                }
                if (cell != PathSearch.NONE) pts.add(cell);
            }
            if (!pts.isEmpty()) w.scouts.put(b, pts);
        }
        // what it sees from where it is: the water straight across
        look(w, map, near, tick);
        l.watches.add(w);
        watches++;
        return w;
    }

    /** the watch goes on: its members alive, a foe still known across; false: let go */
    private static boolean keep(PerLevel l, Watch w, ServerLevel level, long tick) {
        w.members.removeIf(b -> !AiSquads.live(b) || l.of.containsKey(b.mob.getId()));
        w.scouts.keySet().removeIf(b -> !w.members.contains(b));
        boolean known = false;
        for (AiBot b : w.members)
            for (int id : w.foes) {
                TargetMemory.Contact c = b.memory.contact(id);
                known |= c != null && (c.state == TargetMemory.State.VISIBLE || c.state == TargetMemory.State.GLIMPSED || tick - c.lostTick <= KNOWN);
            }
        if (w.members.isEmpty() || !known) {
            unwatch(l, w);
            return false;
        }
        // a long watch, the foe still known: it goes on (no tick between in which one may swim), its look along the bank again
        if (tick - w.made > WATCH_MAX) w.made = tick;
        // the report, fresh: the foe across, where it is, what it found; its staff told at once of a new one (a first, a place found)
        boolean news = w.report == null;
        if (news || tick - w.report.tick() >= LOOK_FAR) w.report = new Report(w.id, tick, BlockPos.getX(w.near) + 0.5, BlockPos.getZ(w.near) + 0.5,
                w.foes.stream().mapToInt(Integer::intValue).toArray(), List.copyOf(w.spots));
        if (news) AiCommander.reported(level, w.team);
        return true;
    }

    private static void unwatch(PerLevel l, Watch w) {
        l.watches.remove(w);
        for (var it = l.watching.int2ObjectEntrySet().iterator(); it.hasNext(); ) if (it.next().getValue() == w) it.remove();
    }

    /**
     * From a cell on the bank, a look straight across: no water to swim on the line, and a dry way there over: a
     * bridge or a ford (wet on the way); water NARROW cells or less: a place to swim. Put in its spots, once a place.
     */
    private static void look(Watch w, TacticalMap map, long from, long tick) {
        scouted++;
        // a band along the bank, not one line: the way over it was found along may be slant to the river
        double vx = -w.uz, vz = w.ux;
        for (int o = -BAND; o <= BAND; o += 2) {
            long c = BlockPos.asLong((int) Math.floor(BlockPos.getX(from) + 0.5 + vx * o), BlockPos.getY(from), (int) Math.floor(BlockPos.getZ(from) + 0.5 + vz * o));
            if (lookAcross(w, map, c)) return;
        }
    }

    /** one look straight across from the cell; whether a place was found there */
    private static boolean lookAcross(Watch w, TacticalMap map, long from) {
        PathSearch.Reader r = new PathSearch.Reader(map);
        double width = Math.hypot(BlockPos.getX(w.far) - BlockPos.getX(w.near), BlockPos.getZ(w.far) - BlockPos.getZ(w.near));
        // a fan of looks across, the way over and up to FAN degrees either side (the way over may be slant): the narrowest water
        Vec3 a = new Vec3(BlockPos.getX(from) + 0.5 - w.ux * 3, BlockPos.getY(from), BlockPos.getZ(from) + 0.5 - w.uz * 3);
        Vec3 b = new Vec3(a.x + w.ux * (width + 10), a.y, a.z + w.uz * (width + 10));
        long[] banks = null;
        double across = Double.MAX_VALUE;
        boolean straightDry = false;
        for (int deg = -FAN; deg <= FAN; deg += FAN / 3) {
            double rad = Math.toRadians(deg), cx = w.ux * Math.cos(rad) - w.uz * Math.sin(rad), cz = w.ux * Math.sin(rad) + w.uz * Math.cos(rad);
            long[] k = line(map, a, new Vec3(a.x + cx * (width + 10), a.y, a.z + cz * (width + 10)));
            if (k == null) {
                if (deg == 0) straightDry = true;
                continue;
            }
            double d = Math.hypot(BlockPos.getX(k[1]) - BlockPos.getX(k[0]), BlockPos.getZ(k[1]) - BlockPos.getZ(k[0]));
            if (d < across) {
                across = d;
                banks = k;
            }
        }
        Spot spot = null;
        // (a look a little slant measures the water a little wider: SLANT more)
        if (banks != null && across - 1 <= NARROW + SLANT)
            spot = new Spot((BlockPos.getX(banks[0]) + BlockPos.getX(banks[1])) / 2.0 + 0.5, (BlockPos.getZ(banks[0]) + BlockPos.getZ(banks[1])) / 2.0 + 0.5, Kind.SWIM);
        // straight across no water to swim, the river beside it (the lines to the side swim): a bridge or a ford there,
        // if a dry way goes over to the far side of the river
        else if (straightDry && banks != null) {
            long start = PathSearch.snap(r, a.x, a.y, a.z), end = PathSearch.snap(r, b.x, b.y, b.z);
            if (start != PathSearch.NONE && end != PathSearch.NONE) {
                PathSearch.Result p = PathSearch.find(map, start, end, 1.5, DRY_NODES, PathSearch.Cost.NONE, true);
                long last = p.cells.length == 0 ? PathSearch.NONE : p.cells[p.cells.length - 1];
                boolean over = last != PathSearch.NONE
                        && (BlockPos.getX(last) - BlockPos.getX(w.near)) * w.ux + (BlockPos.getZ(last) - BlockPos.getZ(w.near)) * w.uz > width;
                if (p.reached && over && p.cells.length <= (width + 10) * 2) {
                    boolean wet = false;
                    for (long c : p.cells) wet |= wet(r, c);
                    spot = new Spot(BlockPos.getX(from) + 0.5 + w.ux * (width / 2 + 3), BlockPos.getZ(from) + 0.5 + w.uz * (width / 2 + 3), wet ? Kind.FORD : Kind.BRIDGE);
                }
            }
        }
        if (spot == null) return false;
        for (Spot o : w.spots) if (Math.hypot(o.x() - spot.x(), o.z() - spot.z()) < 4) return true;
        w.spots.add(spot);
        found++;
        w.report = null;
        return true;
    }

    private static boolean wet(PathSearch.Reader r, long cell) {
        int x = BlockPos.getX(cell), y = BlockPos.getY(cell), z = BlockPos.getZ(cell);
        return r.has(x, y, z) && (r.flags(x, y, z) & (SectionCells.SWIM | SectionCells.WATER)) != 0;
    }

    // ------------------------------------------------------------------ the drill: an order

    /** an ordered crossing for its squad (its staff's task), or the one its side has here; null if none */
    private static @Nullable Op ordered(PerLevel l, AiBot bot, ServerLevel level, long tick) {
        Task t = task(bot, tick);
        if (t == null || AiWater.swims(bot.mob) || past(t, bot.mob.position())) return null;
        String team = AiSquads.team(bot);
        String key = team + ":" + Math.round(t.crossX()) + ":" + Math.round(t.crossZ());
        Op op = l.ops.get(key);
        TacticalMap map = TacticalMap.of(level);
        if (op != null) {
            // its side's crossing here under way: one of a squad made since joins it, with the main body
            if (bot.mob.distanceToSqr(centre(op.near)) > JOIN * JOIN || op.phase == Phase.LAST) return null;
            if (!op.members.contains(bot)) {
                op.members.add(bot);
                op.main.add(bot);
                bankPlace(op, map, bot, op.members.size());
                l.of.put(bot.mob.getId(), op);
                Watch w = l.watching.remove(bot.mob.getId());
                if (w != null) w.members.remove(bot);
            }
            return op;
        }
        if ((tick + bot.mob.getId()) % LOOK != 0) return null;
        AiSquads.Squad sq = AiSquads.of(bot);
        if (sq == null || sq.bots.size() < SQUAD) return null;
        double dx = t.farX() - t.crossX(), dz = t.farZ() - t.crossZ(), d = Math.hypot(dx, dz);
        if (d < 1) return null;
        double ux = dx / d, uz = dz / d;
        Vec3 at = new Vec3(t.crossX(), bot.mob.getY(), t.crossZ());
        long near = PathSearch.NONE;
        // the near bank's cell at the place: the water's edge on the line over, swimming; the place itself, else
        if (t.kind() == Kind.SWIM) {
            long[] b = line(map, at.add(-ux * 8, 0, -uz * 8), new Vec3(t.farX(), at.y, t.farZ()));
            if (b != null) near = b[0];
        }
        // a ford (or a swim the line missed): the last dry cell before the water on the way over (the staff's place is a
        // cell of its map, up to 8 blocks short of the edge)
        if (near == PathSearch.NONE) near = edge(map, at, new Vec3(t.farX(), at.y, t.farZ()));
        if (near == PathSearch.NONE) near = PathSearch.snap(map, at.x, at.y, at.z);
        long far = PathSearch.snap(map, t.farX(), at.y, t.farZ());
        if (near == PathSearch.NONE || far == PathSearch.NONE) return null;
        op = new Op(team, t, near, far, ux, uz, tick);
        for (AiBot b : sq.bots) if (AiSquads.live(b) && !l.of.containsKey(b.mob.getId()) && !past(t, b.mob.position()) && !AiWater.swims(b.mob)) op.members.add(b);
        if (!op.members.contains(bot)) return null;
        for (AiBot b : op.members) {
            l.of.put(b.mob.getId(), op);
            Watch w = l.watching.remove(b.mob.getId());
            if (w != null) w.members.remove(b);
        }
        parts(op, map);
        l.ops.put(key, op);
        begun++;
        return op;
    }

    /** the last dry cell before the first water on the straight line from one place to another, or NONE */
    private static long edge(CellSource src, Vec3 from, Vec3 to) {
        PathSearch.Reader r = new PathSearch.Reader(src);
        double dx = to.x - from.x, dz = to.z - from.z, d = Math.hypot(dx, dz), y = from.y;
        long last = PathSearch.NONE;
        for (double t = 0; t <= d; t += 1) {
            long cell = PathSearch.snap(r, from.x + dx * t / d, y, from.z + dz * t / d);
            if (cell == PathSearch.NONE) cell = PathSearch.snap(r, from.x + dx * t / d, y - 2, from.z + dz * t / d);
            if (cell == PathSearch.NONE) continue;
            if (wet(r, cell)) return last;
            last = cell;
            y = BlockPos.getY(cell);
        }
        return PathSearch.NONE;
    }

    /** over already: past the place to cross, along the way over (the order done for it) */
    private static boolean past(Task t, Vec3 p) {
        double dx = t.farX() - t.crossX(), dz = t.farZ() - t.crossZ(), d = Math.hypot(dx, dz);
        return d >= 1 && ((p.x - t.crossX()) * dx + (p.z - t.crossZ()) * dz) / d > 2;
    }

    /**
     * Who does what: the near side's security (the base of fire, the marksman, RIFLES riflemen), the far side's (a pair
     * of riflemen, nearest the place), the main body (the rest, by pairs); each with its place at the near bank.
     */
    private static void parts(Op op, TacticalMap map) {
        List<AiBot> rest = new ArrayList<>(op.members);
        Vec3 bank = centre(op.near);
        rest.sort(java.util.Comparator.comparingDouble(b -> b.mob.position().distanceToSqr(bank)));
        for (AiBot b : rest) if (AiSquads.base(b) || AiMarksman.is(b)) op.cover.add(b);
        rest.removeAll(op.cover);
        for (int i = 0; i < RIFLES && rest.size() > SECURITY + 1; i++) op.cover.add(rest.remove(rest.size() - 1));
        for (int i = 0; i < SECURITY && !rest.isEmpty(); i++) op.security.add(rest.remove(0));
        op.main.addAll(rest);
        // the base of fire last of the near side's security
        op.cover.sort(java.util.Comparator.comparingInt(b -> AiSquads.base(b) ? 1 : 0));
        int i = 0;
        for (AiBot b : op.members) bankPlace(op, map, b, i++);
    }

    /** its place at the near bank: the security and the main body at the edge side by side; the near side's security a few blocks back */
    private static void bankPlace(Op op, TacticalMap map, AiBot b, int i) {
        boolean back = op.cover.contains(b);
        double lat = ((i % 6) - 2.5) * ACROSS, from = back ? 5 : 1.5;
        long cell = PathSearch.snap(map, BlockPos.getX(op.near) + 0.5 - op.ux * from - op.uz * lat, BlockPos.getY(op.near), BlockPos.getZ(op.near) + 0.5 - op.uz * from + op.ux * lat);
        op.bank.put(b, cell != PathSearch.NONE ? cell : op.near);
    }

    /** the place one sent over holds: about the rally point, side by side; the first of the far side's security further on, to watch */
    private static long hold(Op op, TacticalMap map, int k, boolean watcher) {
        double past = watcher ? WATCH_PAST : (k / 4) * ACROSS, lat = watcher ? 0 : ((k % 4) - 1.5) * ACROSS * 2;
        double x = BlockPos.getX(op.far) + 0.5 + op.ux * past - op.uz * lat, z = BlockPos.getZ(op.far) + 0.5 + op.uz * past + op.ux * lat;
        PathSearch.Reader r = new PathSearch.Reader(map);
        long cell = PathSearch.snap(r, x, BlockPos.getY(op.far), z);
        return cell != PathSearch.NONE && !swim(r, cell) ? cell : op.far;
    }

    /** sent over to its place (one place each, kept); whether it went (not one back from the water a moment ago) */
    private static boolean send(Op op, TacticalMap map, AiBot b, long tick) {
        if (op.going.containsKey(b) || op.over.contains(b) || op.stuck.contains(b) || tick < op.backUntil.getOrDefault(b, Long.MIN_VALUE)) return false;
        Long at = op.holdOf.get(b);
        if (at == null) {
            boolean watcher = op.security.indexOf(b) == 0;
            at = hold(op, map, watcher ? 0 : op.holds++, watcher);
            op.holdOf.put(b, at);
        }
        op.going.put(b, at);
        return true;
    }

    /** on its way over, or back from the water a moment ago and soon to go again */
    private static boolean busy(Op op, AiBot b, long tick) {
        return op.going.containsKey(b) || tick < op.backUntil.getOrDefault(b, Long.MIN_VALUE);
    }

    /** one soldier's part this tick; whether it did that */
    private static boolean drill(PerLevel l, Op op, AiSoldier s, AiBot bot, ServerLevel level, long tick) {
        Long to = op.going.get(bot);
        String what = op.cover.contains(bot) ? "over the river last, the near side's security" : op.security.contains(bot) ? "over the river first, the far side's security"
                : "over the river with its pair";
        if (to != null) {
            // shot at swimming: to the nearer rally point in the line of the fire (from across and not half over: back)
            if (AiWater.swims(bot.mob) && AiWater.underFire(bot, tick) && op.phase != Phase.LAST && side(op, bot.mob.position()) < 0 && fromAcross(op, bot)) {
                op.going.remove(bot);
                op.backUntil.put(bot, tick + BACK_WAIT);
                int was = op.main.indexOf(bot);
                if (was >= 0) {
                    op.main.remove(was);
                    if (was < op.sentMain) op.sentMain--;
                }
                // over with the next pair (the security: with its own, again)
                if (!op.security.contains(bot) && !op.cover.contains(bot)) op.main.add(Math.min(op.sentMain, op.main.size()), bot);
                back++;
                s.crossing(bot, op.bank.getOrDefault(bot, op.near), AiSoldier.RUN, null, tick, "shot at swimming over, from across and not half over: back to the near bank");
                return true;
            }
            if (AiWater.swims(bot.mob) && AiWater.underFire(bot, tick) && side(op, bot.mob.position()) >= 0 && op.onward.add(bot)) onward++;
            if (bot.mover.goalIs(to) && (bot.mover.arrived() || bot.mover.failed()) && !AiWater.swims(bot.mob) && side(op, bot.mob.position()) > 0) {
                op.going.remove(bot);
                op.over.add(bot);
                return true;
            }
            if (bot.mover.goalIs(to) && bot.mover.failed() && op.fails.merge(bot, 1, Integer::sum) >= FAILS) {
                op.going.remove(bot);
                op.stuck.add(bot);
                return false;
            }
            s.crossing(bot, to, AiSoldier.RUN, null, tick, what);
            return true;
        }
        // over: it holds its place at the far bank, watching on, till all are over
        if (op.over.contains(bot)) {
            TargetMemory.Contact c = bot.memory.focus(bot.mob.getX(), bot.mob.getZ());
            if (c != null && c.state == TargetMemory.State.VISIBLE) return false;
            Vec3 on = centre(op.far).add(op.ux * 16, 0, op.uz * 16);
            s.crossing(bot, bot.mover.goal() != Long.MIN_VALUE ? bot.mover.goal() : op.far, AiSoldier.WALK, on, tick, "over, holds the far bank till all are over");
            return true;
        }
        // the near side's security, till it goes last: covers from where it is, at the foe in sight, else where it was last
        if (op.cover.contains(bot) && op.phase != Phase.LAST && !AiWater.swims(bot.mob) && side(op, bot.mob.position()) < 0) {
            TargetMemory.Contact c = bot.memory.focus(bot.mob.getX(), bot.mob.getZ());
            Long at = op.bank.get(bot);
            if (op.phase == Phase.GATHER && at != null && !(bot.mover.goalIs(at) && bot.mover.arrived())) {
                s.crossing(bot, at, AiSoldier.RUN, centre(op.far), tick, "to the near bank, the near side's security");
                return true;
            }
            if (c != null) {
                boolean seen = c.state == TargetMemory.State.VISIBLE;
                Vec3 aim = seen ? new Vec3(c.x, c.y + 1.1, c.z) : new Vec3(c.lostX, c.lostY + 1.1, c.lostZ);
                s.coverFire(bot, aim, tick, lifted(op, aim));
                return true;
            }
            return false;
        }
        // the rest wait at their places at the near bank, watching across
        Long at = op.bank.get(bot);
        if (at != null && !AiWater.swims(bot.mob) && side(op, bot.mob.position()) < 0) {
            s.crossing(bot, at, AiSoldier.RUN, centre(op.far), tick, "at the near bank, waits its turn to cross");
            return true;
        }
        return false;
    }

    /** the fire it is under is from across the river (or from none known: from across) */
    private static boolean fromAcross(Op op, AiBot bot) {
        LivingEntity by = bot.mob.getLastHurtByMob();
        return by == null || side(op, by.position()) > 0;
    }

    /** the crossing's next part when its time has come; false when it is over (let go) */
    private static boolean advance(PerLevel l, Op op, ServerLevel level, long tick) {
        for (var it = op.members.iterator(); it.hasNext(); ) {
            AiBot b = it.next();
            if (AiSquads.live(b)) continue;
            it.remove();
            if (l.of.get(b.mob.getId()) == op) l.of.remove(b.mob.getId());
        }
        op.cover.removeIf(b -> !AiSquads.live(b));
        op.security.removeIf(b -> !AiSquads.live(b));
        op.main.removeIf(b -> !AiSquads.live(b));
        op.going.keySet().removeIf(b -> !AiSquads.live(b));
        boolean all = !op.members.isEmpty();
        for (AiBot b : op.members) all &= op.over.contains(b) || op.stuck.contains(b);
        // the order taken back from all of it not over yet (the staff's is let go for each once it is over), none on its way
        boolean ordered = false;
        for (AiBot b : op.members) ordered |= !op.over.contains(b) && task(b, tick) != null;
        if (op.members.isEmpty() || all || tick - op.made > MAX || !ordered && op.going.isEmpty()) {
            if (!all) whyNot[op.members.isEmpty() ? 0 : tick - op.made > MAX ? 1 : 2]++;
            end(l, op, all);
            return false;
        }
        TacticalMap map = TacticalMap.of(level);
        switch (op.phase) {
            case GATHER -> {
                boolean there = true;
                for (AiBot b : op.members) {
                    Long at = op.bank.get(b);
                    there &= at != null && b.mover.goalIs(at) && (b.mover.arrived() || b.mover.failed());
                }
                if (there || tick - op.phaseAt >= GATHER_MAX) {
                    int why = watched(l, op, level, tick);
                    if (why >= 0) smokeWhy[why]++;
                    phase(op, why >= 0 ? Phase.SMOKE : Phase.SECURITY, tick);
                }
                if (op.phase == Phase.SECURITY) sendSecurity(op, map, tick);
            }
            case SMOKE -> {
                if (!op.smoked) {
                    Vec3 at = new Vec3(BlockPos.getX(op.far) + 0.5 - op.ux * (HOLD_DEEP - SMOKE_PAST), BlockPos.getY(op.far), BlockPos.getZ(op.far) + 0.5 - op.uz * (HOLD_DEEP - SMOKE_PAST));
                    List<AiBot> by = new ArrayList<>(op.security);
                    by.addAll(op.main);
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
                    phase(op, Phase.SECURITY, tick);
                    sendSecurity(op, map, tick);
                }
            }
            case SECURITY -> {
                // the far bank held and watched: "clear", the main body goes. None of the security over (down, swum
                // back): it goes again, made up from the main body, twice at most; the main body does not follow blind
                boolean done = true, any = false;
                for (AiBot b : op.security) {
                    done &= !busy(op, b, tick);
                    any |= op.over.contains(b);
                }
                if (done || tick - op.phaseAt >= SECURITY_MAX) {
                    if (!any && op.securityTries < 2 && op.security.size() + op.main.size() > 0) {
                        op.securityTries++;
                        while (op.security.size() < SECURITY && !op.main.isEmpty()) op.security.add(op.main.remove(0));
                        op.phaseAt = tick;
                        sendSecurity(op, map, tick);
                    } else {
                        phase(op, Phase.MAIN, tick);
                        sendPair(op, map, tick);
                    }
                }
            }
            case MAIN -> {
                // the pair sent is over (or let be); all of the main body over: the near side's security may go
                boolean done = true, mainOver = true;
                for (AiBot b : op.main) {
                    done &= !busy(op, b, tick);
                    mainOver &= op.over.contains(b) || op.stuck.contains(b);
                }
                if (done && op.sentMain >= op.main.size() && !mainOver) {
                    // one swum back since, left out of the pairs sent: with the next
                    for (int k = 0; k < op.main.size(); k++)
                        if (!op.over.contains(op.main.get(k)) && !op.stuck.contains(op.main.get(k))) {
                            AiBot b = op.main.remove(k);
                            op.main.add(b);
                            op.sentMain = op.main.size() - 1;
                            break;
                        }
                }
                if (done || tick - op.phaseAt >= PAIR_MAX) {
                    if (op.sentMain < op.main.size()) {
                        op.phaseAt = tick;
                        sendPair(op, map, tick);
                    } else if (done && mainOver) {
                        // all of the main body over: the near side's security last, at the same place
                        phase(op, Phase.LAST, tick);
                        for (AiBot b : op.cover) send(op, map, b, tick);
                        lastSent++;
                    }
                }
            }
            case LAST -> {
                for (AiBot b : op.members) if (!op.over.contains(b) && !op.going.containsKey(b) && !op.stuck.contains(b) && !AiWater.swims(b.mob)) send(op, map, b, tick);
            }
        }
        return true;
    }

    private static void sendSecurity(Op op, TacticalMap map, long tick) {
        boolean any = false;
        for (AiBot b : op.security) any |= send(op, map, b, tick);
        if (any) securitySent++;
    }

    private static void sendPair(Op op, TacticalMap map, long tick) {
        int n = 0;
        while (op.sentMain < op.main.size() && n < PAIR) {
            if (send(op, map, op.main.get(op.sentMain), tick)) n++;
            op.sentMain++;
        }
        if (n > 0) pairsSent++;
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

    /** crossings and watches none of whose soldiers came by a while: let go */
    private static void sweep(PerLevel l, long tick) {
        l.swept = tick;
        for (Op op : new ArrayList<>(l.ops.values()))
            if (op.last != Long.MIN_VALUE && tick - op.last > STALE) {
                whyNot[3]++;
                end(l, op, false);
            }
        for (Watch w : new ArrayList<>(l.watches))
            if (w.far == 0 ? w.report == null || tick - w.report.tick() > FRESH : w.last != Long.MIN_VALUE && tick - w.last > STALE) unwatch(l, w);
        l.dryWay.values().removeIf(until -> until < tick);
        l.fired.values().removeIf(t -> tick - t > FIRED);
    }

    /** a foe its own know of this near the far bank, blocks: the crossing's watched */
    static final double WATCHED = 64;

    /**
     * Whether the crossing is watched, so it goes under smoke (the manuals: a crossing in the foe's sight is smoked):
     * 0 under fire; 1 its own fire went (its base of fire or any of it shot within FIRED: the foe heard it); 2 one of
     * it knows of a live foe at the far bank, seen or lost within FIRED this near it; -1 none. What the foe saw or
     * heard is not asked: only what its own side knows.
     */
    private static int watched(PerLevel l, Op op, ServerLevel level, long tick) {
        if (fired(l, op.members, tick)) return 0;
        for (AiBot b : op.members) {
            long t = b.gunner.shotTick();
            if (t != Long.MIN_VALUE && tick - t <= FIRED) return 1;
        }
        Vec3 far = centre(op.far);
        for (AiBot b : op.members)
            for (TargetMemory.Contact c : b.memory.contacts()) {
                if (!c.known() || Math.max(c.seenTick, c.lostTick) < tick - FIRED) continue;
                double dx = c.x - far.x, dz = c.z - far.z;
                if (dx * dx + dz * dz > WATCHED * WATCHED) continue;
                if (level.getEntity(c.id) instanceof LivingEntity e && e.isAlive() && AiTeams.enemy(b.mob, e)) return 2;
            }
        return -1;
    }

    /** any of them under fire within FIRED ticks */
    private static boolean fired(PerLevel l, List<AiBot> bots, long tick) {
        for (AiBot b : bots) {
            long t = l.fired.getOrDefault(b.mob.getId(), Long.MIN_VALUE);
            if (t != Long.MIN_VALUE && tick - t <= FIRED) return true;
        }
        return false;
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
        // the river's middle: halfway between the near bank and the rally point, less the rally point's way past the bank
        mx -= op.ux * HOLD_DEEP / 2;
        mz -= op.uz * HOLD_DEEP / 2;
        return (p.x - mx) * op.ux + (p.z - mz) * op.uz;
    }

    /** fire lifted off a place: one of its own over the river, out of the water, within LIFT of it */
    private static boolean lifted(Op op, Vec3 at) {
        for (AiBot b : op.members)
            if (b.mob.isAlive() && !AiWater.swims(b.mob) && side(op, b.mob.position()) > 0 && Math.hypot(b.mob.getX() - at.x, b.mob.getZ() - at.z) < LIFT) return true;
        return false;
    }

    /** where a foe is (in sight) or was last */
    private static Vec3 place(TargetMemory.Contact c) {
        return c.state == TargetMemory.State.VISIBLE ? new Vec3(c.x, c.y, c.z) : new Vec3(c.lostX, c.lostY, c.lostZ);
    }

    static Vec3 centre(long cell) {
        return new Vec3(BlockPos.getX(cell) + 0.5, BlockPos.getY(cell) + 1.1, BlockPos.getZ(cell) + 0.5);
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
