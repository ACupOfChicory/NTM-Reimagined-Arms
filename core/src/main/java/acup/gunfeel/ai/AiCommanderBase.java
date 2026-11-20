package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.CoreServices;
import acup.gunfeel.GameMath;
import acup.gunfeel.LevelMap;
import acup.gunfeel.ai.AiIntelBase.Group;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.BodyControl;
import acup.gunfeel.host.EntityView;
import acup.gunfeel.host.WorldView;
import acup.gunfeel.water.LiquidEvents;
import acup.gunfeel.water.LiquidPath;
import acup.gunfeel.ReaimTimes;

/** A side's staff: its decisions and state; the version supplies orders and game access through Staff. */
public abstract class AiCommanderBase {
    protected AiCommanderBase() {
    }

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

    /** a soldier at red with none brought it this long fights with what it has: its squad not out for it; ticks */
    static final int RED_WAIT = 1200;

    static final float SUPPRESSED = 0.5F;

    /** a report's answer (the orders given again after it): ticks in all, how many, the longest (the stands') */
    public static long askedLag, askedN, askedMost;

    /** the last tick orders were given in answer to a report (the stands') */
    public static long answeredAt = Long.MIN_VALUE;

    /** squads scouting a group sent to advance on it once seen (the same task: no change; the war stand) */
    public static long scoutsFound;

    /** squads that broke contact pinned down (C5) */
    public static long broke;

    static final String REPORTS_KEY = "aiCommanderReports";

    static boolean reportsOn = true;

