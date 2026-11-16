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
    /** the stands: steps the staff moved soldiers asleep, and those it could not (the ground, water, not loaded) */
    public static long drifted, driftBlocked;
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
        /** the pair's other (C4: pinning and going round, of the same group), its time up at due, the one going round there (or in a fight) at ready */
        @Nullable Job partner;
        long due = Long.MAX_VALUE, ready = Long.MIN_VALUE, pairedAt;
        boolean late;

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
        /** the staff's way there as asked for (stage 7 C1, the squad's one); null: straight, as R5 */
        @Nullable Way way;
        /** a way walked to its end: not taken up again (on from there in straight legs) */
        @Nullable Way walked;
        /** the way once taken up, the point of it it goes to next; null: straight (none yet, or none) */
        HqGrid.@Nullable Route route;
        int at;
        /** legs of the way that could not be walked, one after another; at ROUTE_FAILS it walks straight (as R5) for the rest of the order */
        int routeFails;
        boolean noRoute;
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
                    job == null ? 0 : job.since, group, job != null && job.there, avoid, asleep(s), pair && job.ready != Long.MIN_VALUE, pair ? job.due : Long.MAX_VALUE));
        }
        if (all == 0) return;
        sideX /= all;
        sideZ /= all;
        StaffPlan.Task[] plan = StaffPlan.plan(own, foes, AiHqMap.of(level, team), sideX, sideZ, Math.min(REACH, AiBots.radius() * 2), PER, now, side.sector);
        for (int i = 0; i < squads.size(); i++) task(level, team, side, squads.get(i), jobs.get(i), plan[i], plan[i].group() < 0 ? null : groups.get(plan[i].group()), now);
        if (pairs()) pair(squads, now);
        strays(level, team, squads, now);
    }

    /**
     * The side's soldiers asleep in squads of the staff's own (C2b), as AiSquads makes them of those awake: each grown
     * from one, the nearest of its side within AiSquads.NEAR of any in it, AiSquads.MAX at most; one alone is one too.
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
     * each to its own side of the point as awake (step), onto the middle of the block on the ground as the heightmap
     * has it, in loaded chunks only; not onto water or lava, nor into deep water on the staff's map (a ford a block deep it wades), nor up or down more
     * than DRIFT_CLIMB: then a step turned aside, one way then the other, and FAILS such in a row give the task up as
     * awake. None once at the task's place (got there, as awake), nor within DRIFT_STOP of a group of the enemy. Woken
     * (a player near), it goes on from where it is by its order as any other (step).
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
            if (way != null) {
                while (o.at < way.size() - 1 && Math.hypot(way.x(o.at) - x, way.z(o.at) - z) <= REACHED) o.at++;
                if (o.at < way.size() - 1 || Math.hypot(way.x(o.at) - x, way.z(o.at) - z) > REACHED) {
                    tx = way.x(o.at);
                    tz = way.z(o.at);
                }
            }
            double a = Math.PI * 2 * o.slot / Math.max(1, o.of), spread = o.of > 1 ? 3 : 0;
            tx += Math.cos(a) * spread;
            tz += Math.sin(a) * spread;
            double l = Math.hypot(tx - x, tz - z);
            if (l < 1e-3) continue;
            // the step turned aside after one that could not be made, one way then the other
            double along = Math.atan2(tz - z, tx - x) + (o.fails == 0 ? 0 : (o.fails % 2 == 1 ? 1 : -1) * Math.PI / 4 * ((o.fails + 1) / 2));
            double step = Math.min(DRIFT_STEP, l);
            int bx = Mth.floor(x + Math.cos(along) * step), bz = Mth.floor(z + Math.sin(along) * step);
            // a chunk not loaded: it waits there
            if (!level.hasChunk(bx >> 4, bz >> 4)) continue;
            int ny = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
            HqGrid grid = AiHqMap.of(level, team);
            int ci = grid == null ? -1 : grid.cellX(bx + 0.5), ck = grid == null ? -1 : grid.cellZ(bz + 0.5);
            boolean deep = ci >= 0 && ck >= 0 && grid.known[HqGrid.index(ci, ck)] && grid.deep[HqGrid.index(ci, ck)] >= 0.5;
            // into water a block deep (a ford) it wades; deeper water, or any other liquid, it does not go into
            var fluid = level.getFluidState(new BlockPos(bx, ny - 1, bz));
            boolean wet = !fluid.isEmpty() && (!fluid.is(net.minecraft.tags.FluidTags.WATER) || !level.getFluidState(new BlockPos(bx, ny - 2, bz)).isEmpty());
            if (deep || wet || Math.abs(ny - b.mob.getY()) > DRIFT_CLIMB) {
                driftBlocked++;
                if (++o.fails >= FAILS) giveUp(b, o, now);
                continue;
            }
            o.fails = 0;
            b.mob.snapTo(bx + 0.5, ny, bz + 0.5, (float) Math.toDegrees(Math.atan2(-Math.cos(along), Math.sin(along))), 0F);
            b.mob.setYHeadRot(b.mob.getYRot());
            drifted++;
        }
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
        job.members = List.copyOf(s.bots);
        Way way = t.kind() == StaffPlan.Kind.RESERVE ? null : way(level, team, s, t.x(), t.z());
        int i = 0;
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
            o.of = s.bots.size();
            if (o.way != way && (way == null || o.walked != way)) {
                o.way = way;
                o.route = null;
                o.noRoute = false;
                o.routeFails = 0;
                takeUp(o, b);
            } else if (o.noRoute) o.route = null;
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
                o.noRoute = false;
                o.routeFails = 0;
                takeUp(o, b);
            } else if (o.noRoute) o.route = null;
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
    private static void takeUp(Order o, AiBot b) {
        Way w = o.way;
        if (o.route != null || w == null || !w.done) return;
        o.route = w.route;
        o.at = o.route == null ? 0 : ahead(o.route, b.mob.getX(), b.mob.getZ());
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
            // a leg that could not be walked (the map not built that far yet, or no way): counted only when it is no
            // nearer than at the last one
            if (stuck) {
                if (o.failedAt - d >= PROGRESS) o.fails = 0;
                o.failedAt = d;
                if (++o.fails >= FAILS) return giveUp(bot, o, tick);
            }
            // the staff's way: to its points one after another, each a short walk (C1); a leg that fails is walked
            // round as R5's are, and the way taken up again from where it is then
            if (o.route == null && !o.noRoute) takeUp(o, bot);
            HqGrid.Route way = o.route;
            if (way != null && staff()) {
                int was = o.at;
                while (o.at < way.size() - 1 && Math.hypot(way.x(o.at) - bot.mob.getX(), way.z(o.at) - bot.mob.getZ()) <= REACHED) o.at++;
                // a point that could not be walked to: the next one; again and again, the way is left for straight legs
                if (stuck && ++o.routeFails >= ROUTE_FAILS) {
                    o.route = null;
                    o.noRoute = true;
                    // the straight legs counted from here
                    o.fails = 0;
                    o.failedAt = Double.MAX_VALUE;
                } else {
                    if (stuck) o.at = Math.min(o.at + 1, way.size() - 1);
                    if (bot.mover.arrived()) o.routeFails = 0;
                    // the end of the way (short of the area when that was off the staff's map, or the area moved on
                    // less than REROUTE): on from there in straight legs
                    if (o.at == way.size() - 1 && Math.hypot(way.x(o.at) - bot.mob.getX(), way.z(o.at) - bot.mob.getZ()) <= REACHED) {
                        o.route = null;
                        o.walked = o.way;
                        o.way = null;
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
                            go(bot, level, way.x(o.at) + Math.cos(a) * spread, way.z(o.at) + Math.sin(a) * spread, moves ? StaffMove.speed(o) : AiSoldier.WALK);
                        }
                        if (moves) StaffMove.pace(bot, o, tick);
                        return true;
                    }
                }
            }
            if (tick >= o.nextLeg || bot.mover.arrived() || stuck) {
                if (bot.mover.arrived()) o.fails = 0;
                o.nextLeg = tick + LEG_AGAIN;
                // a leg that could not be walked: the next one shorter and turned aside, one way then the other
                double turn = o.fails == 0 ? 0 : (o.fails % 2 == 1 ? 1 : -1) * Math.PI / 4 * ((o.fails + 1) / 2);
                double step = Math.min(d, o.fails == 0 ? LEG : LEG / 2), along = Math.atan2(dz, dx) + turn;
                // the squad spread a little at the leg's end, each its own side
                double a = Math.PI * 2 * o.slot / Math.max(1, o.of), spread = o.of > 1 ? 3 : 0;
                go(bot, level, bot.mob.getX() + Math.cos(along) * step + Math.cos(a) * spread, bot.mob.getZ() + Math.sin(along) * step + Math.sin(a) * spread,
                        moves ? StaffMove.speed(o) : AiSoldier.WALK);
            }
            if (moves) StaffMove.pace(bot, o, tick);
            return true;
        }
        o.lastInside = tick;
        o.fails = 0;
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
            o.nextLeg = tick + LOOK_AGAIN;
            var rng = bot.mob.getRandom();
            double a = rng.nextDouble() * Math.PI * 2, r = Math.sqrt(rng.nextDouble()) * o.r;
            go(bot, level, o.x + Math.cos(a) * r, o.z + Math.sin(a) * r, AiSoldier.WALK);
        }
        return true;
    }

    /** the group given up: not again for AVOID (its squad's next order is another) */
    private static boolean giveUp(AiBot bot, Order o, long tick) {
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
        if (leg.cell() == Long.MIN_VALUE) go(bot, level, leg.x(), leg.z(), leg.speed());
        else {
            bot.mover.costs(PathSearch.Cost.NONE);
            bot.mover.goTo(leg.cell(), 0.35, leg.speed());
            if (bot.mob.isAlive()) bot.gunner.aim(new net.minecraft.world.phys.Vec3(leg.x(), BlockPos.getY(leg.cell()) + 1.5, leg.z()));
        }
        o.moveLeg = true;
    }

    /** a walk (a run, a creep) to the ground at x, z (where the chunk there is not loaded: at its own height) */
    private static void go(AiBot bot, ServerLevel level, double x, double z, double speed) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        int y = level.hasChunk(bx >> 4, bz >> 4) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz) : bot.mob.getBlockY();
        bot.mover.costs(PathSearch.Cost.NONE);
        bot.mover.goTo(BlockPos.asLong(bx, y, bz), 2, speed);
        if (bot.order != null) bot.order.moveLeg = false;
        if (bot.mob.isAlive()) bot.gunner.aim(new net.minecraft.world.phys.Vec3(x, y + 1.5, z));
    }

    /**
     * The pairs (C4): each new squad going round with the squad pinning its group, their time set (the way round at
     * PAIR_PACE, half as long again, PAIR_MORE more, PAIR_MOST at most); a pair whose time ran out counted.
     */
    private static void pair(List<AiSquads.Squad> squads, long now) {
        List<Job> pins = new ArrayList<>(), flanks = new ArrayList<>();
        List<AiSquads.Squad> flankers = new ArrayList<>();
        for (AiSquads.Squad s : squads) {
            Job j = job(s, now);
            if (j == null) continue;
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
                double d = first.order == null ? 0 : Math.hypot(first.order.x - sx, first.order.z - sz);
                f.partner = p;
                p.partner = f;
                f.pairedAt = p.pairedAt = now;
                f.due = p.due = now + Math.min(PAIR_MOST, (long) (d * HqGrid.DETOUR / PAIR_PACE * 1.5) + PAIR_MORE);
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
     * its place or StaffPlan.FLANK_D of the group (seen from afar on the way, it goes on round): ready (C4)
     */
    private static void couple(ServerLevel level, long now) {
        for (AiSquads.Squad s : AiSquads.squads(level)) {
            if (s.target < 0) continue;
            for (AiBot b : s.bots) {
                Job j = b.order != null && now <= b.order.until ? b.order.job : null;
                if (j != null && j.kind == StaffPlan.Kind.FLANK && j.partner != null && j.ready == Long.MIN_VALUE && contains(j.ids, s.target)
                        && (Math.hypot(b.mob.getX() - b.order.x, b.mob.getZ() - b.order.z) <= LEASH || Math.hypot(b.mob.getX() - j.gx, b.mob.getZ() - j.gz) <= StaffPlan.FLANK_D)) {
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

    /** /armsai info */
    public static String info() {
        StringBuilder t = new StringBuilder();
        for (StaffPlan.Kind k : StaffPlan.Kind.values()) t.append(t.length() == 0 ? "" : ", ").append(k.name().toLowerCase(java.util.Locale.ROOT)).append(' ').append(given[k.ordinal()]);
        return String.format(java.util.Locale.ROOT, "commanders: %d orders, %d times a squad held in reserve, %d sent where the side loses soldiers, %d groups given up, %d ways (%.2f ms each, off the server thread); tasks given: %s, %d to catch up with a squad, %d steps moved asleep; %s; %s", orders,
                reserve, helped, gaveUp, routes.get(), routes.get() == 0 ? 0 : routeNanos.get() / 1e6 / routes.get(), t, strays, drifted, AiIntel.info(), AiHqMap.info());
    }
}
