package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ReaimTimes;
import acup.gunfeel.ai.AiIntel.Group;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A side's commander (stage 5, R5; the owner, 2026-11-14: "they will attack other teams and players, just a rough
 * whereabouts"). No mob: the side's mind. Every EVERY_MIN to EVERY_MAX ticks, for each team of AI soldiers, it takes
 * the groups of its enemies from its side's picture (AiIntel, stage 7 C0: AI mobs of a team it fights, players it
 * fights; those within 24 blocks of one another one group, a group known by its members from one report to the next;
 * of the mobs, only those that think, near a player) and sends each of its squads with no one to fight to the
 * nearest group within REACH (and twice aiRadius): by its rough whereabouts only, the picture's circle round the
 * group (16 blocks, wider to 32 the longer none of the side has seen any of them, its middle off theirs by up to half
 * the radius). The squad goes there (AiSoldier, when it knows of no one:
 * legs of LEG blocks) and looks through the area with its own eyes, as before; what it sees it fights as ever. PER
 * squads to a group at most (those fighting one counted); a squad with no group left is the reserve and stays where it
 * is, unless the side lost a soldier to a group within HELP ticks (R5b): then HELP_MORE squads more may go there, and
 * the reserve goes. The group a soldier is lost to: that of the one that downed it; else that of whom its squad
 * fought; else the one it was sent to. A squad that looked through an area GIVE_UP ticks in all and found no one, or whose way there failed FAILS legs
 * running with no PROGRESS, gives that group up for AVOID (half of it or more) and is sent to another. Lone soldiers (no squad) and soldiers of no team get
 * no orders. No places set by command, no buildings as targets.
 */
public final class AiCommander {
    /** the server setting: sides have commanders */
    public static final String KEY = "aiCommander";
    /** off only for the stands' "before" */
    static boolean on = true;
    /** a side's orders this often, ticks (between the two, by chance) */
    static final int EVERY_MIN = 100, EVERY_MAX = 140;
    /** a squad goes for a group this far off at most (and twice aiRadius), blocks */
    static final double REACH = 160;
    /** squads to a group at most */
    static final int PER = 2;
    /** a squad goes in legs this long, blocks; a leg is set again after this many ticks; one looking through the area picks another place this often */
    static final double LEG = 24;
    static final int LEG_AGAIN = 100, LOOK_AGAIN = 120;
    /** in the area this long in all with no one found: given up; that group not again for this long; ticks */
    static final int GIVE_UP = 1200, AVOID = 2400;
    /** legs on the way that could not be walked, one after another, none bringing it PROGRESS blocks nearer: given up */
    static final int FAILS = 6;
    static final double PROGRESS = 4;
    /** an order lasts this long unless given again, ticks */
    static final int LASTS = 400;
    /**
     * the squads' reports (C5): looked for every LOOK ticks; the orders given again ASKED_GAP after the last at the
     * soonest; a soldier suppressed this much counts (AiProne.suppressed); most of the squad so PIN_AFTER ticks: pinned
     * down, none PIN_QUIET: no longer
     */
    static final int LOOK = 20, ASKED_GAP = 20, PIN_AFTER = 60, PIN_QUIET = 100;
    /** one that broke contact stays back this long after it was pinned down (C5), ticks */
    static final int REST = 1200;
    static final float SUPPRESSED = 0.5F;
    /** a report's answer (the orders given again after it): ticks in all, how many, the longest (the stands') */
    public static long askedLag, askedN, askedMost;
    /** the last tick orders were given in answer to a report (the stands') */
    public static long answeredAt = Long.MIN_VALUE;
    /** squads that broke contact pinned down (C5) */
    public static long broke;
    static final String REPORTS_KEY = "aiCommanderReports";
    static boolean reportsOn = true;

    /** the staff answers its squads' reports (C5: pinned down, broke contact); false: as before (the reports still made) */
    static boolean reports() {
        return reportsOn && ReaimTimes.flag(REPORTS_KEY, true);
    }

    /** falling back pinned down (C5): it breaks contact, its smoke first, rather than fight on where it is */
    static boolean breaksContact(AiBot bot, long tick) {
        Order o = bot.order;
        return o != null && tick <= o.until && o.job != null && o.job.kind == StaffPlan.Kind.BACK && o.job.breaking && on();
    }

    /**
     * the server setting: the side's staff (stage 7): its map of the land and its ways (C1); off: as R5b, straight
     * legs; off only for the stands' "before"
     */
    public static final String STAFF_KEY = "aiCommanderStaff";
    static boolean staff = true;
    /** a squad's way is made again when its area moved this far from where the way ends, blocks; a way's point is reached this near */
    static final double REROUTE = 24, REACHED = 6;
    /**
     * soldiers asleep (no player within aiRadius) with a task (C2b): moved by the staff itself every DRIFT ticks
     * DRIFT_STEP blocks along their way (a walk's pace), DRIFT_CLIMB blocks up or down a step at most; no nearer a
     * group of the enemy than DRIFT_STOP blocks (asleep they cannot fight)
     */
    static final int DRIFT = 20;
    /** a pair's one going round looked at for a fight this often, ticks (C4) */
    static final int COUPLE = 10;
    static final double DRIFT_STEP = 4, DRIFT_CLIMB = 3, DRIFT_STOP = 48;
    /**
     * asleep: a point of its way in water, or before a stretch over water, reached this near only (not REACHED: it
     * would cut across beside the ford); no spread of the squad within DRIFT_WET of water; blocks
     */
    static final double DRIFT_AT_WATER = 1.5, DRIFT_WET = 8;
    /** asleep, a step into deep water: a way round looked for over the blocks within DETOUR, DETOUR_AGAIN ticks apart at most */
    static final int DETOUR = 12, DETOUR_AGAIN = 100;
    /** new ways a side asks for at most each time it gives its orders (the rest keep theirs or go straight till the next) */
    static final int WAYS = 3;
    static final int ROUTE_FAILS = 3;
    /** new ways the side giving its orders now may still make */
    private static int waysLeft;
    /** the stands: ways made, their time off the server thread (ns); the last ways, for the stands to count (cleared by them) */
    public static final java.util.concurrent.atomic.AtomicLong routes = new java.util.concurrent.atomic.AtomicLong(), routeNanos = new java.util.concurrent.atomic.AtomicLong();
    static final List<HqGrid.Route> made = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * A squad's way as asked for (C1): looked for off the server thread over the staff's map; each of the squad takes
     * it up once it is there. Its route null when done: no way, straight legs.
     */
    static final class Way {
        final double toX, toZ;
        volatile HqGrid.@Nullable Route route;
        /** where it crosses water (the crossing's doctrine), null: it does not */
        volatile StaffCross.@Nullable Site site;
        volatile boolean done;

        Way(double toX, double toZ) {
            this.toX = toX;
            this.toZ = toZ;
        }
    }

    /**
     * the server setting: the side's staff gives each squad a task (stage 7 C2, StaffPlan): advance, pin, go round,
     * hold, scout, fall back, wait; off: as R5, the nearest group or the reserve; off only for the stands' "before"
     */
    public static final String TASKS_KEY = "aiCommanderTasks";
    static boolean tasks = true;
    /**
     * the server setting: a pair pinning and going round works together (stage 7 C4): the one going round there, or in
     * a fight, signals; the pinning one then keeps the group's places under fire, the other advances; their time up
     * (PAIR_PACE), both advance. Squads pinning or holding go no farther after a foe than LEASH from their place. Off
     * only for the stands' "before"
     */
    public static final String PAIRS_KEY = "aiCommanderPairs";
    static boolean pairs = true;
    /** a pair's time: the way round at this pace (blocks a tick, its bounds and all) half as long again, and PAIR_MORE more, PAIR_MOST at most; ticks */
    static final double PAIR_PACE = 0.15;
    static final int PAIR_MORE = 600, PAIR_MOST = 1800;
    /** pinning or holding: after a foe lost no farther than this from its place, blocks */
    static final double LEASH = 24;
    /** there (within LEASH), the one going round at its group this far off the pinning one at least: round, ready */
    static final double READY_ANGLE = Math.toRadians(60);
    /** the one going round for a pair runs into its group within this (blocks to the group's middle): ready, there or not */
    static final double RUN_INTO = 20;
    /** the one going round shot at within this many ticks: it fights back */
    static final long UNDER_FIRE = 40;
    /** the stands: pairs made, those whose one going round got there (or into a fight) in time, the ticks that took in all, those whose time ran out */
    public static int paired, coupled, expired;
    public static long coupleTicks;

    /** the stands: tasks given, by kind (StaffPlan.Kind's order) */
    public static final int[] given = new int[StaffPlan.Kind.values().length];

    /** the server setting: the reserve goes where its side loses soldiers (R5b); off only for the stands' "before" */
    public static final String HELP_KEY = "aiCommanderHelp";
    static boolean help = true;
    /** a group that downed one of the side this lately, ticks; squads more that may go to it then */
    static final int HELP = 1200, HELP_MORE = 1;

    /** the stands: orders given (squads sent), times a squad was held in reserve, areas given up, reserves sent where the side loses soldiers */
    public static int orders, reserve, gaveUp, helped, strays;
    /** of the strays: those come into a squad crossing a river, given its crossing's order */
    public static int joinedCrossing;
    /** the stands: steps the staff moved soldiers asleep, and those it could not (the ground, water, not loaded) */
    public static long drifted, driftBlocked;
    /** of those it could not: into deep water; ways round found by the blocks (detour) */
    public static long driftWater, detours;
    /** the stands: the game tick each side (by its team) was last given its orders */
    static final Map<String, Long> commandedAt = new HashMap<>();

    /**
     * A squad's task as the staff gave it (C2), one to all of it (the staff's squad: those with the same one): what,
     * since when, the soldiers it was given to (its losses counted among them, not those of them out of the squad),
     * the group's members then and where the group was when its place was picked, where to face at its place, whether
     * it got there.
     */
    static final class Job {
        final StaffPlan.Kind kind;
        final long since;
        final List<AiBot> bots;
        final int[] ids;
        double gx, gz, faceX, faceZ;
        boolean there;
        /** the squad it is given to now (the staff's squads change as they move), in its order */
        List<AiBot> members;
        /** how it goes along its way (C3) */
        final StaffMove.State move = new StaffMove.State();
        /** its own stretch on from where the staff's way ended to its area's middle, for its way of going; when it was last looked for in vain */
        HqGrid.@Nullable Route tail;
        long tailNot = Long.MIN_VALUE;
        /** the pair's other (C4: pinning and going round, of the same group), its time up at due, the one going round there (or in a fight) at ready */
        @Nullable Job partner;
        long due = Long.MAX_VALUE, ready = Long.MIN_VALUE, pairedAt;
        /** when the staff last gave it (to the squad, or to the parts of a squad split on the way) */
        long givenAt = Long.MIN_VALUE;
        boolean late;
        /**
         * its squad's reports (C5, AiReports): made already (contact, out of rounds, there, cannot get through); losses
         * reported (1 a third, 2 half); pinned down: most of it suppressed since, pinned since (reported), alive then
         */
        boolean saidContact, saidAmmo, saidThere, saidBlocked;
        /** falling back pinned down (C5): it breaks contact, smoke first (AiSoldier) */
        boolean breaking;
        /** given to help a squad of its side pinned down by the same group (C5: going round it or pinning it too) */
        boolean helps;
        int saidLosses, aliveAtPin;
        long pinFrom = Long.MIN_VALUE, pinnedAt = Long.MIN_VALUE, quietFrom = Long.MIN_VALUE, noFoeFrom = Long.MIN_VALUE;

        Job(StaffPlan.Kind kind, long since, List<AiBot> bots, int[] ids) {
            this.kind = kind;
            this.since = since;
            this.bots = bots;
            this.ids = ids;
            this.members = bots;
        }

        /** of those it was given to, alive still */
        int alive() {
            int n = 0;
            for (AiBot b : bots) if (b.mob.isAlive()) n++;
            return n;
        }

        /** a task at a place (stand there), not an area to go through */
        boolean place() {
            return kind != StaffPlan.Kind.ADVANCE && kind != StaffPlan.Kind.SCOUT;
        }
    }

