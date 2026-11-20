package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ReaimTimes;

/** The staff's plain data and pure helpers shared by the versions. */
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
}