    /** the staff answers its squads' reports (C5: pinned down, broke contact); false: as before (the reports still made) */
    static boolean reports() {
        return reportsOn && ReaimTimes.flag(REPORTS_KEY, true);
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

    static final double DRIFT_STEP = 4, DRIFT_CLIMB = StaffGround.CLIMB, DRIFT_STOP = 48;

    /**
     * asleep: a point of its way in water, or before a stretch over water, reached this near only (not REACHED: it
     * would cut across beside the ford); no spread of the squad within DRIFT_WET of water; blocks
     */
    static final double DRIFT_AT_WATER = 1.5, DRIFT_WET = 8;

    /** asleep, a step into deep water: a way round looked for over the blocks within DETOUR, DETOUR_AGAIN ticks apart at most */
    static final int DETOUR = StaffGround.DETOUR, DETOUR_AGAIN = 100;

    /** new ways a side asks for at most each time it gives its orders (the rest keep theirs or go straight till the next) */
    static final int WAYS = 3;

    static final int ROUTE_FAILS = 3;

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

    static final int PAIR_MORE = 600, PAIR_MOST = StaffPlan.PAIR_MOST;

    /**
     * a pair made again (its other gone before it was ready) keeps the time it had; its other dead and the new one not
     * at its place yet: this long more at least, ticks (the new one is not late the moment it comes)
     */
    static final int PAIR_GRACE = 200;

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

    /** the stands: pairs made again, those whose time was the one before, up the moment they were made, their other dead (given PAIR_GRACE) */
    public static int pairedAgain, keptTime, upAtOnce, otherDied, graced;

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
    static class Job<B extends AiBotBase, J extends Job<B, J>> {
        /** what (a scout finding its group advances: the same task, AiCommander.task) */
        StaffPlan.Kind kind;
        final long since;
        /** a scout that found its group: when (it advances KEEP at least from then, StaffPlan; Long.MIN_VALUE: not) */
        long foundAt = Long.MIN_VALUE;
        final List<B> bots;
        final int[] ids;
        double gx, gz, faceX, faceZ;
        boolean there;
        /** the squad it is given to now (the staff's squads change as they move), in its order */
        List<B> members;
        /** how it goes along its way (C3) */
        final StaffMoveBase.State move = new StaffMoveBase.State();
        /** its own stretch on from where the staff's way ended to its area's middle, for its way of going; when it was last looked for in vain */
        HqGrid.@Nullable Route tail;
        long tailNot = Long.MIN_VALUE;
        /** the pair's other (C4: pinning and going round, of the same group), its time up at due, the one going round there (or in a fight) at ready */
        @Nullable J partner;
        long due = Long.MAX_VALUE, ready = Long.MIN_VALUE, pairedAt;
        /**
         * a pair it was in (or the task it had before at the same group was in) undone before it was ready (C4): that
         * pair's time (Long.MAX_VALUE: none), its other dead then
         */
        long dueWas = Long.MAX_VALUE;
        boolean otherDead;
        /** when the staff last gave it (to the squad, or to the parts of a squad split on the way) */
        long givenAt = Long.MIN_VALUE;
        boolean late;
        /**
         * its squad's reports (C5, AiReports): made already (contact, at red (AMMO), there, cannot get through); losses
         * reported (1 a third, 2 half); pinned down: most of it suppressed since, pinned since (reported), alive then
         */
        boolean saidContact, saidAmmo, saidThere, saidBlocked;
        /** falling back pinned down (C5): it breaks contact, smoke first (AiSoldier) */
        boolean breaking;
        /** given to help a squad of its side pinned down by the same group (C5: going round it or pinning it too) */
        boolean helps;
        int saidLosses, aliveAtPin;
        long pinFrom = Long.MIN_VALUE, pinnedAt = Long.MIN_VALUE, quietFrom = Long.MIN_VALUE, noFoeFrom = Long.MIN_VALUE;

        Job(StaffPlan.Kind kind, long since, List<B> bots, int[] ids) {
            this.kind = kind;
            this.since = since;
            this.bots = bots;
            this.ids = ids;
            this.members = bots;
        }

        /** of those it was given to, alive still */
        int alive() {
            int n = 0;
            for (B b : bots) if (b.body.alive()) n++;
            return n;
        }

        /** a task at a place (stand there), not an area to go through */
        boolean place() {
            return kind != StaffPlan.Kind.ADVANCE && kind != StaffPlan.Kind.SCOUT;
        }
    }

    /** a squad's order, a copy to each of it: the area, the group (its members' ids, its lead's), till when */
    static class Order<B extends AiBotBase, J extends Job<B, J>> {
        /** the staff's task it is for (C2; null: R5's, an area) */
        @Nullable J job;
        double x, z, r;
        int lead;
        int[] ids = new int[0];
        long until, nextLeg = Long.MIN_VALUE, lastInside = Long.MIN_VALUE;
        /** the tick it was given as a new order (one kept on, its time made longer, keeps it) */
        long givenAt = Long.MIN_VALUE;
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
        @Nullable CrossingKind crossKind;
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
        /** looking through its area: on the way to a place in it till then (round a house's corner, out past the radius, it is still there) */
        long lookUntil = Long.MIN_VALUE;
        /** the room the area's middle is in (AiRooms, read in the area, again when the area moved on): places looked through only in it; OUTSIDE: anywhere */
        AiRooms.@Nullable Plan rooms;
        int room = AiRooms.OUTSIDE, roomY;
        double roomX = Double.NaN, roomZ = Double.NaN;
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
        /** the stands' C3 trace: the tick of its last step by its squad's mode (StaffMove.leg), and whether that was a hold */
        long moveAt = Long.MIN_VALUE;
        boolean moveHold;
    }

    public static boolean on() {
        return on && ReaimTimes.flag(KEY, true);
    }

    static boolean tasks() {
        return tasks && ReaimTimes.flag(TASKS_KEY, true);
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

    /** squad {@code i}, not pinned down itself, given a way round or a pin at a group a squad of its side is pinned down by */
    static boolean helps(List<StaffPlan.Squad> own, StaffPlan.Task[] plan, int i) {
        StaffPlan.Task t = plan[i];
        if (t == null || t.group() < 0 || t.kind() != StaffPlan.Kind.FLANK && t.kind() != StaffPlan.Kind.PIN || own.get(i).pinned() != Long.MIN_VALUE) return false;
        for (int k = 0; k < own.size(); k++) if (k != i && own.get(k).pinned() != Long.MIN_VALUE && own.get(k).group() == t.group()) return true;
        return false;
    }

    /** the squad's way, once looked for: taken up from the point of it ahead of the soldier */
    /** the stands: stretches of a squad's own made past the staff's way; looked for in vain (water, a cliff, off the map) */
    static int tails, tailsNot;

    /** a stretch looked for in vain is not looked for again within this many ticks */
    static final int TAIL_AGAIN = 20;

    /** the point of a way a soldier here goes to next: the end of the stretch of the way nearest it */
    static int ahead(HqGrid.Route r, double x, double z) {
        int best = 0;
        double bestD = Double.MAX_VALUE, px = r.fromX(), pz = r.fromZ();
        for (int j = 0; j < r.size(); j++) {
            double qx = r.x(j), qz = r.z(j), lx = qx - px, lz = qz - pz, l2 = lx * lx + lz * lz;
            double t = l2 == 0 ? 1 : acup.gunfeel.GameMath.clamp(((x - px) * lx + (z - pz) * lz) / l2, 0, 1);
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

    /** whether an order was given as a new one after that tick (not one kept on from before it) */
    static boolean givenSince(@Nullable Order<?, ?> o, long since) {
        return o != null && o.givenAt > since;
    }

    /** at x, z: as far along from the crossing's point toward the gathering place as it is, or farther */
    static boolean past(double x, double z, double cx, double cz, double fx, double fz) {
        double dx = fx - cx, dz = fz - cz, len2 = dx * dx + dz * dz;
        return len2 < 1e-9 || ((x - cx) * dx + (z - cz) * dz) >= len2;
    }

    /**
     * a soldier at "red" (the field manual's ammunition status): a magazine or none left besides the gun's. Most of a
     * squad so, it is out (StaffPlan: back, as black was before) and says so (AMMO): one magazine sooner than empty, so
     * that a squad running dry one soldier after another is not sent on just before it falls back (the short war's
     * flaps, 2026-11-17). Its supplier fills one so as well (AiSupply.need)
     */
    /** the strength of a kind of soldier (a role's ordinal; PLAYER or any other: a player), for the staff's plan (StaffPlan) */
    static double weight(int kind) {
        AiRole[] roles = AiRole.values();
        if (kind < 0 || kind >= roles.length) return 3;
        return switch (roles[kind]) {
            case RIFLE -> 1;
            case BASE -> 1.5;
            case MARKSMAN, GRENADIER -> 1.2;
            case MEDIC -> 0.7;
            case SUPPLIER -> 0.8;
        };
    }

    /** a place to stand looked for this far round x, z, blocks; this far down from the top the sky sees */
    static final int SNAP = StaffGround.SNAP, DOWN = StaffGround.DOWN;

    /** a place this much over or under the soldier is at about its height, blocks */
    static final int LEVEL = StaffGround.LEVEL;

    /** off only for the stands' "before" (AiCheckWall): the top the sky sees taken as it is */
    static boolean snap = true;

    /** off only for the stands' "before" (AiCheckWall): a leg's time out with no way made is no leg failed */
    static boolean unstuck = true;

    /** straight legs one after another with no PROGRESS on the nearest it got to its area: the area given up */
    static final int IDLE_LEGS = 8;

    /** it, or one of its side with an order to the same area, was in it within MATE_IN ticks */
    static final int MATE_IN = 2 * LEG_AGAIN;

    /** places it could not get to kept, and a place this near one is not taken; random places tried before one is taken anyway */
    static final int BAD_PLACES = 8, PLACE_TRIES = 4;

    static final double BAD_NEAR = 6;

    /**
     * a new pair's time: its own (fresh), or the time one of the two had in a pair undone before it was ready, if
     * sooner; that pair's other dead and the new one not at its place yet, PAIR_GRACE from now at least
     */
    static long time(Job<?, ?> f, Job<?, ?> p, long fresh, long now) {
        long was = Math.min(f.dueWas, p.dueWas);
        // the one whose other died paired with a new one not at its place yet (the new one pinning still on its way)
        boolean dead = f.otherDead || p.otherDead, grace = f.otherDead && !p.there || p.otherDead && !f.there;
        // taken up by this pair: its time from now on is the pair's own (handed on while it is not over)
        f.otherDead = p.otherDead = false;
        f.dueWas = p.dueWas = Long.MAX_VALUE;
        if (was == Long.MAX_VALUE) return fresh;
        pairedAgain++;
        if (dead) otherDied++;
        long kept = grace ? Math.max(was, now + PAIR_GRACE) : was;
        if (kept >= fresh) return fresh;
        keptTime++;
        if (kept > was) graced++;
        if (kept <= now) upAtOnce++;
        return kept;
    }

    static boolean contains(int[] a, int x) {
        for (int y : a) if (y == x) return true;
        return false;
    }

    static boolean shares(int[] a, int[] b) {
        for (int x : a) for (int y : b) if (x == y) return true;
        return false;
    }

    /** the stands: times a pinning soldier kept a lost foe's places under fire for its pair (C4) */
    public static long pressed;


    /** What remains in 26.2 while the staff's decisions live in core. */
    interface Staff<B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> {
        /** 26.2: AiBot.order. */
        @Nullable O order(B b);
        /** 26.2: AiBot.order set. */
        void order(B b, @Nullable O o);
        /** 26.2: new AiCommander.Order. */
        O order();
        /** 26.2: new AiCommander.Job. */
        J job(StaffPlan.Kind kind, long since, List<B> bots, int[] ids);
        /** 26.2: new AiSquads.Squad. */
        AiSquadsBase.Squad<B> squad();
        /** 26.2: AiHqMap.of. */
        @Nullable HqGrid hq(Object level, String team);
        /** 26.2: AiHqMap.report. */
        void hqReport(Object level, String team, List<B> own, List<Group> groups);
        /** 26.2: AiReports.report. */
        void report(Object level, String team, AiReportsBase.Report r);
        /** 26.2: AiIntel.tick. */
        long intel(Object level, Map<String, List<B>> own, long now);
        /** 26.2: AiIntel.lost (the facade: the stands' former copy hears of the loss too). */
        void lost(Object level, String team, int id, long tick);
        /** 26.2: AiBots.radius. */
        double radius();
        /** 26.2: AiSupply.on and its OUT_KEY flag. */
        boolean supplyOn();
        /** 26.2: StaffMove.squad. */
        List<B> moveSquad(J j);
        /** 26.2: StaffMove.afresh. */
        void moveAfresh(J j);
        /** 26.2: AiCrossing.of is present. */
        boolean inCrossing(B b);
        /** 26.2: AiTerrain.ON. */
        boolean terrainOn();
        /** 26.2: Wiring.world. */
        WorldView world(Object level);
        /** 26.2: LevelEntities. */
        EntityView entities(Object level);
        /** 26.2: Wiring.liquids. */
        LiquidPath.Fluids fluids(Object level);
    }

    private static final BodyControl CONTROL = CoreServices.load(BodyControl.class, BodyControl.MISSING);
    static final byte SIGNAL_POINT = 3, SIGNAL_COME = 5;
    static final int SIGN_TICKS = 20;
    static int waysLeft;

    static void forgetLevel(Object level) {
        LEVELS.remove(level);
        reportDue.remove(level);
    }

    private static long dueAt(Object level) {
        Long due = reportDue.get(level);
        return due == null ? Long.MIN_VALUE : due;
    }

    @SuppressWarnings("unchecked")
    private static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> List<AiSquadsBase.Squad<B>> squadsIn(Staff<B, J, O> staff, Object level) {
        return (List<AiSquadsBase.Squad<B>>) (List<?>) AiSquadsBase.squadsOf(level);
    }

    @SuppressWarnings("unchecked")
    private static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> java.util.Collection<B> botsIn(Staff<B, J, O> staff, Object level) {
        return (java.util.Collection<B>) AiBotsBase.bots(level);
    }

    /** a side's own: its next orders */
    static final class Side {
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

    private static final LevelMap<Map<String, Side>> LEVELS = new LevelMap<>();
    /** the soonest a side's picture is due again, per level */
    private static final LevelMap<Long> reportDue = new LevelMap<>();

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> boolean breakingContact(Staff<B, J, O> staff, B bot, long tick) {
        O o = staff.order(bot);
        return o != null && tick <= o.until && o.job != null && o.job.kind == StaffPlan.Kind.BACK && o.job.breaking && on();
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> void lostBot(Staff<B, J, O> staff, Object level, B fallen, long tick) {
        if (!on()) return;
        Map<String, Side> sides = LEVELS.get(level);
        String team = AiSquadsBase.teamOf(fallen);
        if (sides == null || !sides.containsKey(team)) return;
        // the mob that hit it last, else whom the game credits (a player who hit it lately first)
        Body by = fallen.body.lastHurtBy();
        if (by == null || !AiTeamsBase.enemy(fallen.body, by)) by = fallen.body.killCredit();
        AiSquadsBase.Squad<B> s = AiSquadsBase.squadOf(fallen);
        if (by != null && by != fallen.body && AiTeamsBase.enemy(fallen.body, by)) staff.lost(level, team, by.id(), tick);
        else if (s != null && s.target >= 0) staff.lost(level, team, s.target, tick);
        else if (staff.order(fallen) != null && tick <= staff.order(fallen).until) for (int id : staff.order(fallen).ids) staff.lost(level, team, id, tick);
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> int groupRoom(Staff<B, J, O> staff, Group g, long now) {
        return help() && g.known().lost != Long.MIN_VALUE && now - g.known().lost <= HELP ? PER + HELP_MORE : PER;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> void tickStaff(Staff<B, J, O> staff, Object level) {
        tickStaff(staff, level, CONTROL);
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> void tickStaff(Staff<B, J, O> staff, Object level, BodyControl control) {
        if (!on()) return;
        long now = staff.world(level).gameTime();
        if (tasks() && now % DRIFT == 0) driftBots(staff, level, now, control);
        if (tasks() && pairs() && now % COUPLE == 0) coupleSquads(staff, level, now);
        if (tasks() && now % LOOK == 0) lookSquads(staff, level, now);
        Map<String, Side> sides = LEVELS.computeIfAbsent(level, k -> new HashMap<>());
        boolean due = sides.isEmpty() || now >= dueAt(level);
        for (Side side : sides.values()) due |= now >= side.next;
        // a side not met before is due at once; else nothing to do before the soonest one's time, orders or report (a
        // new side is found on the next one's, at most EVERY_MAX later)
        if (!due) return;
        Map<String, List<AiSquadsBase.Squad<B>>> byTeam = new HashMap<>();
        for (AiSquadsBase.Squad<B> s : squadsIn(staff, level)) {
            String team = AiSquadsBase.teamOf(s.bots.get(0));
            if (!team.isEmpty()) byTeam.computeIfAbsent(team, t -> new ArrayList<>()).add(s);
        }
        // with the staff's tasks, its soldiers asleep too, in squads of its own (C2b): a side all asleep is a side
        if (tasks()) for (AiSquadsBase.Squad<B> s : farSquads(staff, level)) byTeam.computeIfAbsent(AiSquadsBase.teamOf(s.bots.get(0)), t -> new ArrayList<>()).add(s);
        // no side with squads (AI mobs of no team, or alone): looked at again with the squads made again
        if (byTeam.isEmpty()) {
            sides.clear();
            reportDue.put(level, now + AiSquadsBase.EVERY);
            return;
        }
        // the sides' pictures first (AiIntel): every soldier of a side with squads counts, alone, asleep or in a squad
        Map<String, List<B>> own = new HashMap<>();
        for (String team : byTeam.keySet()) own.put(team, new ArrayList<>());
        for (B b : botsIn(staff, level)) {
            List<B> l = b.soldier() && b.body.alive() && !b.body.removed() ? own.get(AiSquadsBase.teamOf(b)) : null;
            if (l != null) l.add(b);
        }
        reportDue.put(level, staff.intel(level, own, now));
        for (var e : byTeam.entrySet()) {
            Side side = sides.computeIfAbsent(e.getKey(), t -> new Side());
            // a new report: the staff's map made again (off the server thread)
            long reports = AiIntelBase.reportsOf(level, e.getKey());
            if (reports != side.reports) {
                side.reports = reports;
                if (staff()) {
                    // the map round its squads awake (those it gives orders to), not its loners nor its squads asleep
                    // far off (C2b) unless it has none awake
                    List<B> inSquads = new ArrayList<>();
                    for (AiSquadsBase.Squad<B> s : e.getValue()) if (!squadAsleep(staff, s)) inSquads.addAll(s.bots);
                    // all of it asleep (C2b): round those
                    if (inSquads.isEmpty()) for (AiSquadsBase.Squad<B> s : e.getValue()) inSquads.addAll(s.bots);
                    staff.hqReport(level, e.getKey(), inSquads, AiIntelBase.groupsOf(level, e.getKey()));
                }
            }
            if (now < side.next) continue;
            side.next = now + EVERY_MIN + staff.world(level).random().nextInt(EVERY_MAX - EVERY_MIN + 1);
            commandSide(staff, level, e.getKey(), side, e.getValue(), now);
        }
        sides.keySet().retainAll(byTeam.keySet());
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> void commandSide(Staff<B, J, O> staff, Object level, String team, Side side, List<AiSquadsBase.Squad<B>> squads, long now) {
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
        List<Group> groups = enemyGroups(staff, level, team);
        waysLeft = WAYS;
        commandedAt.put(team, now);
        if (tasks()) {
            planTasks(staff, level, team, side, squads, groups, now);
            return;
        }
        Map<Group, Integer> sent = new java.util.IdentityHashMap<>();
        List<AiSquadsBase.Squad<B>> going = new ArrayList<>(), idle = new ArrayList<>();
        // squads at a fight first: counted to the group of their target
        for (AiSquadsBase.Squad<B> s : squads) {
            if (s.target < 0) {
                going.add(s);
                continue;
            }
            Group t = countedTo(staff, s, groups, now);
            if (t != null) sent.merge(t, 1, Integer::sum);
        }
        // then those on their way to a group still there (known by any of its members), as they are while there is
        // room: by the order most of the squad carries (squads are made again as they move: a few with an order in a
        // squad of others is not a squad sent)
        for (AiSquadsBase.Squad<B> s : going) {
            Group g = goingTo(staff, s, groups, now);
            if (g != null && !squadFailed(staff, s, g, now) && sent.getOrDefault(g, 0) < groupRoom(staff, g, now)) {
                sent.merge(g, 1, Integer::sum);
                giveArea(staff, level, team, s, g, now, false);
            } else idle.add(s);
        }
        // the rest: each the nearest group with room, not one it gave up lately; none: the reserve
        double reach = Math.min(REACH, staff.radius() * 2);
        for (AiSquadsBase.Squad<B> s : idle) {
            double sx = 0, sz = 0;
            for (B b : s.bots) {
                sx += b.body.x() / s.bots.size();
                sz += b.body.z() / s.bots.size();
            }
            Group best = null;
            double bestD = reach * reach;
            for (Group g : groups) {
                if (sent.getOrDefault(g, 0) >= PER || squadFailed(staff, s, g, now)) continue;
                double dx = g.x() - sx, dz = g.z() - sz, d = dx * dx + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = g;
                }
            }
            // none: the reserve, unless there is a group where the side loses soldiers with room for one more
            boolean helping = false;
            if (best == null) for (Group g : groups) {
                if (sent.getOrDefault(g, 0) >= groupRoom(staff, g, now) || squadFailed(staff, s, g, now)) continue;
                double dx = g.x() - sx, dz = g.z() - sz, d = dx * dx + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = g;
                    helping = true;
                }
            }
            if (best == null) {
                for (B b : s.bots) staff.order(b, null);
                reserve++;
                continue;
            }
            if (helping) helped++;
            sent.merge(best, 1, Integer::sum);
            giveArea(staff, level, team, s, best, now, true);
        }
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> void planTasks(Staff<B, J, O> staff, Object level, String team, Side side, List<AiSquadsBase.Squad<B>> squads, List<Group> groups, long now) {
        List<StaffPlan.Group> foes = new ArrayList<>(groups.size());
        for (Group g : groups) {
            IntelBook.Group k = g.known();
            int n = 0;
            double strength = 0;
            for (int kind = 0; kind < k.kinds.length; kind++) {
                n += k.kinds[kind];
                strength += k.kinds[kind] * weight(kind);
            }
            if (n > 0) strength *= Math.min(1, k.health / n);
            foes.add(new StaffPlan.Group(g.x(), g.z(), g.r(), strength, k.seen == Long.MIN_VALUE ? Long.MAX_VALUE : now - k.seen, groupRoom(staff, g, now)));
        }
        List<StaffPlan.Squad> own = new ArrayList<>(squads.size());
        List<J> jobs = new ArrayList<>(squads.size());
        double sideX = 0, sideZ = 0;
        int all = 0;
        boolean supply = staff.supplyOn();
        HqGrid grid = staff.hq(level, team);
        boolean afresh = side.blind && grid != null;
        for (AiSquadsBase.Squad<B> s : squads) {
            double sx = 0, sz = 0, strength = 0;
            int alive = 0, out = 0;
            for (B b : s.bots) {
                sx += b.body.x() / s.bots.size();
                sz += b.body.z() / s.bots.size();
                if (!b.body.alive()) continue;
                alive++;
                if (!supply || !atRed(staff, b)) b.redFrom = Long.MIN_VALUE;
                else {
                    // at red, and none brought it RED_WAIT: it fights with what it has (its squad is not out for it,
                    // however the squads are made again meanwhile)
                    if (b.redFrom == Long.MIN_VALUE) b.redFrom = now;
                    if (now - b.redFrom < RED_WAIT) out++;
                }
                // its strength: one with no round besides its gun's at a third (red, a magazine left, still fights)
                boolean empty = supply && !b.gunner().ammo.infinite() && b.gunner().ammo.reserve() <= 0 && b.gunner().capacity() > 0;
                strength += weight(b.role().ordinal()) * Math.min(1, b.body.health() / b.body.maxHealth()) * (empty ? 0.3 : 1);
                sideX += b.body.x();
                sideZ += b.body.z();
                all++;
            }
            J job = squadJob(staff, s, now);
            // out of rounds: most of it at red; once back, till none of it is (one filled by its supplier is no reason
            // to send it on while the rest are still at red: it would only fall back again)
            boolean outOf = out * 2 > alive || out > 0 && job != null && job.kind == StaffPlan.Kind.BACK;
            // a squad split on the way: the squad before it with the same task (the plan gives them one)
            int twin = job == null ? -1 : jobs.indexOf(job);
            jobs.add(job);
            int group = job == null ? -1 : groups.indexOf(matchGroup(staff, job.ids, groups));
            // a group it gave up lately (none of it could get there): not again for AVOID
            int avoid = -1;
            for (int k = 0; k < groups.size() && avoid < 0; k++) if (squadFailed(staff, s, groups.get(k), now)) avoid = k;
            boolean pair = pairs() && job != null && job.partner != null;
            // a pair's time up with the one going round not ready: counted here, before the plan gives it another task
            if (pair && job.kind == StaffPlan.Kind.FLANK && job.ready == Long.MIN_VALUE && now >= job.due && !job.late) {
                job.late = true;
                expired++;
            }
            own.add(new StaffPlan.Squad(sx, sz, strength, job == null ? alive : job.alive(), job == null ? alive : job.bots.size(), outOf, job == null ? null : job.kind,
                    job == null ? 0 : afresh ? now - StaffPlan.KEEP : Math.max(job.since, job.foundAt), group, job != null && job.there, avoid, squadAsleep(staff, s), pair && job.ready != Long.MIN_VALUE, pair ? job.due : Long.MAX_VALUE,
                    job == null || !reports() ? Long.MIN_VALUE : job.pinnedAt, job != null && job.pinnedAt != Long.MIN_VALUE && job.alive() < job.aliveAtPin,
                    pair && job.partner != null && job.partner.alive() > 0 && !job.late, twin, job != null && job.place() && placeStands(staff, job, groups), out > 0 && !afresh));
        }
        if (all == 0) return;
        sideX /= all;
        sideZ /= all;
        StaffPlan.Task[] plan = StaffPlan.plan(own, foes, grid, sideX, sideZ, Math.min(REACH, staff.radius() * 2), PER, now, side.sector, mapReady(staff));
        side.blind = grid == null && mapReady(staff);
        for (int i = 0; i < squads.size(); i++) {
            // a squad crossing a river now keeps its task till it is over (the drill is not broken off half way)
            J cj = jobs.get(i);
            if (cj != null && jobCrossing(staff, cj)) {
                // its orders kept alive meanwhile (they last LASTS); one of the squad made since with none of it is given it
                for (B b : cj.members) if (staff.order(b) != null && staff.order(b).job == cj) staff.order(b).until = Math.max(staff.order(b).until, now + LASTS);
                for (B b : squads.get(i).bots) {
                    if (staff.order(b) != null && staff.order(b).job == cj) staff.order(b).until = Math.max(staff.order(b).until, now + LASTS);
                    else if (b.body.alive()) joinCrossing(staff, b, cj, now);
                }
                continue;
            }
            assignTask(staff, level, team, side, squads.get(i), jobs.get(i), plan[i], plan[i].group() < 0 ? null : groups.get(plan[i].group()), now);
            // sent round or to pin for one of the side pinned down by that group (C5's help, not a change of mind)
            if (helps(own, plan, i)) {
                J nj = squadJob(staff, squads.get(i), now);
                if (nj != null && nj.kind == plan[i].kind()) nj.helps = true;
            }
            // pinned down and sent back (C5): it breaks contact
            if (plan[i].kind() == StaffPlan.Kind.BACK && own.get(i).pinned() != Long.MIN_VALUE) {
                J nj = squadJob(staff, squads.get(i), now);
                if (nj != null && nj.kind == StaffPlan.Kind.BACK && !nj.breaking) {
                    nj.breaking = true;
                    broke++;
                }
            }
        }
        if (pairs()) pairSquads(staff, squads, grid, now);
        gatherStrays(staff, level, team, squads, now);
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> List<AiSquadsBase.Squad<B>> farSquads(Staff<B, J, O> staff, Object level) {
        List<AiSquadsBase.Squad<B>> out = new ArrayList<>();
        if (!AiSquadsBase.on) return out;
        double near = AiIntelBase.KNOW + staff.radius();
        List<B> free = new ArrayList<>();
        List<String> teams = new ArrayList<>();
        for (B b : botsIn(staff, level)) {
            if (b.active || !(b.soldier()) || !b.body.alive() || b.body.removed() || AiSquadsBase.squadOf(b) != null) continue;
            String team = AiSquadsBase.teamOf(b);
            if (team.isEmpty() || AiSquadsBase.offTeams.contains(team) || !playerNear(staff, level, b, near)) continue;
            free.add(b);
            teams.add(team);
        }
        if (free.isEmpty()) return out;
        // by AiSquadsBase.NEAR cells: a squad grows from the cells round its own only
        Map<Long, List<Integer>> cells = new HashMap<>();
        for (int i = 0; i < free.size(); i++) cells.computeIfAbsent(cellOf(staff, free.get(i)), c -> new ArrayList<>()).add(i);
        boolean[] taken = new boolean[free.size()];
        double near2 = AiSquadsBase.NEAR * AiSquadsBase.NEAR;
        for (int i = 0; i < free.size(); i++) {
            if (taken[i]) continue;
            AiSquadsBase.Squad<B> s = staff.squad();
            taken[i] = true;
            s.bots.add(free.get(i));
            String team = teams.get(i);
            while (s.bots.size() < AiSquadsBase.MAX) {
                int best = -1;
                double bestD = near2;
                for (B in : s.bots) {
                    int cx = GameMath.floor(in.body.x() / AiSquadsBase.NEAR), cz = GameMath.floor(in.body.z() / AiSquadsBase.NEAR);
                    for (int ox = -1; ox <= 1; ox++)
                        for (int oz = -1; oz <= 1; oz++)
                            for (int j : cells.getOrDefault(cellKey(staff, cx + ox, cz + oz), List.of())) {
                                if (taken[j] || !team.equals(teams.get(j))) continue;
                                double d = AiSquadsBase.distanceSqr(in, free.get(j));
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
            for (B b : s.bots) {
                s.cx += b.body.x() / s.bots.size();
                s.cz += b.body.z() / s.bots.size();
            }
            out.add(s);
        }
        return out;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> long cellOf(Staff<B, J, O> staff, B b) {
        return cellKey(staff, GameMath.floor(b.body.x() / AiSquadsBase.NEAR), GameMath.floor(b.body.z() / AiSquadsBase.NEAR));
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> long cellKey(Staff<B, J, O> staff, int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> boolean playerNear(Staff<B, J, O> staff, Object level, B b, double r) {
        EntityView view = staff.entities(level);
        Body[] players = new Body[0];
        int n = view.players(players);
        if (n < 0) {
            players = new Body[-n];
            n = view.players(players);
        }
        for (int i = 0; i < n; i++) {
            Body p = players[i];
            double dx = p.x() - b.body.x(), dy = p.y() - b.body.y(), dz = p.z() - b.body.z();
            if (!p.spectator() && dx * dx + dy * dy + dz * dz <= r * r) return true;
        }
        return false;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> boolean squadAsleep(Staff<B, J, O> staff, AiSquadsBase.Squad<B> s) {
        int n = 0;
        for (B b : s.bots) if (!b.active) n++;
        return n * 2 > s.bots.size();
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> void driftBots(Staff<B, J, O> staff, Object level, long now, BodyControl control) {
        WorldView world = staff.world(level);
        LiquidPath.Fluids fluids = staff.fluids(level);
        Map<String, List<Group>> groupsOf = new HashMap<>();
        for (B b : botsIn(staff, level)) {
            if (b.active || !(b.soldier()) || !b.body.alive() || b.body.removed() || !hasOrder(staff, b, now)) continue;
            O o = staff.order(b);
            if (o.job == null) continue;
            String team = AiSquadsBase.teamOf(b);
            if (!AiSquadsBase.on || AiSquadsBase.offTeams.contains(team)) continue;
            double x = b.body.x(), z = b.body.z(), d = Math.hypot(o.x - x, o.z - z);
            if (o.job.place() ? d <= o.r : d <= o.r * 0.5) {
                if (o.job.place()) o.job.there = true;
                o.fails = 0;
                continue;
            }
            boolean close = false;
            for (Group g : groupsOf.computeIfAbsent(team, t -> enemyGroups(staff, level, t))) if (Math.hypot(g.x() - x, g.z() - z) <= DRIFT_STOP) close = true;
            if (close) continue;
            // toward the next point of its way, else straight to the place; its own side of it
            if (o.route == null && !o.noRoute) takeWay(staff, o, b);
            double tx = o.x, tz = o.z;
            HqGrid.Route way = o.route;
            HqGrid grid = staff.hq(level, team);
            if (way != null) {
                while (o.at < way.size() - 1 && Math.hypot(way.x(o.at) - x, way.z(o.at) - z) <= reachedAt(staff, grid, way, o.at)) o.at++;
                if (o.at < way.size() - 1 || Math.hypot(way.x(o.at) - x, way.z(o.at) - z) > REACHED) {
                    tx = way.x(o.at);
                    tz = way.z(o.at);
                }
            }
            // its own side of the point, not by water (a ford is narrow)
            // (by water, or at a point to be reached on it: nearly on it)
            boolean onIt = way != null && o.at < way.size() && reachedAt(staff, grid, way, o.at) < REACHED;
            double a = Math.PI * 2 * o.slot / Math.max(1, o.of), spread = o.of <= 1 ? 0 : onIt || waterNear(staff, grid, tx, tz) ? 1 : 3;
            tx += Math.cos(a) * spread;
            tz += Math.sin(a) * spread;
            double l = Math.hypot(tx - x, tz - z);
            if (l < 1e-3) continue;
            // on a way round deep water found before: along it, while it is where the last step left it and goes for
            // the same point (woken and moved meanwhile, or its way changed: dropped)
            if (o.detour != null) {
                long last = o.detourAt == 0 ? o.detourFrom : o.detour[o.detourAt - 1];
                if (Math.abs((int) (last >> 32) - GameMath.floor(x)) + Math.abs((int) last - GameMath.floor(z)) > 2 || Math.hypot(o.detourTX - tx, o.detourTZ - tz) > 2) o.detour = null;
            }
            if (o.detour != null) {
                int k = Math.min(o.detour.length - 1, o.detourAt + (int) DRIFT_STEP - 1);
                long n = o.detour[k];
                int dx = (int) (n >> 32), dz = (int) n;
                if (world.hasChunk(dx >> 4, dz >> 4)) {
                    o.detourAt = k + 1;
                    if (o.detourAt >= o.detour.length) o.detour = null;
                    double turn = Math.atan2(dz + 0.5 - z, dx + 0.5 - x);
                    control.snap(b.body, dx + 0.5, world.height(dx, dz, WorldView.HEIGHT_MOTION_BLOCKING_NO_LEAVES), dz + 0.5, (float) Math.toDegrees(Math.atan2(-Math.cos(turn), Math.sin(turn))), 0F);
                    control.yHeadRot(b.body, b.body.yRot());
                    o.fails = 0;
                    drifted++;
                }
                continue;
            }
            // the step turned aside after one that could not be made, one way then the other
            double along = Math.atan2(tz - z, tx - x) + (o.fails == 0 ? 0 : (o.fails % 2 == 1 ? 1 : -1) * Math.PI / 4 * ((o.fails + 1) / 2));
            double step = Math.min(DRIFT_STEP, l);
            int bx = GameMath.floor(x + Math.cos(along) * step), bz = GameMath.floor(z + Math.sin(along) * step);
            // its point within the block it stands on (a step to it goes nowhere): reached, on to the next
            if (bx == GameMath.floor(x) && bz == GameMath.floor(z) && o.fails == 0) {
                if (way != null && o.at < way.size() - 1) o.at++;
                continue;
            }
            // a chunk not loaded: it waits there
            if (!world.hasChunk(bx >> 4, bz >> 4)) continue;
            int ny = world.height(bx, bz, WorldView.HEIGHT_MOTION_BLOCKING_NO_LEAVES);
            // into water a block deep (a ford) it wades; deeper water, or any other liquid, it does not go into
            LiquidPath.Cell fluid = fluids.at(bx, ny - 1, bz);
            boolean wet = fluid != null && (fluid.liquid() != LiquidEvents.Liquid.WATER || fluids.at(bx, ny - 2, bz) != null);
            // (by the block itself, not the staff's map's cell: a ford in a cell mostly deep)
            if (wet || Math.abs(ny - b.body.y()) > DRIFT_CLIMB) {
                driftBlocked++;
                if (wet) driftWater++;
                // deep water ahead: a way round it over the blocks (by the ford), now and then at most
                if (wet && (o.detourTick == Long.MIN_VALUE || now - o.detourTick >= DETOUR_AGAIN)) {
                    o.detourTick = now;
                    long[] path = StaffGround.detour(world, fluids, GameMath.floor(x), GameMath.floor(z), GameMath.floor(b.body.y()), tx, tz);
                    if (path != null) {
                        detours++;
                        o.detour = path;
                        o.detourAt = 0;
                        o.detourFrom = (long) GameMath.floor(x) << 32 | GameMath.floor(z) & 0xFFFFFFFFL;
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
                if (++o.fails >= FAILS) abandonOrder(staff, b, o, now);
                continue;
            }
            o.fails = 0;
            control.snap(b.body, bx + 0.5, ny, bz + 0.5, (float) Math.toDegrees(Math.atan2(-Math.cos(along), Math.sin(along))), 0F);
            control.yHeadRot(b.body, b.body.yRot());
            drifted++;
        }
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> double reachedAt(Staff<B, J, O> staff, @Nullable HqGrid grid, HqGrid.Route way, int at) {
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

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> boolean waterNear(Staff<B, J, O> staff, @Nullable HqGrid grid, double x, double z) {
        if (grid == null) return false;
        for (int i = -1; i <= 1; i++)
            for (int k = -1; k <= 1; k++)
                if (StaffCross.water(grid, x + i * DRIFT_WET, z + k * DRIFT_WET) > 0) return true;
        return false;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> void gatherStrays(Staff<B, J, O> staff, Object level, String team, List<AiSquadsBase.Squad<B>> squads, long now) {
        double reach = Math.min(REACH, staff.radius() * 2);
        for (B b : botsIn(staff, level)) {
            O mine = staff.order(b);
            if (mine == null || mine.job == null || now > mine.until || !(b.soldier()) || !b.body.alive() || !b.active) continue;
            if (AiSquadsBase.squadOf(b) != null || !team.equals(AiSquadsBase.teamOf(b))) continue;
            O best = null;
            double bestD = reach * reach;
            for (AiSquadsBase.Squad<B> s : squads) {
                // after one awake as it is (one asleep goes at the staff's pace, far off)
                if (squadAsleep(staff, s)) continue;
                O o = null;
                double sx = 0, sz = 0;
                for (B c : s.bots) {
                    sx += c.body.x() / s.bots.size();
                    sz += c.body.z() / s.bots.size();
                    if (o == null && staff.order(c) != null && staff.order(c).job != null && now <= staff.order(c).until) o = staff.order(c);
                }
                double dx = sx - b.body.x(), dz = sz - b.body.z(), d = dx * dx + dz * dz;
                if (o != null && d < bestD) {
                    bestD = d;
                    best = o;
                }
            }
            // none within reach: its order runs out, it is on its own
            if (best == null) continue;
            O o = best.job == mine.job ? mine : freshOrder(staff, now);
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
                takeWay(staff, o, b);
            }
            if (o != mine) strays++;
            staff.order(b, o);
        }
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> @Nullable J squadJob(Staff<B, J, O> staff, AiSquadsBase.Squad<B> s, long now) {
        J best = null;
        int most = 0;
        for (B b : s.bots) {
            J j = staff.order(b) != null && now <= staff.order(b).until ? staff.order(b).job : null;
            if (j == null) continue;
            int n = 0;
            for (B c : s.bots) if (staff.order(c) != null && now <= staff.order(c).until && staff.order(c).job == j) n++;
            if (n > most) {
                most = n;
                best = j;
            }
        }
        return most * 2 >= s.bots.size() ? best : null;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> void assignTask(Staff<B, J, O> staff, Object level, String team, Side side, AiSquadsBase.Squad<B> s, @Nullable J had, StaffPlan.Task t, @Nullable Group g, long now) {
        int[] ids = g == null ? new int[0] : g.ids();
        // a squad scouting a group sent to advance on it (the side has seen it now): the same task, its time, pair and
        // way of going kept (StaffPlan.area: scouting a group not seen lately)
        boolean found = had != null && g != null && had.kind == StaffPlan.Kind.SCOUT && t.kind() == StaffPlan.Kind.ADVANCE && g.any(had.ids);
        boolean same = had != null && (had.kind == t.kind() || found) && (g == null ? had.ids.length == 0 : g.any(had.ids));
        if (found) {
            had.kind = StaffPlan.Kind.ADVANCE;
            had.foundAt = now;
            scoutsFound++;
        }
        // a sector scouted with no group known: the same one till it is looked through (given up there); a new one
        // turns the side's sectors on
        if (t.kind() == StaffPlan.Kind.SCOUT && g == null) {
            if (same) t = keptTask(staff, s, had, t, false);
            else side.sector++;
        }
        // a place to stand at kept while its group stays about where it was (it is not picked again as the picture
        // and the squad move: a squad going round does not swap sides halfway); falling back, the place it went for
        if (same && had.place() && (g == null || Math.hypot(g.x() - had.gx, g.z() - had.gz) <= REROUTE)) t = keptTask(staff, s, had, t, true);
        // falling back, the losses still counted from those it had before (not from those it has left)
        J job = same ? had
                : staff.job(t.kind(), now, t.kind() == StaffPlan.Kind.BACK && had != null ? had.bots : List.copyOf(s.bots), ids);
        // a pair's time goes on with a new task at the same group (C4: the two changing over, one sent to scout the
        // group's place and back): a pair made with it again does not start the way round's time afresh
        // (a pair whose time ran out is over: both advance, nothing handed on)
        if (!same && had != null && g != null && g.any(had.ids)) {
            job.dueWas = Math.min(had.dueWas, had.partner != null && had.ready == Long.MIN_VALUE && had.due > now ? had.due : Long.MAX_VALUE);
            job.otherDead = had.otherDead;
        }
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
        if (same && job.givenAt == now && !job.members.containsAll(s.bots)) {
            List<B> both = new ArrayList<>(job.members);
            i = both.size();
            for (B b : s.bots) if (!both.contains(b)) both.add(b);
            job.members = List.copyOf(both);
            for (B b : job.members) if (staff.order(b) != null && staff.order(b).job == job) staff.order(b).of = both.size();
        } else job.members = List.copyOf(s.bots);
        job.givenAt = now;
        int of = job.members.size();
        Way way = t.kind() == StaffPlan.Kind.RESERVE ? null : wayTo(staff, level, team, s, t.x(), t.z());
        for (B b : s.bots) {
            O o = staff.order(b) != null && staff.order(b).job == job ? staff.order(b) : freshOrder(staff, now);
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
                takeWay(staff, o, b);
            } else if (o.noRoute) {
                o.route = null;
                o.onTail = false;
            }
            else if (o.route != null && farRoute(staff, o.route, o.at, b)) o.at = ahead(o.route, b.body.x(), b.body.z());
            staff.order(b, o);
        }
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> boolean placeStands(Staff<B, J, O> staff, J job, List<Group> groups) {
        Group g = matchGroup(staff, job.ids, groups);
        return g != null && Math.hypot(g.x() - job.gx, g.z() - job.gz) <= REROUTE;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> StaffPlan.Task keptTask(Staff<B, J, O> staff, AiSquadsBase.Squad<B> s, J had, StaffPlan.Task t, boolean face) {
        for (B b : s.bots)
            if (staff.order(b) != null && staff.order(b).job == had)
                return new StaffPlan.Task(t.kind(), t.group(), staff.order(b).x, staff.order(b).z, staff.order(b).r, face ? t.faceX() : staff.order(b).x, face ? t.faceZ() : staff.order(b).z);
        return t;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> @Nullable Group matchGroup(Staff<B, J, O> staff, int[] ids, List<Group> groups) {
        for (Group g : groups) if (g.any(ids)) return g;
        return null;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> @Nullable Group goingTo(Staff<B, J, O> staff, AiSquadsBase.Squad<B> s, List<Group> groups, long now) {
        // the groups in their order: of two alike, the first (the commander and the stands pick the same)
        Group best = null;
        int most = 0;
        for (Group g : groups) {
            int n = 0;
            for (B b : s.bots) if (staff.order(b) != null && now <= staff.order(b).until && matchGroup(staff, staff.order(b).ids, groups) == g) n++;
            if (n > most) {
                most = n;
                best = g;
            }
        }
        return most * 2 >= s.bots.size() ? best : null;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> @Nullable Group countedTo(Staff<B, J, O> staff, AiSquadsBase.Squad<B> s, List<Group> groups, long now) {
        if (s.target >= 0) for (Group x : groups) if (x.has(s.target)) return x;
        return goingTo(staff, s, groups, now);
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> boolean squadFailed(Staff<B, J, O> staff, AiSquadsBase.Squad<B> s, Group g, long now) {
        int n = 0;
        for (B b : s.bots) if (now < b.orderFailedUntil && g.any(b.orderFailed)) n++;
        return n * 2 >= s.bots.size();
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> void giveArea(Staff<B, J, O> staff, Object level, String team, AiSquadsBase.Squad<B> s, Group g, long now, boolean fresh) {
        double x = g.x(), z = g.z(), r = g.r();
        if (fresh) orders++;
        int[] ids = g.ids();
        Way way = wayTo(staff, level, team, s, x, z);
        int i = 0;
        for (B b : s.bots) {
            // the same group (one of its members still in it): the same order, its time in the area kept
            O o = staff.order(b) != null && staff.order(b).job == null && g.any(staff.order(b).ids) ? staff.order(b) : freshOrder(staff, now);
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
                takeWay(staff, o, b);
            } else if (o.noRoute) {
                o.route = null;
                o.onTail = false;
            }
            else if (o.route != null && farRoute(staff, o.route, o.at, b)) o.at = ahead(o.route, b.body.x(), b.body.z());
            staff.order(b, o);
        }
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> @Nullable Way wayTo(Staff<B, J, O> staff, Object level, String team, AiSquadsBase.Squad<B> s, double x, double z) {
        if (!staff()) return null;
        for (B b : s.bots) {
            if (staff.order(b) == null) continue;
            // the one it goes by or waits for; or one walked to its end (not one that ended short, at the map's edge:
            // the map has moved on with the squad since)
            Way w = staff.order(b).way != null ? staff.order(b).way : staff.order(b).walked;
            if (w == null || w == staff.order(b).walked && w.route != null && w.route.partial()) continue;
            if (Math.hypot(w.toX - x, w.toZ - z) <= REROUTE) return w;
        }
        HqGrid grid = staff.hq(level, team);
        if (grid == null || waysLeft <= 0) return null;
        waysLeft--;
        double sx = 0, sz = 0;
        for (B b : s.bots) {
            sx += b.body.x() / s.bots.size();
            sz += b.body.z() / s.bots.size();
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

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> void takeWay(Staff<B, J, O> staff, O o, B b) {
        Way w = o.way;
        if (o.route != null || w == null || !w.done) return;
        o.route = w.route;
        o.at = o.route == null ? 0 : ahead(o.route, b.body.x(), b.body.z());
        // the staff's crossing on it (the squad goes over by the drill: AiCrossing), unless already past its water;
        // one begun (it is past the near bank's point) is kept as it is
        if (o.crossKind != null && crossingStarted(staff, b, o)) return;
        StaffCross.Site c = o.route == null ? null : w.site;
        o.crossKind = null;
        if (c != null && !past(b.body.x(), b.body.z(), c.x(), c.z(), c.farX(), c.farZ())) {
            o.crossX = c.x();
            o.crossZ = c.z();
            o.farX = c.farX();
            o.farZ = c.farZ();
            o.crossKind = c.kind();
        }
        // its first leg at once, not when the straight one walked meanwhile is due again
        if (o.route != null) o.nextLeg = Long.MIN_VALUE;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> boolean farRoute(Staff<B, J, O> staff, HqGrid.Route r, int at, B b) {
        return Math.hypot(r.x(at) - b.body.x(), r.z(at) - b.body.z()) > HqGrid.LEG;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> List<Group> enemyGroups(Staff<B, J, O> staff, Object level, String team) {
        List<Group> out = new ArrayList<>();
        for (Group g : AiIntelBase.groupsOf(level, team)) if (g.known().awake && g.alive()) out.add(g);
        return out;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> boolean hasOrder(Staff<B, J, O> staff, B bot, long tick) {
        return on() && staff.order(bot) != null && tick <= staff.order(bot).until;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> O freshOrder(Staff<B, J, O> staff, long now) {
        O o = staff.order();
        o.givenAt = now;
        return o;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> @Nullable O crossingOrder(Staff<B, J, O> staff, B bot, long tick) {
        O o = staff.order(bot);
        if (o == null || o.crossKind == null || !hasOrder(staff, bot, tick)) return null;
        if (past(bot.body.x(), bot.body.z(), o.crossX, o.crossZ, o.farX, o.farZ)) {
            o.crossKind = null;
            // on along its way from where it is now; the last of the squad over: its way of going begun afresh there
            // (its bounds' halves and covers were of the near bank)
            if (o.route != null) o.at = ahead(o.route, bot.body.x(), bot.body.z());
            J j = o.job;
            if (j != null) {
                boolean all = true;
                for (B b : j.members) if (b.body.alive() && staff.order(b) != null && staff.order(b).job == j && staff.order(b).crossKind != null) all = false;
                if (all) staff.moveAfresh(j);
            }
            return null;
        }
        return o;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> void joinCrossing(Staff<B, J, O> staff, B b, J cj, long now) {
        if (b.orderFailedUntil > now || staff.inCrossing(b)) return;
        O by = null;
        for (B c : cj.members) if (staff.order(c) != null && staff.order(c).job == cj && staff.order(c).crossKind != null) by = staff.order(c);
        if (by == null) return;
        O o = freshOrder(staff, now);
        o.job = cj;
        o.x = by.x;
        o.z = by.z;
        o.r = by.r;
        o.lead = by.lead;
        o.ids = by.ids;
        o.until = now + LASTS;
        if (!cj.members.contains(b)) {
            List<B> members = new ArrayList<>(cj.members);
            members.add(b);
            cj.members = List.copyOf(members);
        }
        o.slot = cj.members.indexOf(b);
        o.of = cj.members.size();
        o.way = by.way;
        takeWay(staff, o, b);
        staff.order(b, o);
        joinedCrossing++;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> int passedSquad(Staff<B, J, O> staff, AiSquadsBase.Squad<B> s, B b, long now) {
        J j = squadJob(staff, s, now);
        if (j == null) return 0;
        if (jobCrossing(staff, j)) return 1;
        return staff.order(b) == null || staff.order(b).job != j ? 2 : 0;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> boolean jobCrossing(Staff<B, J, O> staff, J j) {
        for (B b : j.members)
            if (b.body.alive() && staff.order(b) != null && staff.order(b).job == j && staff.order(b).crossKind != null && crossingStarted(staff, b, staff.order(b))) return true;
        return false;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> boolean crossingStarted(Staff<B, J, O> staff, B b, O o) {
        return ((b.body.x() - o.crossX) * (o.farX - o.crossX) + (b.body.z() - o.crossZ) * (o.farZ - o.crossZ)) > 0;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> boolean atRed(Staff<B, J, O> staff, B b) {
        return !b.gunner().ammo.infinite() && b.gunner().capacity() > 0 && b.gunner().ammo.reserve() <= b.gunner().capacity();
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> void reportAsked(Staff<B, J, O> staff, Object level, String team) {
        Side side = LEVELS.computeIfAbsent(level, l -> new HashMap<>()).get(team);
        if (side == null) return;
        long now = staff.world(level).gameTime();
        if (side.asked == Long.MIN_VALUE) side.asked = now;
        // at once, but its orders given again ASKED_GAP apart at least (reports come in bunches in a fight)
        side.next = Math.min(side.next, Math.max(now + 1, side.commanded == Long.MIN_VALUE ? now + 1 : side.commanded + ASKED_GAP));
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> void lookSquads(Staff<B, J, O> staff, Object level, long now) {
        for (AiSquadsBase.Squad<B> s : squadsIn(staff, level)) {
            J j = squadJob(staff, s, now);
            if (j == null) continue;
            String team = AiSquadsBase.teamOf(s.bots.get(0));
            int id = j.bots.get(0).body.id(), alive = 0, held = 0, empty = 0;
            double x = 0, z = 0;
            for (B b : s.bots) {
                if (!b.body.alive()) continue;
                alive++;
                x += b.body.x();
                z += b.body.z();
                if (b.prone().suppressed() >= SUPPRESSED) held++;
                if (atRed(staff, b)) empty++;
            }
            if (alive == 0) continue;
            x /= alive;
            z /= alive;
            // contact: once, again only after PIN_QUIET with no foe (a foe lost from sight a moment is the same fight)
            if (s.target >= 0) {
                j.noFoeFrom = Long.MIN_VALUE;
                if (!j.saidContact) {
                    j.saidContact = true;
                    staff.report(level, team, new AiReportsBase.Report(id, now, AiReportsBase.Kind.CONTACT, x, z, s.target));
                }
            } else if (j.noFoeFrom == Long.MIN_VALUE) j.noFoeFrom = now;
            else if (now - j.noFoeFrom >= PIN_QUIET) j.saidContact = false;
            int left = j.alive(), was = j.bots.size(), losses = left * 2 < was ? 2 : left * 3 <= was * 2 ? 1 : 0;
            if (losses > j.saidLosses) {
                j.saidLosses = losses;
                staff.report(level, team, new AiReportsBase.Report(id, now, AiReportsBase.Kind.LOSSES, x, z, losses));
            }
            if (held * 2 >= alive) {
                j.quietFrom = Long.MIN_VALUE;
                if (j.pinFrom == Long.MIN_VALUE) j.pinFrom = now;
                if (j.pinnedAt == Long.MIN_VALUE && now - j.pinFrom >= PIN_AFTER) {
                    j.pinnedAt = now;
                    j.aliveAtPin = left;
                    staff.report(level, team, new AiReportsBase.Report(id, now, AiReportsBase.Kind.PINNED, x, z, held));
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
                staff.report(level, team, new AiReportsBase.Report(id, now, AiReportsBase.Kind.AMMO, x, z, empty));
            } else if (empty == 0) j.saidAmmo = false;
            if (!j.saidThere && j.there) {
                j.saidThere = true;
                staff.report(level, team, new AiReportsBase.Report(id, now, AiReportsBase.Kind.THERE, x, z, 0));
            }
        }
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> boolean abandonOrder(Staff<B, J, O> staff, B bot, O o, long tick) {
        // its squad cannot get through (C5): reported once for its task
        J j = o.job;
        Object level = bot.serverLevel();
        if (j != null && !j.saidBlocked && level != null) {
            j.saidBlocked = true;
            staff.report(level, AiSquadsBase.teamOf(bot), new AiReportsBase.Report(j.bots.get(0).body.id(), tick, AiReportsBase.Kind.BLOCKED, bot.body.x(), bot.body.z(), 0));
        }
        bot.orderFailed = o.ids;
        bot.orderFailedUntil = tick + AVOID;
        staff.order(bot, null);
        gaveUp++;
        return false;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> void pairSquads(Staff<B, J, O> staff, List<AiSquadsBase.Squad<B>> squads, @Nullable HqGrid grid, long now) {
        List<J> pins = new ArrayList<>(), flanks = new ArrayList<>();
        List<AiSquadsBase.Squad<B>> flankers = new ArrayList<>();
        for (AiSquadsBase.Squad<B> s : squads) {
            J j = squadJob(staff, s, now);
            // asleep (moved by drift, not step): never there to signal, not paired
            if (j == null || squadAsleep(staff, s) && j.partner == null) continue;
            if (j.kind == StaffPlan.Kind.PIN) pins.add(j);
            else if (j.kind == StaffPlan.Kind.FLANK) {
                flanks.add(j);
                flankers.add(s);
            }
        }
        // a pair whose other is gone (its squad dead, its task another now) before it was ready: undone, to pair again
        for (List<J> l : List.of(pins, flanks))
            for (J j : l)
                if (j.partner != null && j.ready == Long.MIN_VALUE && (j.partner.partner != j || !pins.contains(j.partner) && !flanks.contains(j.partner))) {
                    // its time goes on with it: a pair made again does not start the way round's time afresh
                    j.dueWas = Math.min(j.dueWas, j.due);
                    j.otherDead = j.partner.alive() == 0;
                    j.partner = null;
                    j.due = Long.MAX_VALUE;
                }
        for (int i = 0; i < flanks.size(); i++) {
            J f = flanks.get(i);
            if (f.partner == null) for (J p : pins) {
                if (p.partner != null || !shares(p.ids, f.ids)) continue;
                AiSquadsBase.Squad<B> s = flankers.get(i);
                double sx = 0, sz = 0;
                for (B b : s.bots) {
                    sx += b.body.x() / s.bots.size();
                    sz += b.body.z() / s.bots.size();
                }
                B first = s.bots.get(0);
                // its way there over the map (by a ford, round the hill), else the straight line by DETOUR
                double d = staff.order(first) == null ? 0 : Math.hypot(staff.order(first).x - sx, staff.order(first).z - sz) * HqGrid.DETOUR;
                if (grid != null && staff.order(first) != null) {
                    int ai = grid.cellX(sx), ak = grid.cellZ(sz), bi = grid.cellX(staff.order(first).x), bk = grid.cellZ(staff.order(first).z);
                    int[] w = ai < 0 || ak < 0 || bi < 0 || bk < 0 ? null : grid.path(ai, ak, bi, bk, 0);
                    if (w != null) d = Math.max(d, HqGrid.cells(w) * HqGrid.CELL);
                }
                f.partner = p;
                p.partner = f;
                f.pairedAt = p.pairedAt = now;
                f.due = p.due = time(f, p, now + Math.min(PAIR_MOST, (long) (d / PAIR_PACE * 1.5) + PAIR_MORE), now);
                paired++;
                break;
            }
        }
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> void coupleSquads(Staff<B, J, O> staff, Object level, long now) {
        for (AiSquadsBase.Squad<B> s : squadsIn(staff, level)) {
            if (s.target < 0) continue;
            for (B b : s.bots) {
                J j = staff.order(b) != null && now <= staff.order(b).until ? staff.order(b).job : null;
                if (j != null && j.kind == StaffPlan.Kind.FLANK && j.partner != null && j.ready == Long.MIN_VALUE && contains(j.ids, s.target)
                        && (Math.hypot(b.body.x() - staff.order(b).x, b.body.z() - staff.order(b).z) <= LEASH && roundGroup(staff, b, j.partner, b.memory.contact(s.target))
                                || ranIntoGroup(staff, b, b.memory.contact(s.target)) || underFire(staff, b, now))) {
                    signalReady(staff, level, j, now);
                    break;
                }
            }
        }
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> void signalReady(Staff<B, J, O> staff, Object level, J f, long now) {
        if (f.ready != Long.MIN_VALUE || f.partner == null || !pairs() || now >= f.due) return;
        f.ready = f.partner.ready = now;
        // ready in time: a later pair of either is a pair of its own
        f.dueWas = f.partner.dueWas = Long.MAX_VALUE;
        coupled++;
        coupleTicks += now - f.pairedAt;
        for (B b : f.members)
            if (b.body.alive() && staff.order(b) != null && staff.order(b).job == f) {
                b.gesture(SIGNAL_POINT, SIGN_TICKS, now);
                Map<String, Side> sides = LEVELS.get(level);
                Side side = sides == null ? null : sides.get(AiSquadsBase.teamOf(b));
                if (side != null) side.next = now + 1;
                break;
            }
        for (B b : f.partner.members)
            if (b.body.alive() && staff.order(b) != null && staff.order(b).job == f.partner) {
                b.gesture(SIGNAL_COME, SIGN_TICKS, now);
                break;
            }
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> boolean pressesFor(Staff<B, J, O> staff, B bot, long tick) {
        O o = staff.order(bot);
        J j = o == null || tick > o.until ? null : o.job;
        boolean p = j != null && j.kind == StaffPlan.Kind.PIN && j.ready != Long.MIN_VALUE && tick < j.due && pairs() && on();
        if (p) pressed++;
        return p;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> boolean leashedAt(Staff<B, J, O> staff, B bot, int target, long tick) {
        O o = staff.order(bot);
        J j = o == null || tick > o.until ? null : o.job;
        if (j == null || j.kind != StaffPlan.Kind.PIN && j.kind != StaffPlan.Kind.HOLD) return false;
        if (Math.hypot(bot.body.x() - o.x, bot.body.z() - o.z) > LEASH) return false;
        TargetMemory.Contact c = bot.memory.contact(target);
        return c != null && Math.hypot(c.lostX - o.x, c.lostZ - o.z) > LEASH && pairs() && on();
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> boolean goesAround(Staff<B, J, O> staff, B bot, TargetMemory.Contact c, long tick) {
        O o = staff.order(bot);
        J j = o == null || tick > o.until ? null : o.job;
        if (j == null || j.kind != StaffPlan.Kind.FLANK || j.partner == null || j.ready != Long.MIN_VALUE || tick >= j.due) return false;
        double x = c.state == TargetMemory.State.VISIBLE ? c.x : c.lostX, z = c.state == TargetMemory.State.VISIBLE ? c.z : c.lostZ;
        if (Math.hypot(x - bot.body.x(), z - bot.body.z()) <= RUN_INTO) return false;
        // shot at lately: it fights back rather than walk on under fire
        return !underFire(staff, bot, tick) && pairs() && on();
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> boolean ranIntoGroup(Staff<B, J, O> staff, B bot, TargetMemory.@Nullable Contact c) {
        if (c == null) return false;
        double x = c.state == TargetMemory.State.VISIBLE ? c.x : c.lostX, z = c.state == TargetMemory.State.VISIBLE ? c.z : c.lostZ;
        return Math.hypot(x - bot.body.x(), z - bot.body.z()) <= RUN_INTO;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> boolean roundGroup(Staff<B, J, O> staff, B bot, J pin, TargetMemory.@Nullable Contact c) {
        if (c == null) return true;
        // out of its sight: where it went from sight goes stale as it walks on; the staff's picture of it instead
        J f = staff.order(bot) == null ? null : staff.order(bot).job;
        double gx = c.state == TargetMemory.State.VISIBLE ? c.x : f == null ? c.lostX : f.gx, gz = c.state == TargetMemory.State.VISIBLE ? c.z : f == null ? c.lostZ : f.gz;
        double px = 0, pz = 0;
        int n = 0;
        for (B m : pin.members)
            if (m.body.alive()) {
                px += m.body.x();
                pz += m.body.z();
                n++;
            }
        if (n == 0) return true;
        double ax = px / n - gx, az = pz / n - gz, bx = bot.body.x() - gx, bz = bot.body.z() - gz;
        double cos = (ax * bx + az * bz) / Math.max(1e-6, Math.hypot(ax, az) * Math.hypot(bx, bz));
        return cos <= Math.cos(READY_ANGLE);
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> boolean underFire(Staff<B, J, O> staff, B bot, long now) {
        TargetMemory.Noise n = bot.memory.noise();
        return n != null && n.hurt && now - n.tick <= UNDER_FIRE;
    }

    static <B extends AiBotBase, J extends Job<B, J>, O extends Order<B, J>> boolean mapReady(Staff<B, J, O> staff) {
        return pairs() && staff() && staff.terrainOn();
    }
}