    /** a squad's order, a copy to each of it: the area, the group (its members' ids, its lead's), till when */
    static final class Order {
        /** the staff's task it is for (C2; null: R5's, an area) */
        @Nullable Job job;
        double x, z, r;
        int lead;
        int[] ids = new int[0];
        long until, nextLeg = Long.MIN_VALUE, lastInside = Long.MIN_VALUE;
        /** how far off the area it was when its last leg failed (or the first one began) */
        double failedAt = Double.MAX_VALUE;
        /** ticks in the area so far; legs failed one after another */
        int inside, fails;
        /** its place in the squad, for a spread of the legs' ends */
        int slot, of;
        /** asleep: a way round deep water over the blocks (x << 32 | z), the next of it, when it was looked for */
        long @Nullable [] detour;
        int detourAt;
        long detourTick = Long.MIN_VALUE, detourFrom;
        /** the point the way round was for */
        double detourTX, detourTZ;
        /**
         * a crossing (the staff's task CROSS): the place on the near bank to go over from, how (null: not a crossing),
         * where to gather on the far bank
         */
        double crossX, crossZ, farX, farZ;
        AiCrossing.@Nullable Kind crossKind;
        /** the staff's way there as asked for (stage 7 C1, the squad's one); null: straight, as R5 */
        @Nullable Way way;
        /** a way walked to its end: not taken up again (on from there in straight legs) */
        @Nullable Way walked;
        /** the way once taken up, the point of it it goes to next; null: straight (none yet, or none) */
        HqGrid.@Nullable Route route;
        int at;
        /** the route is its squad's own stretch past the staff's way (Job.tail); that stretch walked to its end, or it could not be */
        boolean onTail, noTail;
        int tailFails;
        /** legs of the way that could not be walked, one after another; at ROUTE_FAILS it walks straight (as R5) for the rest of the order */
        int routeFails;
        boolean noRoute;
        /** places in its area it could not get to (behind a wall, on a roof), x and z by twos: none looked for near them again */
        final List<double[]> badPlaces = new ArrayList<>(4);
        /** its last leg's end (not a bound's) and how far off it it was when the leg was set */
        double legTX, legTZ, legD = Double.MAX_VALUE;
        /** the nearest it got to the area; legs since with no PROGRESS on that */
        double bestD = Double.MAX_VALUE;
        int idleLegs;
        /** at its task's place, stopped there (C2) */
        boolean stood;
        /**
         * its squad's bounds and rushes (C3): where it goes to or holds (the cover cell found, else none), whether
         * cover was found; for which of them (the mode's start, the turn) and where along the way that ended; where
         * its last leg went
         */
        double coverX, coverZ, coverTo, legX = Double.NaN, legZ = Double.NaN;
        long coverCell = Long.MIN_VALUE, coverSince = Long.MIN_VALUE, halfSince = Long.MIN_VALUE, badCell = Long.MIN_VALUE;
        /** its last walk was a leg of the bounds or rushes (one that fails is theirs to see to) */
        boolean moveLeg;
        /** its half of the squad's bounds, for which of them (the mode's start) */
        int coverTurn, half;
        boolean covered;
    }

    /** a side's own: its next orders */
    private static final class Side {
        long next = Long.MIN_VALUE;
        /** with no group known, the sector of the ring round it its squads scout next (C2) */
        int sector;
        /** the side's reports seen (a new one: its map made again) */
        long reports;
        /** its last tasks given with no map yet (the pairs on: none to go round): all looked at afresh once it has one */
        boolean blind;
        /** its last orders given at; the first report since (Long.MIN_VALUE: none) */
        long commanded = Long.MIN_VALUE, asked = Long.MIN_VALUE;
    }

    private static final Map<ServerLevel, Map<String, Side>> LEVELS = new WeakHashMap<>();
    /** the soonest a side's picture is due again, per level */
    private static final Map<ServerLevel, Long> reportDue = new WeakHashMap<>();

    private AiCommander() {
    }

    public static boolean on() {
        return on && ReaimTimes.flag(KEY, true);
    }

    static boolean tasks() {
        return tasks && ReaimTimes.flag(TASKS_KEY, true);
    }

    public static void forget(ServerLevel level) {
        LEVELS.remove(level);
        AiReports.forget(level);
        AiIntel.forget(level);
        AiHqMap.forget(level);
    }

    static boolean staff() {
        return staff && ReaimTimes.flag(STAFF_KEY, true);
    }

    static boolean pairs() {
        return pairs && ReaimTimes.flag(PAIRS_KEY, true);
    }

    static boolean help() {
        return help && ReaimTimes.flag(HELP_KEY, true);
    }

    /**
     * One of a side down (AiBots.died): where the side loses soldiers. The one that downed it, if of a side it fights;
     * else whom its squad fought; else the group it was sent to.
     */
    static void died(ServerLevel level, AiBot fallen, long tick) {
        if (!on()) return;
        Map<String, Side> sides = LEVELS.get(level);
        String team = AiSquads.team(fallen);
        if (sides == null || !sides.containsKey(team)) return;
        // the mob that hit it last, else whom the game credits (a player who hit it lately first)
        LivingEntity by = fallen.mob.getLastHurtByMob();
        if (by == null || !AiTeams.enemy(fallen.mob, by)) by = fallen.mob.getKillCredit();
        AiSquads.Squad s = AiSquads.of(fallen);
        if (by != null && by != fallen.mob && AiTeams.enemy(fallen.mob, by)) AiIntel.lost(level, team, by.getId(), tick);
        else if (s != null && s.target >= 0) AiIntel.lost(level, team, s.target, tick);
        else if (fallen.order != null && tick <= fallen.order.until) for (int id : fallen.order.ids) AiIntel.lost(level, team, id, tick);
    }

    /** squads that may go to this group: PER, one more where the side lost a soldier lately */
    private static int room(Group g, long now) {
        return help() && g.known().lost != Long.MIN_VALUE && now - g.known().lost <= HELP ? PER + HELP_MORE : PER;
    }


    /** every server tick, after the squads are made (AiBots): each side whose time it is gives its orders */
    static void tick(ServerLevel level) {
        if (!on()) return;
        long now = level.getGameTime();
        if (tasks() && now % DRIFT == 0) drift(level, now);
        if (tasks() && pairs() && now % COUPLE == 0) couple(level, now);
        if (tasks() && now % LOOK == 0) look(level, now);
        Map<String, Side> sides = LEVELS.computeIfAbsent(level, k -> new HashMap<>());
        boolean due = sides.isEmpty() || now >= reportDue.getOrDefault(level, Long.MIN_VALUE);
        for (Side side : sides.values()) due |= now >= side.next;
        // a side not met before is due at once; else nothing to do before the soonest one's time, orders or report (a
        // new side is found on the next one's, at most EVERY_MAX later)
        if (!due) return;
        Map<String, List<AiSquads.Squad>> byTeam = new HashMap<>();
        for (AiSquads.Squad s : AiSquads.squads(level)) {
            String team = AiSquads.team(s.bots.get(0));
            if (!team.isEmpty()) byTeam.computeIfAbsent(team, t -> new ArrayList<>()).add(s);
        }
        // with the staff's tasks, its soldiers asleep too, in squads of its own (C2b): a side all asleep is a side
        if (tasks()) for (AiSquads.Squad s : far(level)) byTeam.computeIfAbsent(AiSquads.team(s.bots.get(0)), t -> new ArrayList<>()).add(s);
        // no side with squads (AI mobs of no team, or alone): looked at again with the squads made again
        if (byTeam.isEmpty()) {
            sides.clear();
            reportDue.put(level, now + AiSquads.EVERY);
            return;
        }
        // the sides' pictures first (AiIntel): every soldier of a side with squads counts, alone, asleep or in a squad
        Map<String, List<AiBot>> own = new HashMap<>();
        for (String team : byTeam.keySet()) own.put(team, new ArrayList<>());
        for (AiBot b : AiBots.bots(level)) {
            List<AiBot> l = b.brain instanceof AiSoldier && b.mob.isAlive() && !b.mob.isRemoved() ? own.get(AiSquads.team(b)) : null;
            if (l != null) l.add(b);
        }
        reportDue.put(level, AiIntel.tick(level, own, now));
        for (var e : byTeam.entrySet()) {
            Side side = sides.computeIfAbsent(e.getKey(), t -> new Side());
            // a new report: the staff's map made again (off the server thread)
            long reports = AiIntel.reports(level, e.getKey());
            if (reports != side.reports) {
                side.reports = reports;
                if (staff()) {
                    // the map round its squads awake (those it gives orders to), not its loners nor its squads asleep
                    // far off (C2b) unless it has none awake
                    List<AiBot> inSquads = new ArrayList<>();
                    for (AiSquads.Squad s : e.getValue()) if (!asleep(s)) inSquads.addAll(s.bots);
                    // all of it asleep (C2b): round those
                    if (inSquads.isEmpty()) for (AiSquads.Squad s : e.getValue()) inSquads.addAll(s.bots);
                    AiHqMap.report(level, e.getKey(), inSquads, AiIntel.groups(level, e.getKey()));
                }
            }
            if (now < side.next) continue;
            side.next = now + EVERY_MIN + level.getRandom().nextInt(EVERY_MAX - EVERY_MIN + 1);
            command(level, e.getKey(), side, e.getValue(), now);
        }
        sides.keySet().retainAll(byTeam.keySet());
    }

    private static void command(ServerLevel level, String team, Side side, List<AiSquads.Squad> squads, long now) {
        // a report's answer: how long after it the orders were given again (C5)
        if (side.asked != Long.MIN_VALUE) {
            long lag = now - side.asked;
            askedLag += lag;
            askedN++;
            askedMost = Math.max(askedMost, lag);
            side.asked = Long.MIN_VALUE;
            answeredAt = now;
        }
        side.commanded = now;
        List<Group> groups = groups(level, team);
        waysLeft = WAYS;
        commandedAt.put(team, now);
        if (tasks()) {
            tasks(level, team, side, squads, groups, now);
            return;
        }
        Map<Group, Integer> sent = new java.util.IdentityHashMap<>();
        List<AiSquads.Squad> going = new ArrayList<>(), idle = new ArrayList<>();
        // squads at a fight first: counted to the group of their target
        for (AiSquads.Squad s : squads) {
            if (s.target < 0) {
                going.add(s);
                continue;
            }
            Group t = counted(s, groups, now);
            if (t != null) sent.merge(t, 1, Integer::sum);
        }
        // then those on their way to a group still there (known by any of its members), as they are while there is
        // room: by the order most of the squad carries (squads are made again as they move: a few with an order in a
        // squad of others is not a squad sent)
        for (AiSquads.Squad s : going) {
            Group g = going(s, groups, now);
            if (g != null && !failed(s, g, now) && sent.getOrDefault(g, 0) < room(g, now)) {
                sent.merge(g, 1, Integer::sum);
                give(level, team, s, g, now, false);
            } else idle.add(s);
        }
        // the rest: each the nearest group with room, not one it gave up lately; none: the reserve
        double reach = Math.min(REACH, AiBots.radius() * 2);
        for (AiSquads.Squad s : idle) {
            double sx = 0, sz = 0;
            for (AiBot b : s.bots) {
                sx += b.mob.getX() / s.bots.size();
                sz += b.mob.getZ() / s.bots.size();
            }
            Group best = null;
            double bestD = reach * reach;
            for (Group g : groups) {
                if (sent.getOrDefault(g, 0) >= PER || failed(s, g, now)) continue;
                double dx = g.x() - sx, dz = g.z() - sz, d = dx * dx + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = g;
                }
            }
            // none: the reserve, unless there is a group where the side loses soldiers with room for one more
            boolean helping = false;
            if (best == null) for (Group g : groups) {
                if (sent.getOrDefault(g, 0) >= room(g, now) || failed(s, g, now)) continue;
                double dx = g.x() - sx, dz = g.z() - sz, d = dx * dx + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = g;
                    helping = true;
                }
            }
            if (best == null) {
                for (AiBot b : s.bots) b.order = null;
                reserve++;
                continue;
            }
            if (helping) helped++;
            sent.merge(best, 1, Integer::sum);
            give(level, team, s, best, now, true);
        }
    }

    /**
     * The staff's tasks (C2): each squad as the plan has it (StaffPlan) from its strength, losses, rounds and the task
     * most of it has now; each group from its kinds and health, how lately seen, its room (R5b).
     */
    private static void tasks(ServerLevel level, String team, Side side, List<AiSquads.Squad> squads, List<Group> groups, long now) {
        List<StaffPlan.Group> foes = new ArrayList<>(groups.size());
        for (Group g : groups) {
            IntelBook.Group k = g.known();
            int n = 0;
            double strength = 0;
            for (int kind = 0; kind < k.kinds.length; kind++) {
                n += k.kinds[kind];
                strength += k.kinds[kind] * StaffPlan.weight(kind);
            }
            if (n > 0) strength *= Math.min(1, k.health / n);
            foes.add(new StaffPlan.Group(g.x(), g.z(), g.r(), strength, k.seen == Long.MIN_VALUE ? Long.MAX_VALUE : now - k.seen, room(g, now)));
        }
        List<StaffPlan.Squad> own = new ArrayList<>(squads.size());
        List<Job> jobs = new ArrayList<>(squads.size());
        double sideX = 0, sideZ = 0;
        int all = 0;
        boolean supply = AiSupply.on() && ReaimTimes.flag(AiSupply.OUT_KEY, true);
        HqGrid grid = AiHqMap.of(level, team);
        boolean afresh = side.blind && grid != null;
        for (AiSquads.Squad s : squads) {
            double sx = 0, sz = 0, strength = 0;
            int alive = 0, out = 0;
            for (AiBot b : s.bots) {
                sx += b.mob.getX() / s.bots.size();
                sz += b.mob.getZ() / s.bots.size();
                if (!b.mob.isAlive()) continue;
                alive++;
                boolean empty = supply && !b.gunner.ammo.infinite() && b.gunner.ammo.reserve() <= 0 && b.gunner.capacity() > 0;
                if (empty) out++;
                strength += StaffPlan.weight(AiRole.of(b).ordinal()) * Math.min(1, b.mob.getHealth() / b.mob.getMaxHealth()) * (empty ? 0.3 : 1);
                sideX += b.mob.getX();
                sideZ += b.mob.getZ();
                all++;
            }
            Job job = job(s, now);
            // a squad split on the way: the squad before it with the same task (the plan gives them one)
            int twin = job == null ? -1 : jobs.indexOf(job);
            jobs.add(job);
            int group = job == null ? -1 : groups.indexOf(match(job.ids, groups));
            // a group it gave up lately (none of it could get there): not again for AVOID
            int avoid = -1;
            for (int k = 0; k < groups.size() && avoid < 0; k++) if (failed(s, groups.get(k), now)) avoid = k;
            boolean pair = pairs() && job != null && job.partner != null;
            // a pair's time up with the one going round not ready: counted here, before the plan gives it another task
            if (pair && job.kind == StaffPlan.Kind.FLANK && job.ready == Long.MIN_VALUE && now >= job.due && !job.late) {
                job.late = true;
                expired++;
            }
            own.add(new StaffPlan.Squad(sx, sz, strength, job == null ? alive : job.alive(), job == null ? alive : job.bots.size(), out * 2 > alive, job == null ? null : job.kind,
                    job == null ? 0 : afresh ? now - StaffPlan.KEEP : job.since, group, job != null && job.there, avoid, asleep(s), pair && job.ready != Long.MIN_VALUE, pair ? job.due : Long.MAX_VALUE,
                    job == null || !reports() ? Long.MIN_VALUE : job.pinnedAt, job != null && job.pinnedAt != Long.MIN_VALUE && job.alive() < job.aliveAtPin,
                    pair && job.partner != null && job.partner.alive() > 0 && !job.late, twin));
        }
        if (all == 0) return;
        sideX /= all;
        sideZ /= all;
        StaffPlan.Task[] plan = StaffPlan.plan(own, foes, grid, sideX, sideZ, Math.min(REACH, AiBots.radius() * 2), PER, now, side.sector);
        side.blind = grid == null && mapped();
        for (int i = 0; i < squads.size(); i++) {
            // a squad crossing a river now keeps its task till it is over (the drill is not broken off half way)
            Job cj = jobs.get(i);
            if (cj != null && crossing(cj)) {
                // its orders kept alive meanwhile (they last LASTS); one of the squad made since with none of it is given it
                for (AiBot b : cj.members) if (b.order != null && b.order.job == cj) b.order.until = Math.max(b.order.until, now + LASTS);
                for (AiBot b : squads.get(i).bots) {
                    if (b.order != null && b.order.job == cj) b.order.until = Math.max(b.order.until, now + LASTS);
                    else if (b.mob.isAlive()) join(b, cj, now);
                }
                continue;
            }
            task(level, team, side, squads.get(i), jobs.get(i), plan[i], plan[i].group() < 0 ? null : groups.get(plan[i].group()), now);
            // sent round or to pin for one of the side pinned down by that group (C5's help, not a change of mind)
            if (helps(own, plan, i)) {
                Job nj = job(squads.get(i), now);
                if (nj != null && nj.kind == plan[i].kind()) nj.helps = true;
            }
            // pinned down and sent back (C5): it breaks contact
            if (plan[i].kind() == StaffPlan.Kind.BACK && own.get(i).pinned() != Long.MIN_VALUE) {
                Job nj = job(squads.get(i), now);
                if (nj != null && nj.kind == StaffPlan.Kind.BACK && !nj.breaking) {
                    nj.breaking = true;
                    broke++;
                }
            }
        }
        if (pairs()) pair(squads, grid, now);
        strays(level, team, squads, now);
    }

    /** squad {@code i}, not pinned down itself, given a way round or a pin at a group a squad of its side is pinned down by */
    static boolean helps(List<StaffPlan.Squad> own, StaffPlan.Task[] plan, int i) {
        StaffPlan.Task t = plan[i];
        if (t == null || t.group() < 0 || t.kind() != StaffPlan.Kind.FLANK && t.kind() != StaffPlan.Kind.PIN || own.get(i).pinned() != Long.MIN_VALUE) return false;
        for (int k = 0; k < own.size(); k++) if (k != i && own.get(k).pinned() != Long.MIN_VALUE && own.get(k).group() == t.group()) return true;
        return false;
    }

    /**
     * The side's soldiers asleep in squads of the staff's own (C2b), as AiSquads makes them of those awake: each grown
     * from one, the nearest of its side within AiSquads.NEAR of any in it, AiSquads.MAX at most; one alone is one too.
     * (A base of fire is not kept BASE_REACH times as far here as AiSquads keeps it: asleep, no one goes round a side.)
     * Only those within AiIntel.KNOW and aiRadius of a player (farther none of their enemies may wake: no work for a
     * server no one plays near), and none still in a squad of AiSquads' (asleep since it was made).
     */
    static List<AiSquads.Squad> far(ServerLevel level) {
        List<AiSquads.Squad> out = new ArrayList<>();
        if (!AiSquads.on) return out;
        double near = AiIntel.KNOW + AiBots.radius();
        List<AiBot> free = new ArrayList<>();
        List<String> teams = new ArrayList<>();
        for (AiBot b : AiBots.bots(level)) {
            if (b.active || !(b.brain instanceof AiSoldier) || !b.mob.isAlive() || b.mob.isRemoved() || AiSquads.of(b) != null) continue;
            String team = AiSquads.team(b);
            if (team.isEmpty() || AiSquads.offTeams.contains(team) || !playerWithin(level, b, near)) continue;
            free.add(b);
            teams.add(team);
        }
        if (free.isEmpty()) return out;
        // by AiSquads.NEAR cells: a squad grows from the cells round its own only
        Map<Long, List<Integer>> cells = new HashMap<>();
        for (int i = 0; i < free.size(); i++) cells.computeIfAbsent(cell(free.get(i)), c -> new ArrayList<>()).add(i);
        boolean[] taken = new boolean[free.size()];
        double near2 = AiSquads.NEAR * AiSquads.NEAR;
        for (int i = 0; i < free.size(); i++) {
            if (taken[i]) continue;
            AiSquads.Squad s = new AiSquads.Squad();
            taken[i] = true;
            s.bots.add(free.get(i));
            String team = teams.get(i);
            while (s.bots.size() < AiSquads.MAX) {
                int best = -1;
                double bestD = near2;
                for (AiBot in : s.bots) {
                    int cx = Mth.floor(in.mob.getX() / AiSquads.NEAR), cz = Mth.floor(in.mob.getZ() / AiSquads.NEAR);
                    for (int ox = -1; ox <= 1; ox++)
                        for (int oz = -1; oz <= 1; oz++)
                            for (int j : cells.getOrDefault(key(cx + ox, cz + oz), List.of())) {
                                if (taken[j] || !team.equals(teams.get(j))) continue;
                                double d = in.mob.distanceToSqr(free.get(j).mob);
                                if (d <= bestD) {
                                    bestD = d;
                                    best = j;
                                }
                            }
                }
                if (best < 0) break;
                taken[best] = true;
                s.bots.add(free.get(best));
            }
            for (AiBot b : s.bots) {
                s.cx += b.mob.getX() / s.bots.size();
                s.cz += b.mob.getZ() / s.bots.size();
            }
            out.add(s);
        }
        return out;
    }

    private static long cell(AiBot b) {
        return key(Mth.floor(b.mob.getX() / AiSquads.NEAR), Mth.floor(b.mob.getZ() / AiSquads.NEAR));
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static boolean playerWithin(ServerLevel level, AiBot b, double r) {
        for (Player p : level.players()) if (!p.isSpectator() && p.distanceToSqr(b.mob) <= r * r) return true;
        return false;
    }

    /** most of the squad asleep: one of the staff's own (far) */
    static boolean asleep(AiSquads.Squad s) {
        int n = 0;
        for (AiBot b : s.bots) if (!b.active) n++;
        return n * 2 > s.bots.size();
    }

    /**
     * The soldiers asleep with a task, moved by the staff (C2b): a step along the way (straight where there is none),
     * each to its own side of the point as awake (step; by water nearly on it), onto the middle of the block on the
     * ground as the heightmap has it, in loaded chunks only; not into deep water or lava (a ford a block deep it wades),
     * nor up or down more than DRIFT_CLIMB. Deep water ahead: a way round it over the blocks (detour), followed; else,
     * or the ground, a step turned aside, one way then the other, and FAILS such in a row give the task up as awake.
     * A point of the way in water, or before a stretch over it, is reached on it (DRIFT_AT_WATER), not at REACHED: it
     * goes into the ford where the way does. None once at the task's place (got there, as awake), nor within
     * DRIFT_STOP of a group of the enemy. Woken (a player near), it goes on from where it is by its order as any other.
     */
    private static void drift(ServerLevel level, long now) {
        Map<String, List<Group>> groupsOf = new HashMap<>();
        for (AiBot b : AiBots.bots(level)) {
            if (b.active || !(b.brain instanceof AiSoldier) || !b.mob.isAlive() || b.mob.isRemoved() || !has(b, now)) continue;
            Order o = b.order;
            if (o.job == null) continue;
            String team = AiSquads.team(b);
            if (!AiSquads.on || AiSquads.offTeams.contains(team)) continue;
            double x = b.mob.getX(), z = b.mob.getZ(), d = Math.hypot(o.x - x, o.z - z);
            if (o.job.place() ? d <= o.r : d <= o.r * 0.5) {
                if (o.job.place()) o.job.there = true;
                o.fails = 0;
                continue;
            }
            boolean close = false;
            for (Group g : groupsOf.computeIfAbsent(team, t -> groups(level, t))) if (Math.hypot(g.x() - x, g.z() - z) <= DRIFT_STOP) close = true;
            if (close) continue;
            // toward the next point of its way, else straight to the place; its own side of it
            if (o.route == null && !o.noRoute) takeUp(o, b);
            double tx = o.x, tz = o.z;
            HqGrid.Route way = o.route;
            HqGrid grid = AiHqMap.of(level, team);
            if (way != null) {
                while (o.at < way.size() - 1 && Math.hypot(way.x(o.at) - x, way.z(o.at) - z) <= reached(grid, way, o.at)) o.at++;
                if (o.at < way.size() - 1 || Math.hypot(way.x(o.at) - x, way.z(o.at) - z) > REACHED) {
                    tx = way.x(o.at);
                    tz = way.z(o.at);
                }
            }
            // its own side of the point, not by water (a ford is narrow)
            // (by water, or at a point to be reached on it: nearly on it)
            boolean onIt = way != null && o.at < way.size() && reached(grid, way, o.at) < REACHED;
            double a = Math.PI * 2 * o.slot / Math.max(1, o.of), spread = o.of <= 1 ? 0 : onIt || nearWater(grid, tx, tz) ? 1 : 3;
            tx += Math.cos(a) * spread;
            tz += Math.sin(a) * spread;
            double l = Math.hypot(tx - x, tz - z);
            if (l < 1e-3) continue;
            // on a way round deep water found before: along it, while it is where the last step left it and goes for
            // the same point (woken and moved meanwhile, or its way changed: dropped)
            if (o.detour != null) {
                long last = o.detourAt == 0 ? o.detourFrom : o.detour[o.detourAt - 1];
                if (Math.abs((int) (last >> 32) - Mth.floor(x)) + Math.abs((int) last - Mth.floor(z)) > 2 || Math.hypot(o.detourTX - tx, o.detourTZ - tz) > 2) o.detour = null;
            }
            if (o.detour != null) {
                int k = Math.min(o.detour.length - 1, o.detourAt + (int) DRIFT_STEP - 1);
                long n = o.detour[k];
                int dx = (int) (n >> 32), dz = (int) n;
                if (level.hasChunk(dx >> 4, dz >> 4)) {
                    o.detourAt = k + 1;
                    if (o.detourAt >= o.detour.length) o.detour = null;
                    double turn = Math.atan2(dz + 0.5 - z, dx + 0.5 - x);
                    b.mob.snapTo(dx + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, dx, dz), dz + 0.5, (float) Math.toDegrees(Math.atan2(-Math.cos(turn), Math.sin(turn))), 0F);
                    b.mob.setYHeadRot(b.mob.getYRot());
                    o.fails = 0;
                    drifted++;
                }
                continue;
            }
            // the step turned aside after one that could not be made, one way then the other
            double along = Math.atan2(tz - z, tx - x) + (o.fails == 0 ? 0 : (o.fails % 2 == 1 ? 1 : -1) * Math.PI / 4 * ((o.fails + 1) / 2));
            double step = Math.min(DRIFT_STEP, l);
            int bx = Mth.floor(x + Math.cos(along) * step), bz = Mth.floor(z + Math.sin(along) * step);
            // its point within the block it stands on (a step to it goes nowhere): reached, on to the next
            if (bx == Mth.floor(x) && bz == Mth.floor(z) && o.fails == 0) {
                if (way != null && o.at < way.size() - 1) o.at++;
                continue;
            }
            // a chunk not loaded: it waits there
            if (!level.hasChunk(bx >> 4, bz >> 4)) continue;
            int ny = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
            // into water a block deep (a ford) it wades; deeper water, or any other liquid, it does not go into
            var fluid = level.getFluidState(new BlockPos(bx, ny - 1, bz));
            boolean wet = !fluid.isEmpty() && (!fluid.is(net.minecraft.tags.FluidTags.WATER) || !level.getFluidState(new BlockPos(bx, ny - 2, bz)).isEmpty());
            // (by the block itself, not the staff's map's cell: a ford in a cell mostly deep)
            if (wet || Math.abs(ny - b.mob.getY()) > DRIFT_CLIMB) {
                driftBlocked++;
                if (wet) driftWater++;
                // deep water ahead: a way round it over the blocks (by the ford), now and then at most
                if (wet && (o.detourTick == Long.MIN_VALUE || now - o.detourTick >= DETOUR_AGAIN)) {
                    o.detourTick = now;
                    long[] path = detour(level, Mth.floor(x), Mth.floor(z), Mth.floor(b.mob.getY()), tx, tz);
                    if (path != null) {
                        detours++;
                        o.detour = path;
                        o.detourAt = 0;
                        o.detourFrom = (long) Mth.floor(x) << 32 | Mth.floor(z) & 0xFFFFFFFFL;
                        o.detourTX = tx;
                        o.detourTZ = tz;
                        continue;
                    }
                    // no nearer to it by the blocks, its point within REACHED (one in deep water it cannot stand on): on to the next
                    if (way != null && o.at < way.size() - 1 && Math.hypot(way.x(o.at) - x, way.z(o.at) - z) <= REACHED) {
                        o.at++;
                        o.fails = 0;
                        continue;
                    }
                }
                if (++o.fails >= FAILS) giveUp(b, o, now);
                continue;
            }
            o.fails = 0;
            b.mob.snapTo(bx + 0.5, ny, bz + 0.5, (float) Math.toDegrees(Math.atan2(-Math.cos(along), Math.sin(along))), 0F);
            b.mob.setYHeadRot(b.mob.getYRot());
            drifted++;
        }
    }

    /** asleep, a point of its way: reached within REACHED, or DRIFT_AT_WATER when it or the stretch on from it is over water */
    private static double reached(@Nullable HqGrid grid, HqGrid.Route way, int at) {
        if (grid == null) return REACHED;
        double x = way.x(at), z = way.z(at);
        if (StaffCross.water(grid, x, z) > 0) return DRIFT_AT_WATER;
        if (at + 1 < way.size()) {
            double nx = way.x(at + 1), nz = way.z(at + 1), len = Math.hypot(nx - x, nz - z);
            for (double t = 2; t < len; t += 2)
                if (StaffCross.water(grid, x + (nx - x) * t / len, z + (nz - z) * t / len) > 0) return DRIFT_AT_WATER;
        }
        return REACHED;
    }

    /** water on the staff's map within DRIFT_WET of x, z (no spread of the squad there) */
    private static boolean nearWater(@Nullable HqGrid grid, double x, double z) {
        if (grid == null) return false;
        for (int i = -1; i <= 1; i++)
            for (int k = -1; k <= 1; k++)
                if (StaffCross.water(grid, x + i * DRIFT_WET, z + k * DRIFT_WET) > 0) return true;
        return false;
    }

    /**
     * asleep, deep water in its step: a way over the blocks within DETOUR of it (dry ground, or water a block deep: a
     * ford; up or down DRIFT_CLIMB at most a step) to the one nearest to tx, tz; null: none nearer by 2 at least.
     * The blocks it goes by after its own, x << 32 | z.
     */
    static long @Nullable [] detour(ServerLevel level, int x0, int z0, int y0, double tx, double tz) {
        int side = DETOUR * 2 + 1;
        int[] from = new int[side * side], ys = new int[side * side];
        java.util.Arrays.fill(from, -2);
        int start = DETOUR * side + DETOUR;
        from[start] = -1;
        ys[start] = y0;
        int[] queue = new int[side * side];
        int head = 0, tail = 0, best = start;
        queue[tail++] = start;
        double bestD = Math.hypot(x0 + 0.5 - tx, z0 + 0.5 - tz);
        double startD = bestD;
        while (head < tail) {
            int c = queue[head++];
            int cx = c % side, cz = c / side;
            for (int d = 0; d < 4; d++) {
                int nx = cx + (d == 0 ? 1 : d == 1 ? -1 : 0), nz = cz + (d == 2 ? 1 : d == 3 ? -1 : 0);
                if (nx < 0 || nz < 0 || nx >= side || nz >= side) continue;
                int n = nz * side + nx;
                if (from[n] != -2) continue;
                int bx = x0 + nx - DETOUR, bz = z0 + nz - DETOUR;
                from[n] = -3;
                if (!level.hasChunk(bx >> 4, bz >> 4)) continue;
                int ny = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
                if (Math.abs(ny - ys[c]) > DRIFT_CLIMB) continue;
                var fluid = level.getFluidState(new BlockPos(bx, ny - 1, bz));
                if (!fluid.isEmpty() && (!fluid.is(net.minecraft.tags.FluidTags.WATER) || !level.getFluidState(new BlockPos(bx, ny - 2, bz)).isEmpty())) continue;
                from[n] = c;
                ys[n] = ny;
                queue[tail++] = n;
                double dd = Math.hypot(bx + 0.5 - tx, bz + 0.5 - tz);
                if (dd < bestD) {
                    bestD = dd;
                    best = n;
                }
            }
        }
        if (best == start || startD - bestD < 2) return null;
        int len = 0;
        for (int c = best; c != start; c = from[c]) len++;
        long[] path = new long[len];
        for (int c = best, i = len - 1; c != start; c = from[c], i--)
            path[i] = (long) (x0 + c % side - DETOUR) << 32 | (z0 + c / side - DETOUR) & 0xFFFFFFFFL;
        return path;
    }

    /**
     * A soldier with a task of the staff's left out of the squads (more than AiSquads.NEAR off the others: it fell
     * behind): the task of the nearest squad of its side within reach as that squad has it now, to catch up with it;
     * none: its order runs out.
     */
    private static void strays(ServerLevel level, String team, List<AiSquads.Squad> squads, long now) {
        double reach = Math.min(REACH, AiBots.radius() * 2);
        for (AiBot b : AiBots.bots(level)) {
            Order mine = b.order;
            if (mine == null || mine.job == null || now > mine.until || !(b.brain instanceof AiSoldier) || !b.mob.isAlive() || !b.active) continue;
            if (AiSquads.of(b) != null || !team.equals(AiSquads.team(b))) continue;
            Order best = null;
            double bestD = reach * reach;
            for (AiSquads.Squad s : squads) {
                // after one awake as it is (one asleep goes at the staff's pace, far off)
                if (asleep(s)) continue;
                Order o = null;
                double sx = 0, sz = 0;
                for (AiBot c : s.bots) {
                    sx += c.mob.getX() / s.bots.size();
                    sz += c.mob.getZ() / s.bots.size();
                    if (o == null && c.order != null && c.order.job != null && now <= c.order.until) o = c.order;
                }
                double dx = sx - b.mob.getX(), dz = sz - b.mob.getZ(), d = dx * dx + dz * dz;
                if (o != null && d < bestD) {
                    bestD = d;
                    best = o;
                }
            }
            // none within reach: its order runs out, it is on its own
            if (best == null) continue;
            Order o = best.job == mine.job ? mine : new Order();
            o.job = best.job;
            o.x = best.x;
            o.z = best.z;
            o.r = best.r;
            o.lead = best.lead;
            o.ids = best.ids;
            o.until = now + LASTS;
            o.of = 1;
            if (o.way != best.way) {
                o.way = best.way;
                o.route = null;
                o.noRoute = false;
                o.routeFails = 0;
                takeUp(o, b);
            }
            if (o != mine) strays++;
            b.order = o;
        }
    }

    /** the task most of the squad has now (none: null) */
    private static @Nullable Job job(AiSquads.Squad s, long now) {
        Job best = null;
        int most = 0;
        for (AiBot b : s.bots) {
            Job j = b.order != null && now <= b.order.until ? b.order.job : null;
            if (j == null) continue;
            int n = 0;
            for (AiBot c : s.bots) if (c.order != null && now <= c.order.until && c.order.job == j) n++;
            if (n > most) {
                most = n;
                best = j;
            }
        }
        return most * 2 >= s.bots.size() ? best : null;
    }

    /** the squad's task: the one it has if the same (its time kept), else a new one; its way there, each its order */
    private static void task(ServerLevel level, String team, Side side, AiSquads.Squad s, @Nullable Job had, StaffPlan.Task t, @Nullable Group g, long now) {
        int[] ids = g == null ? new int[0] : g.ids();
        boolean same = had != null && had.kind == t.kind() && (g == null ? had.ids.length == 0 : g.any(had.ids));
        // a sector scouted with no group known: the same one till it is looked through (given up there); a new one
        // turns the side's sectors on
        if (t.kind() == StaffPlan.Kind.SCOUT && g == null) {
            if (same) t = kept(s, had, t, false);
            else side.sector++;
        }
        // a place to stand at kept while its group stays about where it was (it is not picked again as the picture
        // and the squad move: a squad going round does not swap sides halfway); falling back, the place it went for
        if (same && had.place() && (g == null || Math.hypot(g.x() - had.gx, g.z() - had.gz) <= REROUTE)) t = kept(s, had, t, true);
        // falling back, the losses still counted from those it had before (not from those it has left)
        Job job = same ? had
                : new Job(t.kind(), now, t.kind() == StaffPlan.Kind.BACK && had != null ? had.bots : List.copyOf(s.bots), ids);
        // its contact and its being pinned down go on with its new task (C5): not reported again, not forgotten
        if (!same && had != null) {
            job.saidContact = had.saidContact;
            job.noFoeFrom = had.noFoeFrom;
            job.saidAmmo = had.saidAmmo;
            // falling back, the losses counted from the same soldiers
            if (job.bots == had.bots) job.saidLosses = had.saidLosses;
            job.pinFrom = had.pinFrom;
            job.pinnedAt = had.pinnedAt;
            job.quietFrom = had.quietFrom;
            job.aliveAtPin = job.alive() + Math.max(0, had.aliveAtPin - had.alive());
            job.breaking = had.breaking && t.kind() == StaffPlan.Kind.BACK;
        }
        if (!same || g != null && Math.hypot(g.x() - job.gx, g.z() - job.gz) > REROUTE) {
            job.gx = g == null ? t.x() : g.x();
            job.gz = g == null ? t.z() : g.z();
        }
        if (!same) {
            given[t.kind().ordinal()]++;
            if (t.kind() == StaffPlan.Kind.RESERVE) reserve++;
            else if (t.kind() != StaffPlan.Kind.BACK) orders++;
        }
        job.faceX = t.faceX();
        job.faceZ = t.faceZ();
        // given to the other part of a split squad already this time round: its members are both parts
        int i = 0;
        if (same && job.givenAt == now) {
            List<AiBot> both = new ArrayList<>(job.members);
            i = both.size();
            for (AiBot b : s.bots) if (!both.contains(b)) both.add(b);
            job.members = List.copyOf(both);
            for (AiBot b : job.members) if (b.order != null && b.order.job == job) b.order.of = both.size();
        } else job.members = List.copyOf(s.bots);
        job.givenAt = now;
        int of = job.members.size();
        Way way = t.kind() == StaffPlan.Kind.RESERVE ? null : way(level, team, s, t.x(), t.z());
        for (AiBot b : s.bots) {
            Order o = b.order != null && b.order.job == job ? b.order : new Order();
            o.job = job;
            o.x = t.x();
            o.z = t.z();
            o.r = t.r();
            o.lead = g == null ? -1 : g.lead();
            o.ids = ids;
            o.until = now + LASTS;
            o.slot = i++;
            o.of = of;
            if (o.way != way && (way == null || o.walked != way)) {
                o.way = way;
                o.route = null;
                o.onTail = o.noTail = false;
                o.tailFails = 0;
                o.noRoute = false;
                o.routeFails = 0;
                takeUp(o, b);
            } else if (o.noRoute) {
                o.route = null;
                o.onTail = false;
            }
            else if (o.route != null && far(o.route, o.at, b)) o.at = ahead(o.route, b.mob.getX(), b.mob.getZ());
            b.order = o;
        }
    }

    /** the task's place as the squad has it now (its face too, for a place) */
    private static StaffPlan.Task kept(AiSquads.Squad s, Job had, StaffPlan.Task t, boolean face) {
        for (AiBot b : s.bots)
            if (b.order != null && b.order.job == had)
                return new StaffPlan.Task(t.kind(), t.group(), b.order.x, b.order.z, b.order.r, face ? t.faceX() : b.order.x, face ? t.faceZ() : b.order.z);
        return t;
    }

    /** the group with one of these in it, null if none */
    private static @Nullable Group match(int[] ids, List<Group> groups) {
        for (Group g : groups) if (g.any(ids)) return g;
        return null;
    }

    /** the group half of the squad or more has an order for now, null if none (the stands count squads sent by it) */
    static @Nullable Group going(AiSquads.Squad s, List<Group> groups, long now) {
        // the groups in their order: of two alike, the first (the commander and the stands pick the same)
        Group best = null;
        int most = 0;
        for (Group g : groups) {
            int n = 0;
            for (AiBot b : s.bots) if (b.order != null && now <= b.order.until && match(b.order.ids, groups) == g) n++;
            if (n > most) {
                most = n;
                best = g;
            }
        }
        return most * 2 >= s.bots.size() ? best : null;
    }

    /** the group a squad is counted to: of its target while it fights one, else the one it goes to */
    static @Nullable Group counted(AiSquads.Squad s, List<Group> groups, long now) {
        if (s.target >= 0) for (Group x : groups) if (x.has(s.target)) return x;
        return going(s, groups, now);
    }

    /**
     * the squad gave this group up lately: half of it or more (one of the group's members was in the group they gave
     * up; squads are made again as they move, one that gave up may be in a new one with others that did not)
     */
    private static boolean failed(AiSquads.Squad s, Group g, long now) {
        int n = 0;
        for (AiBot b : s.bots) if (now < b.orderFailedUntil && g.any(b.orderFailed)) n++;
        return n * 2 >= s.bots.size();
    }

    /** the area for the group: its circle, as the side's picture has it now (AiIntel) */
    private static void give(ServerLevel level, String team, AiSquads.Squad s, Group g, long now, boolean fresh) {
        double x = g.x(), z = g.z(), r = g.r();
        if (fresh) orders++;
        int[] ids = g.ids();
        Way way = way(level, team, s, x, z);
        int i = 0;
        for (AiBot b : s.bots) {
            // the same group (one of its members still in it): the same order, its time in the area kept
            Order o = b.order != null && b.order.job == null && g.any(b.order.ids) ? b.order : new Order();
            o.x = x;
            o.z = z;
            o.r = r;
            o.lead = g.lead();
            o.ids = ids;
            o.until = now + LASTS;
            o.slot = i++;
            o.of = s.bots.size();
            // a new way (none, or the area moved on): taken up afresh, straight legs given up on the last one left
            if (o.way != way && (way == null || o.walked != way)) {
                o.way = way;
                o.route = null;
                o.onTail = o.noTail = false;
                o.tailFails = 0;
                o.noRoute = false;
                o.routeFails = 0;
                takeUp(o, b);
            } else if (o.noRoute) {
                o.route = null;
                o.onTail = false;
            }
            else if (o.route != null && far(o.route, o.at, b)) o.at = ahead(o.route, b.mob.getX(), b.mob.getZ());
            b.order = o;
        }
    }

    /**
     * The squad's way to the area (stage 7 C1): the one it has (or waits for) while the area is about where it was
     * asked to go, else a new one looked for off the server thread over the staff's map (null: no staff, no map yet,
     * none left to ask for this time: straight legs as R5)
     */
    private static @Nullable Way way(ServerLevel level, String team, AiSquads.Squad s, double x, double z) {
        if (!staff()) return null;
        for (AiBot b : s.bots) {
            if (b.order == null) continue;
            // the one it goes by or waits for; or one walked to its end (not one that ended short, at the map's edge:
            // the map has moved on with the squad since)
            Way w = b.order.way != null ? b.order.way : b.order.walked;
            if (w == null || w == b.order.walked && w.route != null && w.route.partial()) continue;
            if (Math.hypot(w.toX - x, w.toZ - z) <= REROUTE) return w;
        }
        HqGrid grid = AiHqMap.of(level, team);
        if (grid == null || waysLeft <= 0) return null;
        waysLeft--;
        double sx = 0, sz = 0;
        for (AiBot b : s.bots) {
            sx += b.mob.getX() / s.bots.size();
            sz += b.mob.getZ() / s.bots.size();
        }
        Way w = new Way(x, z);
        double fx = sx, fz = sz;
        try {
            AiThreads.submit(() -> {
                try {
                    long t0 = System.nanoTime();
                    HqGrid.Route r = grid.route(fx, fz, x, z);
                    routeNanos.addAndGet(System.nanoTime() - t0);
                    if (r != null) {
                        routes.incrementAndGet();
                        if (made.size() < 1000) made.add(r);
                    }
                    w.site = r == null ? null : StaffCross.site(grid, r);
                    w.route = r;
                } finally {
                    w.done = true;
                }
            });
        } catch (RuntimeException x2) {
            return null;
        }
        return w;
    }

    /** the squad's way, once looked for: taken up from the point of it ahead of the soldier */
    /** the stands: stretches of a squad's own made past the staff's way; looked for in vain (water, a cliff, off the map) */
    static int tails, tailsNot;

    /** a stretch looked for in vain is not looked for again within this many ticks */
    static final int TAIL_AGAIN = 20;

    /**
     * the squad's own stretch on from where it is (its middle) to its area's middle, points LEG apart at most, when the
     * staff's map has it dry and with no cliff (Job.tail, made again when the area moved on REROUTE); else null
     */
    private static HqGrid.@Nullable Route tail(ServerLevel level, Job j, Order o, long tick) {
        if (j.tail != null && Math.hypot(j.tail.toX() - o.x, j.tail.toZ() - o.z) <= REROUTE) return j.tail;
        j.tail = null;
        if (j.tailNot != Long.MIN_VALUE && tick - j.tailNot < TAIL_AGAIN) return null;
        List<AiBot> of = StaffMove.squad(j);
        if (of.isEmpty()) return null;
        double mx = 0, mz = 0;
        for (AiBot b : of) {
            mx += b.mob.getX() / of.size();
            mz += b.mob.getZ() / of.size();
        }
        HqGrid grid = AiHqMap.of(level, AiSquads.team(of.get(0)));
        double len = Math.hypot(o.x - mx, o.z - mz);
        if (grid == null || len < 1 || !grid.dryStraight(mx, mz, o.x, o.z)) {
            tailsNot++;
            j.tailNot = tick;
            return null;
        }
        int n = Math.max(1, (int) Math.ceil(len / HqGrid.LEG));
        double[] points = new double[2 * n];
        for (int k = 1; k <= n; k++) {
            points[2 * k - 2] = mx + (o.x - mx) * k / n;
            points[2 * k - 1] = mz + (o.z - mz) * k / n;
        }
        j.tail = new HqGrid.Route(mx, mz, o.x, o.z, points, len, len, 0, 0);
        tails++;
        return j.tail;
    }

    private static void takeUp(Order o, AiBot b) {
        Way w = o.way;
        if (o.route != null || w == null || !w.done) return;
        o.route = w.route;
        o.at = o.route == null ? 0 : ahead(o.route, b.mob.getX(), b.mob.getZ());
        // the staff's crossing on it (the squad goes over by the drill: AiCrossing), unless already past its water;
        // one begun (it is past the near bank's point) is kept as it is
        if (o.crossKind != null && started(b, o)) return;
        StaffCross.Site c = o.route == null ? null : w.site;
        o.crossKind = null;
        if (c != null && !past(b.mob.getX(), b.mob.getZ(), c.x(), c.z(), c.farX(), c.farZ())) {
            o.crossX = c.x();
            o.crossZ = c.z();
            o.farX = c.farX();
            o.farZ = c.farZ();
            o.crossKind = c.kind();
        }
        // its first leg at once, not when the straight one walked meanwhile is due again
        if (o.route != null) o.nextLeg = Long.MIN_VALUE;
    }

    /** the point of a way a soldier here goes to next: the end of the stretch of the way nearest it */
    static int ahead(HqGrid.Route r, double x, double z) {
        int best = 0;
        double bestD = Double.MAX_VALUE, px = r.fromX(), pz = r.fromZ();
        for (int j = 0; j < r.size(); j++) {
            double qx = r.x(j), qz = r.z(j), lx = qx - px, lz = qz - pz, l2 = lx * lx + lz * lz;
            double t = l2 == 0 ? 1 : Mth.clamp(((x - px) * lx + (z - pz) * lz) / l2, 0, 1);
            double d = Math.hypot(px + lx * t - x, pz + lz * t - z);
            if (d < bestD) {
                bestD = d;
                best = j;
            }
            px = qx;
            pz = qz;
        }
        return best;
    }

    /** a soldier farther than a leg from the point of the way it is going to (left it in a fight, or a new squad's) */
    private static boolean far(HqGrid.Route r, int at, AiBot b) {
        return Math.hypot(r.x(at) - b.mob.getX(), r.z(at) - b.mob.getZ()) > HqGrid.LEG;
    }

    /**
     * its enemies' groups, as its side's picture has them (AiIntel): of the mobs, only groups one of which thinks now
     * (near a player): the side's squads go for none farther (those stand, AiBots); a group all of which are gone
     * since the report is none
     */
    static List<Group> groups(ServerLevel level, String team) {
        List<Group> out = new ArrayList<>();
        for (Group g : AiIntel.groups(level, team)) if (g.known().awake && g.alive()) out.add(g);
        return out;
    }

    /** it has an order to go by now */
    static boolean has(AiBot bot, long tick) {
        return on() && bot.order != null && tick <= bot.order.until;
    }

    /**
     * its order now if its way crosses water ahead of it (the staff's crossing: crossX/Z the near bank's point,
     * crossKind how, farX/Z where to gather past the far bank), else null; once past the far bank, null for good
     */
    static @Nullable Order crossing(AiBot bot, long tick) {
        Order o = bot.order;
        if (o == null || o.crossKind == null || !has(bot, tick)) return null;
        if (past(bot.mob.getX(), bot.mob.getZ(), o.crossX, o.crossZ, o.farX, o.farZ)) {
            o.crossKind = null;
            // on along its way from where it is now; the last of the squad over: its way of going begun afresh there
            // (its bounds' halves and covers were of the near bank)
            if (o.route != null) o.at = ahead(o.route, bot.mob.getX(), bot.mob.getZ());
            Job j = o.job;
            if (j != null) {
                boolean all = true;
                for (AiBot b : j.members) if (b.mob.isAlive() && b.order != null && b.order.job == j && b.order.crossKind != null) all = false;
                if (all) StaffMove.afresh(j);
            }
            return null;
        }
        return o;
    }

    /**
     * one come into a squad crossing a river with its own order or none (the squads are made again as they move): the
     * crossing's order, as one of its squad has it. Short of the far bank's gathering place it goes over by the drill
     * (AiCrossing: at the near bank with the main body, or with the last; on the far bank to a place there, held with its
     * own); past it already, on along the way. Not one that gave up an order lately (AVOID), nor one in another crossing.
     */
    private static void join(AiBot b, Job cj, long now) {
        if (b.orderFailedUntil > now || AiCrossing.of(b) != null) return;
        Order by = null;
        for (AiBot c : cj.members) if (c.order != null && c.order.job == cj && c.order.crossKind != null) by = c.order;
        if (by == null) return;
        Order o = new Order();
        o.job = cj;
        o.x = by.x;
        o.z = by.z;
        o.r = by.r;
        o.lead = by.lead;
        o.ids = by.ids;
        o.until = now + LASTS;
        if (!cj.members.contains(b)) {
            List<AiBot> members = new ArrayList<>(cj.members);
            members.add(b);
            cj.members = List.copyOf(members);
        }
        o.slot = cj.members.indexOf(b);
        o.of = cj.members.size();
        o.way = by.way;
        takeUp(o, b);
        b.order = o;
        joinedCrossing++;
    }

    /** the war check's: 1 the squad's task kept as it crosses (its staff's round passes it by), 2 the squad has a task not this one's, 0 else */
    static int passedBy(AiSquads.Squad s, AiBot b, long now) {
        Job j = job(s, now);
        if (j == null) return 0;
        if (crossing(j)) return 1;
        return b.order == null || b.order.job != j ? 2 : 0;
    }

    /** one of the job's squad has begun its crossing and is not over yet */
    private static boolean crossing(Job j) {
        for (AiBot b : j.members)
            if (b.mob.isAlive() && b.order != null && b.order.job == j && b.order.crossKind != null && started(b, b.order)) return true;
        return false;
    }

    /** past the near bank's point of its crossing, toward the far bank */
    private static boolean started(AiBot b, Order o) {
        return ((b.mob.getX() - o.crossX) * (o.farX - o.crossX) + (b.mob.getZ() - o.crossZ) * (o.farZ - o.crossZ)) > 0;
    }

    /** at x, z: as far along from the crossing's point toward the gathering place as it is, or farther */
    static boolean past(double x, double z, double cx, double cz, double fx, double fz) {
        double dx = fx - cx, dz = fz - cz, len2 = dx * dx + dz * dz;
        return len2 < 1e-9 || ((x - cx) * dx + (z - cz) * dz) >= len2;
    }

    /**
     * a squad of the side reported (AiCrossing: an enemy across water, places to cross found): its staff looks at its
     * tasks again soon
     */
    public static void reported(ServerLevel level, String team) {
        Side side = LEVELS.computeIfAbsent(level, l -> new HashMap<>()).get(team);
        if (side == null) return;
        long now = level.getGameTime();
        if (side.asked == Long.MIN_VALUE) side.asked = now;
        // at once, but its orders given again ASKED_GAP apart at least (reports come in bunches in a fight)
        side.next = Math.min(side.next, Math.max(now + 1, side.commanded == Long.MIN_VALUE ? now + 1 : side.commanded + ASKED_GAP));
    }

    /**
     * The squads' reports (C5), every LOOK ticks: contact, losses, pinned down (most of it suppressed PIN_AFTER ticks;
     * over once none has been for PIN_QUIET), out of rounds, at its place. Each once for its task (losses at a third,
     * at half; pinned again after it was over).
     */
    private static void look(ServerLevel level, long now) {
        for (AiSquads.Squad s : AiSquads.squads(level)) {
            Job j = job(s, now);
            if (j == null) continue;
            String team = AiSquads.team(s.bots.get(0));
            int id = j.bots.get(0).mob.getId(), alive = 0, held = 0, empty = 0;
            double x = 0, z = 0;
            for (AiBot b : s.bots) {
                if (!b.mob.isAlive()) continue;
                alive++;
                x += b.mob.getX();
                z += b.mob.getZ();
                if (b.prone.suppressed() >= SUPPRESSED) held++;
                if (!b.gunner.ammo.infinite() && b.gunner.ammo.reserve() <= 0 && b.gunner.capacity() > 0) empty++;
            }
            if (alive == 0) continue;
            x /= alive;
            z /= alive;
            // contact: once, again only after PIN_QUIET with no foe (a foe lost from sight a moment is the same fight)
            if (s.target >= 0) {
                j.noFoeFrom = Long.MIN_VALUE;
                if (!j.saidContact) {
                    j.saidContact = true;
                    AiReports.report(level, team, new AiReports.Report(id, now, AiReports.Kind.CONTACT, x, z, s.target));
                }
            } else if (j.noFoeFrom == Long.MIN_VALUE) j.noFoeFrom = now;
            else if (now - j.noFoeFrom >= PIN_QUIET) j.saidContact = false;
            int left = j.alive(), was = j.bots.size(), losses = left * 2 < was ? 2 : left * 3 <= was * 2 ? 1 : 0;
            if (losses > j.saidLosses) {
                j.saidLosses = losses;
                AiReports.report(level, team, new AiReports.Report(id, now, AiReports.Kind.LOSSES, x, z, losses));
            }
            if (held * 2 >= alive) {
                j.quietFrom = Long.MIN_VALUE;
                if (j.pinFrom == Long.MIN_VALUE) j.pinFrom = now;
                if (j.pinnedAt == Long.MIN_VALUE && now - j.pinFrom >= PIN_AFTER) {
                    j.pinnedAt = now;
                    j.aliveAtPin = left;
                    AiReports.report(level, team, new AiReports.Report(id, now, AiReports.Kind.PINNED, x, z, held));
                }
            } else {
                j.pinFrom = Long.MIN_VALUE;
                if (held > 0) j.quietFrom = Long.MIN_VALUE;
                else if (j.quietFrom == Long.MIN_VALUE) j.quietFrom = now;
                // over once quiet a while; one that broke contact stays back REST
                boolean over = j.breaking ? now - j.pinnedAt >= REST : j.quietFrom != Long.MIN_VALUE && now - j.quietFrom >= PIN_QUIET;
                if (j.pinnedAt != Long.MIN_VALUE && over) {
                    j.pinnedAt = Long.MIN_VALUE;
                    j.breaking = false;
                }
            }
            if (!j.saidAmmo && empty * 2 > alive) {
                j.saidAmmo = true;
                AiReports.report(level, team, new AiReports.Report(id, now, AiReports.Kind.AMMO, x, z, empty));
            } else if (empty == 0) j.saidAmmo = false;
            if (!j.saidThere && j.there) {
                j.saidThere = true;
                AiReports.report(level, team, new AiReports.Report(id, now, AiReports.Kind.THERE, x, z, 0));
            }
        }
    }

    /**
     * A soldier that knows of no one, with an order (AiSoldier): to the area in legs, then through it, a place after
     * another, its eyes doing the rest. True while it goes by the order; false when it has none, or gave the group up.
     */
    static boolean step(AiBot bot, ServerLevel level, long tick) {
        Order o = bot.order;
        if (!has(bot, tick)) return false;
        double dx = o.x - bot.mob.getX(), dz = o.z - bot.mob.getZ(), d = Math.hypot(dx, dz);
        // in an area once it is within half the radius, out again past the whole of it; at a place (C2) within its
        // radius, away again past twice that
        boolean place = o.job != null && o.job.place();
        boolean there = place ? d <= o.r || o.lastInside == tick - 1 && d <= o.r * 2 : d <= o.r * 0.5 || o.lastInside == tick - 1 && d <= o.r;
        bot.gunner.fire(false);
        if (!there) {
            o.stood = false;
            boolean moves = o.job != null && StaffMove.on();
            if (moves) StaffMove.update(level, o, tick);
            boolean stuck = bot.mover.failed();
            // a leg of its squad's bounds or rushes that could not be walked (a cover cell out of reach): for those to
            // see to (StaffMove), not counted against the order or the way
            boolean coverStuck = stuck && o.moveLeg;
            if (coverStuck) {
                stuck = false;
                o.moveLeg = false;
            }
            // a leg's time out, it not there and no PROGRESS nearer the area than when the leg began (round and round
            // a house with no way in, its point on the roof: the leg's cell a block off each time, no try ever spent):
            // as a leg that could not be walked
            // (a leg of 2 PROGRESS or more: a short one's end is as near as its goal's radius and the place snapped to)
            if (!stuck && unstuck && !o.moveLeg && tick >= o.nextLeg && o.legD >= 2 * PROGRESS && o.legD != Double.MAX_VALUE && !bot.mover.arrived()
                    && Math.hypot(o.legTX - bot.mob.getX(), o.legTZ - bot.mob.getZ()) > o.legD - PROGRESS) stuck = true;
            // a leg that could not be walked (the map not built that far yet, or no way): counted only when it is no
            // nearer than at the last one
            if (stuck) {
                if (o.failedAt - d >= PROGRESS) o.fails = 0;
                o.failedAt = d;
                // (it or one of its squad sent there was in the area a moment ago: the place is to be got to, its own
                // in the way at a narrow doorway; the leg is turned aside as one not walked, the place kept)
                if (mateIn(bot, o, tick)) o.fails = Math.min(o.fails, FAILS - 2);
                if (++o.fails >= FAILS) return giveUp(bot, o, tick);
            }
            // the staff's way: to its points one after another, each a short walk (C1); a leg that fails is walked
            // round as R5's are, and the way taken up again from where it is then
            if (o.route == null && !o.noRoute) takeUp(o, bot);
            // the staff's way given up (its legs could not be walked) or walked to its end short of the area (it did not
            // reach that far, or the area moved on): the squad's way of going (its bounds) kept on to the area, along a
            // straight stretch with no water to swim and no cliff on the staff's map; a river is crossed only by the
            // staff's order. Else straight legs, as before
            if (o.route == null && moves && !o.noTail && (o.noRoute || o.way == null && o.walked != null) && o.job.move.mode != null && staff()) {
                HqGrid.Route t = tail(level, o.job, o, tick);
                if (t != null) {
                    o.route = t;
                    o.at = ahead(t, bot.mob.getX(), bot.mob.getZ());
                    o.onTail = true;
                }
            }
            HqGrid.Route way = o.route;
            if (way != null && staff()) {
                int was = o.at;
                while (o.at < way.size() - 1 && Math.hypot(way.x(o.at) - bot.mob.getX(), way.z(o.at) - bot.mob.getZ()) <= REACHED) o.at++;
                // a point that could not be walked to: the next one; again and again, the way is left for straight legs
                if (o.onTail && stuck && ++o.tailFails >= ROUTE_FAILS) {
                    // its own stretch could not be walked: straight legs for the rest of it, the staff's ways untouched
                    o.route = null;
                    o.onTail = false;
                    o.noTail = true;
                } else if (!o.onTail && stuck && ++o.routeFails >= ROUTE_FAILS) {
                    o.route = null;
                    o.noRoute = true;
                    // the straight legs counted from here
                    o.fails = 0;
                    o.failedAt = Double.MAX_VALUE;
                } else {
                    if (stuck) o.at = Math.min(o.at + 1, way.size() - 1);
                    if (bot.mover.arrived()) o.routeFails = o.tailFails = 0;
                    // the end of the way (short of the area when that was off the staff's map, or the area moved on
                    // less than REROUTE): on from there in straight legs
                    if (o.at == way.size() - 1 && Math.hypot(way.x(o.at) - bot.mob.getX(), way.z(o.at) - bot.mob.getZ()) <= REACHED) {
                        o.route = null;
                        if (o.onTail) {
                            o.onTail = false;
                            o.noTail = true;
                        } else {
                            o.walked = o.way;
                            o.way = null;
                        }
                    } else {
                        StaffMove.Leg leg = moves ? StaffMove.leg(bot, level, o, way, tick, coverStuck) : null;
                        if (leg != null) {
                            leg(bot, level, o, leg, stuck, o.at != was, tick);
                            return true;
                        }
                        if (tick >= o.nextLeg || bot.mover.arrived() || stuck || o.at != was) {
                            if (bot.mover.arrived()) o.fails = 0;
                            o.nextLeg = tick + LEG_AGAIN;
                            double a = Math.PI * 2 * o.slot / Math.max(1, o.of), spread = o.of > 1 ? 3 : 0;
                            leg(o, bot, way.x(o.at) + Math.cos(a) * spread, way.z(o.at) + Math.sin(a) * spread);
                            // no place to stand round the way's point (in a wall, in water too deep): the next point
                            if (!go(bot, level, way.x(o.at) + Math.cos(a) * spread, way.z(o.at) + Math.sin(a) * spread, moves ? StaffMove.speed(o) : AiSoldier.WALK)) {
                                o.at = Math.min(o.at + 1, way.size() - 1);
                                o.nextLeg = tick + 1;
                            }
                        }
                        if (moves) StaffMove.pace(bot, o, tick);
                        return true;
                    }
                }
            }
            if (tick >= o.nextLeg || bot.mover.arrived() || stuck) {
                if (bot.mover.arrived()) o.fails = 0;
                // legs not walked to their ends (failed, or out of time), none of those since it got nearest the area
                // bringing it PROGRESS nearer (round and round a house with no way in; the legs turned aside it walks
                // to their ends in between count neither way): given up at IDLE_LEGS
                if (unstuck) {
                    if (d <= o.bestD - PROGRESS) {
                        o.bestD = d;
                        o.idleLegs = 0;
                    // (one of its squad sent to the same place was in it a moment ago: the place is to be got to, this
                    // one waits its turn, at a narrow doorway its own in the way)
                    } else if (mateIn(bot, o, tick)) o.idleLegs = 0;
                    else if (!bot.mover.arrived() && ++o.idleLegs >= IDLE_LEGS) return giveUp(bot, o, tick);
                }
                o.nextLeg = tick + LEG_AGAIN;
                // a leg that could not be walked: the next one shorter and turned aside, one way then the other
                double turn = o.fails == 0 ? 0 : (o.fails % 2 == 1 ? 1 : -1) * Math.PI / 4 * ((o.fails + 1) / 2);
                double step = Math.min(d, o.fails == 0 ? LEG : LEG / 2), along = Math.atan2(dz, dx) + turn;
                // the squad spread a little at the leg's end, each its own side
                double a = Math.PI * 2 * o.slot / Math.max(1, o.of), spread = o.of > 1 ? 3 : 0;
                leg(o, bot, bot.mob.getX() + Math.cos(along) * step + Math.cos(a) * spread, bot.mob.getZ() + Math.sin(along) * step + Math.sin(a) * spread);
                // no place to stand at the leg's end: counted as a leg not walked (the next one turned aside), soon
                if (!go(bot, level, bot.mob.getX() + Math.cos(along) * step + Math.cos(a) * spread, bot.mob.getZ() + Math.sin(along) * step + Math.sin(a) * spread,
                        moves ? StaffMove.speed(o) : AiSoldier.WALK)) {
                    o.fails++;
                    o.nextLeg = tick + 1;
                }
            }
            if (moves) StaffMove.pace(bot, o, tick);
            return true;
        }
        o.lastInside = tick;
        o.fails = 0;
        o.idleLegs = 0;
        // at its place: it stands there facing the group (not looking through an area, not giving it up)
        if (place) {
            o.job.there = true;
            if (o.job.kind == StaffPlan.Kind.FLANK) ready(level, o.job, tick);
            if (!o.stood) {
                o.stood = true;
                bot.mover.stop();
            }
            boolean face = Math.hypot(o.job.faceX - bot.mob.getX(), o.job.faceZ - bot.mob.getZ()) > 2;
            if (bot.mob.isAlive()) bot.gunner.aim(face ? new net.minecraft.world.phys.Vec3(o.job.faceX, bot.mob.getEyeY(), o.job.faceZ) : null);
            return true;
        }
        if (++o.inside > GIVE_UP) return giveUp(bot, o, tick);
        if (tick >= o.nextLeg || bot.mover.arrived() || bot.mover.failed()) {
            // a place it could not get to: none looked for near it again for the rest of the order
            if (bot.mover.failed() && bot.mover.goal() != Long.MIN_VALUE) {
                if (o.badPlaces.size() >= BAD_PLACES) o.badPlaces.remove(0);
                o.badPlaces.add(new double[] {BlockPos.getX(bot.mover.goal()) + 0.5, BlockPos.getZ(bot.mover.goal()) + 0.5});
            }
            o.nextLeg = tick + LOOK_AGAIN;
            var rng = bot.mob.getRandom();
            double px = o.x, pz = o.z;
            for (int k = 0; k < PLACE_TRIES; k++) {
                double a = rng.nextDouble() * Math.PI * 2, r = Math.sqrt(rng.nextDouble()) * o.r;
                px = o.x + Math.cos(a) * r;
                pz = o.z + Math.sin(a) * r;
                if (!bad(o, px, pz)) break;
            }
            // no place to stand there: another soon
            if (!go(bot, level, px, pz, AiSoldier.WALK)) o.nextLeg = tick + 1;
        }
        return true;
    }

    /** the group given up: not again for AVOID (its squad's next order is another) */
    private static boolean giveUp(AiBot bot, Order o, long tick) {
        // its squad cannot get through (C5): reported once for its task
        Job j = o.job;
        if (j != null && !j.saidBlocked && bot.mob.level() instanceof ServerLevel level) {
            j.saidBlocked = true;
            AiReports.report(level, AiSquads.team(bot), new AiReports.Report(j.bots.get(0).mob.getId(), tick, AiReports.Kind.BLOCKED, bot.mob.getX(), bot.mob.getZ(), 0));
        }
        bot.orderFailed = o.ids;
        bot.orderFailedUntil = tick + AVOID;
        bot.order = null;
        gaveUp++;
        return false;
    }

    /**
     * a step by its squad's mode (C3): held in its cover facing the enemy, or a leg to where the mode has it go, set
     * again when that is new (or moved on), when it could not be walked, or when due and not there yet
     */
    private static void leg(AiBot bot, ServerLevel level, Order o, StaffMove.Leg leg, boolean stuck, boolean on, long tick) {
        // the mode's legs (a bound, a file on the march) are none of the staff's straight legs: their time out with no way
        // made is the mode's (one waits for the others), not a leg failed
        o.legD = Double.MAX_VALUE;
        if (leg.low() && bot.brain instanceof AiSoldier s) s.crouch(true);
        if (leg.hold()) {
            if (bot.mover.goal() != Long.MIN_VALUE) bot.mover.stop();
            StaffMove.face(bot, o);
            return;
        }
        boolean moved = !(Math.hypot(leg.x() - o.legX, leg.z() - o.legZ) <= 1);
        if (!(leg.fresh() || stuck || on && moved || bot.mover.goal() == Long.MIN_VALUE && moved || tick >= o.nextLeg && (moved || !bot.mover.arrived()))) return;
        if (bot.mover.arrived()) o.fails = 0;
        o.nextLeg = tick + leg.again();
        o.legX = leg.x();
        o.legZ = leg.z();
        if (leg.cell() == Long.MIN_VALUE) {
            if (!go(bot, level, leg.x(), leg.z(), leg.speed())) o.nextLeg = tick + 1;
        }
        else {
            bot.mover.costs(PathSearch.Cost.NONE);
            bot.mover.goTo(leg.cell(), 0.35, leg.speed());
            if (bot.mob.isAlive()) bot.gunner.aim(new net.minecraft.world.phys.Vec3(leg.x(), BlockPos.getY(leg.cell()) + 1.5, leg.z()));
        }
        o.moveLeg = true;
    }

    /**
     * A walk (a run, a creep) to the ground at x, z (where the chunk there is not loaded: at its own height): to a cell
     * one stands in there (the top the sky sees may be a wall's or a fence's top, with none), down the column a few
     * blocks, else the nearest beside it within SNAP. Whether it goes: false where the map has those blocks and none of
     * them is one to stand in (the caller takes another place). Whether it gets there is the path's to tell.
     */
    private static boolean go(AiBot bot, ServerLevel level, double x, double z, double speed) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        long cell;
        if (!level.hasChunk(bx >> 4, bz >> 4)) cell = BlockPos.asLong(bx, bot.mob.getBlockY(), bz);
        else if (!snap) cell = BlockPos.asLong(bx, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz), bz);
        else {
            cell = stand(level, bx, bz, bot.mob.getBlockY());
            if (cell == Long.MIN_VALUE) return false;
        }
        bot.mover.costs(PathSearch.Cost.NONE);
        bot.mover.goTo(cell, 2, speed);
        if (bot.order != null) bot.order.moveLeg = false;
        if (bot.mob.isAlive()) bot.gunner.aim(new net.minecraft.world.phys.Vec3(x, BlockPos.getY(cell) + 1.5, z));
        return true;
    }

    /** a place to stand looked for this far round x, z, blocks; this far down from the top the sky sees */
    static final int SNAP = 2, DOWN = 4;
    /** a place this much over or under the soldier is at about its height, blocks */
    static final int LEVEL = 2;
    /** off only for the stands' "before" (AiCheckWall): the top the sky sees taken as it is */
    static boolean snap = true;
    /** off only for the stands' "before" (AiCheckWall): a leg's time out with no way made is no leg failed */
    static boolean unstuck = true;
    /** straight legs one after another with no PROGRESS on the nearest it got to its area: the area given up */
    static final int IDLE_LEGS = 8;

    /** it, or one of its side with an order to the same area, was in it within MATE_IN ticks */
    static final int MATE_IN = 2 * LEG_AGAIN;

    private static boolean mateIn(AiBot bot, Order o, long tick) {
        if (o.lastInside != Long.MIN_VALUE && tick - o.lastInside <= MATE_IN) return true;
        if (!(bot.mob.level() instanceof ServerLevel level)) return false;
        String team = AiSquads.team(bot);
        // (its side's, not its squad's alone: those sent there together need not be one squad)
        for (AiBot b : AiBots.bots(level)) {
            if (!AiSquads.team(b).equals(team)) continue;
            Order m = b.order;
            if (b == bot || m == null || !AiSquads.live(b) || Math.abs(m.x - o.x) > 1 || Math.abs(m.z - o.z) > 1) continue;
            if (m.lastInside != Long.MIN_VALUE && tick - m.lastInside <= MATE_IN) return true;
        }
        return false;
    }
    /** places it could not get to kept, and a place this near one is not taken; random places tried before one is taken anyway */
    static final int BAD_PLACES = 8, PLACE_TRIES = 4;
    static final double BAD_NEAR = 6;

    /**
     * The cell to stand in nearest x, z: in its column from the top the sky sees down to DOWN under the soldier's own
     * height, the one nearest that height (a house's floor, not its roof), else in the nearest column round it within
     * SNAP that has one; the top of x, z itself where the map has not been built there yet (the walk has it built);
     * Long.MIN_VALUE where it has and there is none.
     */
    static long stand(ServerLevel level, int bx, int bz, int ownY) {
        TacticalMap map = TacticalMap.of(level);
        int top0 = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        boolean unknown = false;
        // at about its own height (a step up or down: a house's floor, the ground round a wall) the nearest across;
        // else the nearest across at any height (a hill's top), nearest its own height
        long near = Long.MIN_VALUE, any = Long.MIN_VALUE;
        double nearD = Double.MAX_VALUE, anyD = Double.MAX_VALUE;
        for (int dx = -SNAP; dx <= SNAP; dx++)
            for (int dz = -SNAP; dz <= SNAP; dz++) {
                int x = bx + dx, z = bz + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int top = dx == 0 && dz == 0 ? top0 : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                double across = dx * dx + dz * dz;
                for (int y = top, low = Math.max(level.getMinY(), Math.min(top, ownY) - DOWN); y >= low; y--) {
                    SectionCells c = map.cellsAt(x, y, z);
                    if (c == null) {
                        unknown = true;
                        break;
                    }
                    if (c.find(x & 15, y & 15, z & 15) < 0) continue;
                    int dy = Math.abs(y - ownY);
                    if (dy <= LEVEL && across < nearD) {
                        nearD = across;
                        near = BlockPos.asLong(x, y, z);
                    }
                    double d = across * 1000 + dy;
                    if (d < anyD) {
                        anyD = d;
                        any = BlockPos.asLong(x, y, z);
                    }
                }
            }
        if (near != Long.MIN_VALUE) return near;
        if (any != Long.MIN_VALUE) return any;
        return unknown ? BlockPos.asLong(bx, top0, bz) : Long.MIN_VALUE;
    }

    /** a leg (not a bound's) set to x, z: its end and how far off it, for the next leg to tell whether it made way */
    private static void leg(Order o, AiBot bot, double x, double z) {
        o.legTX = x;
        o.legTZ = z;
        o.legD = Math.hypot(x - bot.mob.getX(), z - bot.mob.getZ());
    }

    /** a place near one it could not get to */
    private static boolean bad(Order o, double x, double z) {
        for (double[] p : o.badPlaces) if (Math.hypot(p[0] - x, p[1] - z) < BAD_NEAR) return true;
        return false;
    }

    /**
     * The pairs (C4): each new squad going round with the squad pinning its group, their time set (the way round at
     * PAIR_PACE, half as long again, PAIR_MORE more, PAIR_MOST at most); a pair whose time ran out counted.
     */
    private static void pair(List<AiSquads.Squad> squads, @Nullable HqGrid grid, long now) {
        List<Job> pins = new ArrayList<>(), flanks = new ArrayList<>();
        List<AiSquads.Squad> flankers = new ArrayList<>();
        for (AiSquads.Squad s : squads) {
            Job j = job(s, now);
            // asleep (moved by drift, not step): never there to signal, not paired
            if (j == null || asleep(s) && j.partner == null) continue;
            if (j.kind == StaffPlan.Kind.PIN) pins.add(j);
            else if (j.kind == StaffPlan.Kind.FLANK) {
                flanks.add(j);
                flankers.add(s);
            }
        }
        // a pair whose other is gone (its squad dead, its task another now) before it was ready: undone, to pair again
        for (List<Job> l : List.of(pins, flanks))
            for (Job j : l)
                if (j.partner != null && j.ready == Long.MIN_VALUE && (j.partner.partner != j || !pins.contains(j.partner) && !flanks.contains(j.partner))) {
                    j.partner = null;
                    j.due = Long.MAX_VALUE;
                }
        for (int i = 0; i < flanks.size(); i++) {
            Job f = flanks.get(i);
            if (f.partner == null) for (Job p : pins) {
                if (p.partner != null || !shares(p.ids, f.ids)) continue;
                AiSquads.Squad s = flankers.get(i);
                double sx = 0, sz = 0;
                for (AiBot b : s.bots) {
                    sx += b.mob.getX() / s.bots.size();
                    sz += b.mob.getZ() / s.bots.size();
                }
                AiBot first = s.bots.get(0);
                // its way there over the map (by a ford, round the hill), else the straight line by DETOUR
                double d = first.order == null ? 0 : Math.hypot(first.order.x - sx, first.order.z - sz) * HqGrid.DETOUR;
                if (grid != null && first.order != null) {
                    int ai = grid.cellX(sx), ak = grid.cellZ(sz), bi = grid.cellX(first.order.x), bk = grid.cellZ(first.order.z);
                    int[] w = ai < 0 || ak < 0 || bi < 0 || bk < 0 ? null : grid.path(ai, ak, bi, bk, 0);
                    if (w != null) d = Math.max(d, HqGrid.cells(w) * HqGrid.CELL);
                }
                f.partner = p;
                p.partner = f;
                f.pairedAt = p.pairedAt = now;
                f.due = p.due = now + Math.min(PAIR_MOST, (long) (d / PAIR_PACE * 1.5) + PAIR_MORE);
                paired++;
                break;
            }
        }
    }

    private static boolean contains(int[] a, int x) {
        for (int y : a) if (y == x) return true;
        return false;
    }

    private static boolean shares(int[] a, int[] b) {
        for (int x : a) for (int y : b) if (x == y) return true;
        return false;
    }

    /**
     * a pair's one going round in a fight with its group (its squad's target one of it) once round: within LEASH of
     * its place, or run into it within RUN_INTO of itself, as AiSoldier lets it fight (seen from afar on the way, it
     * goes on round), or shot at: ready (C4)
     */
    private static void couple(ServerLevel level, long now) {
        for (AiSquads.Squad s : AiSquads.squads(level)) {
            if (s.target < 0) continue;
            for (AiBot b : s.bots) {
                Job j = b.order != null && now <= b.order.until ? b.order.job : null;
                if (j != null && j.kind == StaffPlan.Kind.FLANK && j.partner != null && j.ready == Long.MIN_VALUE && contains(j.ids, s.target)
                        && (Math.hypot(b.mob.getX() - b.order.x, b.mob.getZ() - b.order.z) <= LEASH && round(b, j.partner, b.memory.contact(s.target))
                                || ranInto(b, b.memory.contact(s.target)) || shotAt(b, now))) {
                    ready(level, j, now);
                    break;
                }
            }
        }
    }

    /**
     * a pair's one going round there, or in a fight (C4), in its time: it signals (its first a sign to the enemy, the
     * pinning one's first a sign to come on), and its side gives its orders again now (it to advance, the other to keep
     * the group's places under fire)
     */
    private static void ready(ServerLevel level, Job f, long now) {
        if (f.ready != Long.MIN_VALUE || f.partner == null || !pairs() || now >= f.due) return;
        f.ready = f.partner.ready = now;
        coupled++;
        coupleTicks += now - f.pairedAt;
        for (AiBot b : f.members)
            if (b.mob.isAlive() && b.order != null && b.order.job == f) {
                b.gesture(AiPose.SIGNAL_POINT, AiVoice.SIGN_TICKS, now);
                Map<String, Side> sides = LEVELS.get(level);
                Side side = sides == null ? null : sides.get(AiSquads.team(b));
                if (side != null) side.next = now + 1;
                break;
            }
        for (AiBot b : f.partner.members)
            if (b.mob.isAlive() && b.order != null && b.order.job == f.partner) {
                b.gesture(AiPose.SIGNAL_COME, AiVoice.SIGN_TICKS, now);
                break;
            }
    }

    /**
     * pinning for a pair whose one going round is there (C4): it keeps the places of the group it lost under fire
     * (AiSquads.suppresses)
     */
    static boolean presses(AiBot bot, long tick) {
        Order o = bot.order;
        Job j = o == null || tick > o.until ? null : o.job;
        boolean p = j != null && j.kind == StaffPlan.Kind.PIN && j.ready != Long.MIN_VALUE && tick < j.due && pairs() && on();
        if (p) pressed++;
        return p;
    }

    /** the stands: times a pinning soldier kept a lost foe's places under fire for its pair (C4) */
    public static long pressed;

    /**
     * pinning or holding (C4), once within LEASH of its place (on the way there it is free): a foe lost farther than
     * LEASH from the place is not gone after, nor gone round
     */
    static boolean leashed(AiBot bot, int target, long tick) {
        Order o = bot.order;
        Job j = o == null || tick > o.until ? null : o.job;
        if (j == null || j.kind != StaffPlan.Kind.PIN && j.kind != StaffPlan.Kind.HOLD) return false;
        if (Math.hypot(bot.mob.getX() - o.x, bot.mob.getZ() - o.z) > LEASH) return false;
        TargetMemory.Contact c = bot.memory.contact(target);
        return c != null && Math.hypot(c.lostX - o.x, c.lostZ - o.z) > LEASH && pairs() && on();
    }

    /**
     * going round for a pair not yet ready, in its time, the foe it knows of farther than RUN_INTO: it goes on round by
     * the order rather than into a fight from where it is (C4; nearer, it fights: ready)
     */
    static boolean goesRound(AiBot bot, TargetMemory.Contact c, long tick) {
        Order o = bot.order;
        Job j = o == null || tick > o.until ? null : o.job;
        if (j == null || j.kind != StaffPlan.Kind.FLANK || j.partner == null || j.ready != Long.MIN_VALUE || tick >= j.due) return false;
        double x = c.state == TargetMemory.State.VISIBLE ? c.x : c.lostX, z = c.state == TargetMemory.State.VISIBLE ? c.z : c.lostZ;
        if (Math.hypot(x - bot.mob.getX(), z - bot.mob.getZ()) <= RUN_INTO) return false;
        // shot at lately: it fights back rather than walk on under fire
        return !shotAt(bot, tick) && pairs() && on();
    }

    /** a contact within RUN_INTO of the bot: it has run into it (C4: the one going round ready, fighting) */
    private static boolean ranInto(AiBot bot, TargetMemory.@Nullable Contact c) {
        if (c == null) return false;
        double x = c.state == TargetMemory.State.VISIBLE ? c.x : c.lostX, z = c.state == TargetMemory.State.VISIBLE ? c.z : c.lostZ;
        return Math.hypot(x - bot.mob.getX(), z - bot.mob.getZ()) <= RUN_INTO;
    }

    /**
     * near its place, the one going round round in fact: at its group (where it sees it, else the staff's picture of
     * it) READY_ANGLE or more off the pinning one's middle (its place made from an older picture, the group moved on:
     * it goes on to it); its group not known, or the pinning one all down: as before, its place enough
     */
    private static boolean round(AiBot bot, Job pin, TargetMemory.@Nullable Contact c) {
        if (c == null) return true;
        // out of its sight: where it went from sight goes stale as it walks on; the staff's picture of it instead
        Job f = bot.order == null ? null : bot.order.job;
        double gx = c.state == TargetMemory.State.VISIBLE ? c.x : f == null ? c.lostX : f.gx, gz = c.state == TargetMemory.State.VISIBLE ? c.z : f == null ? c.lostZ : f.gz;
        double px = 0, pz = 0;
        int n = 0;
        for (AiBot m : pin.members)
            if (m.mob.isAlive()) {
                px += m.mob.getX();
                pz += m.mob.getZ();
                n++;
            }
        if (n == 0) return true;
        double ax = px / n - gx, az = pz / n - gz, bx = bot.mob.getX() - gx, bz = bot.mob.getZ() - gz;
        double cos = (ax * bx + az * bz) / Math.max(1e-6, Math.hypot(ax, az) * Math.hypot(bx, bz));
        return cos <= Math.cos(READY_ANGLE);
    }

    private static boolean shotAt(AiBot bot, long now) {
        TargetMemory.Noise n = bot.memory.noise();
        return n != null && n.hurt && now - n.tick <= UNDER_FIRE;
    }

    /** the pairs on and the staff's map made (no map yet: the pairs' squads both pin till it is in) */
    static boolean mapped() {
        return pairs() && staff() && AiTerrain.ON;
    }

    /** /armsai info */
    public static String info() {
        StringBuilder t = new StringBuilder();
        for (StaffPlan.Kind k : StaffPlan.Kind.values()) t.append(t.length() == 0 ? "" : ", ").append(k.name().toLowerCase(java.util.Locale.ROOT)).append(' ').append(given[k.ordinal()]);
        return String.format(java.util.Locale.ROOT, "commanders: %d orders, %d times a squad held in reserve, %d sent where the side loses soldiers, %d groups given up, %d ways (%.2f ms each, off the server thread); tasks given: %s, %d to catch up with a squad, %d steps moved asleep; %s; %s", orders,
                reserve, helped, gaveUp, routes.get(), routes.get() == 0 ? 0 : routeNanos.get() / 1e6 / routes.get(), t, strays, drifted, AiIntel.info(), AiHqMap.info());
    }
}
