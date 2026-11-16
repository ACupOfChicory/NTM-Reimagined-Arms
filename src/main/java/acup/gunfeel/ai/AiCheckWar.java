package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * The AI check's war (stage 7, the staff): a field FIELD blocks across with a hill, a wood, a river right across with
 * one ford, a thicket by it, and open ground. Side A, two squads of three (both sent: two to a group at most), at one edge, one by the hill, one by the wood; at the other, across the river, a player (a
 * fake one, in survival, kept whole) walking to and fro, for a while hidden in a stone box farther off. The hill's
 * crest and the wood lie well within the player's sight (128 blocks; A sees 96), the camp just out of it: whether the
 * side comes on behind them tells in how near it gets before the player first sees one of it. ROUNDS rounds of RUN ticks (-PaiCheckWarRounds, 4 by default: the full
 * check's short war), by turns with no staff (R5), with its map and ways (C1), with its tasks too (C2), with the
 * squads' ways of going too (C3: march, bounds, creeping, assault). Counted, at each of side A's reports
 * (AiIntel): how far its picture's middle is from the player by how long ago one of the side saw it, whether the
 * player was within the circle; the tick one of A first saw it; till then, how often each of A stood where the
 * player's eyes reached it (a look from its eyes to A's head or chest, blocks and leaves in the way), how far each
 * walked; the staff's ways (C1): how much of each lies where the staff thinks the enemy sees it, against the shortest way
 * there (a river across: by its ford), how long it is against that; the time the reports, maps and ways took.
 */
final class AiCheckWar {
    static final int X0 = 3200, Z0 = 0, FIELD = 256, RUN = 2400, SQUADS = 2, EACH = 3;
    /** the hill: its middle, radius, height */
    static final int HILL_X = 80, HILL_Z = 96, HILL_R = 30, HILL_H = 12;
    /** the wood, a tree every TREE blocks or so */
    static final int WOOD_X0 = 150, WOOD_X1 = 210, WOOD_Z0 = 56, WOOD_Z1 = 124, TREE = 6;
    /** the river right across: from RIVER_Z, RIVER_W wide, RIVER_D deep; the ford, a block deep */
    static final int RIVER_Z = 128, RIVER_W = 6, RIVER_D = 3, FORD_X0 = 220, FORD_X1 = 227;
    /**
     * the thicket by the ford: bushes along both banks (from the wood to past the ford; on the far one the ford's end only, out of the player's walk), a bush
     * every THICKET blocks, BANK blocks deep; the ford and its ends kept clear
     */
    static final int THICKET_X0 = 200, THICKET_FAR_X0 = 196, THICKET_X1 = 236, BANK = 14, THICKET = 5;
    /** side A's squads; the player's walk: its middle, how far to each side, how long there and back */
    static final double CAMP_Z = 24.5, WALK_X = 128.5, WALK_Z = 160.5, WALK_A = 40;
    static final int WALK_T = 1200;
    /** the player hides in a stone box (its middle) from this tick of a round to this */
    static final int HIDE_FROM = 900, HIDE_TO = RUN, BOX_X = 140, BOX_Z = 200;
    static final double[] SQUAD_X = {100.5, 156.5};
    /**
     * the last round, "far" (C2b): the side asleep (no stand's wake), the player standing here, farther from its camp
     * than aiRadius but within what the staff knows; the staff is to move its squads till they wake, within FAR_RUN
     */
    static final double FAR_X = 128.5, FAR_Z = 240.5;
    /**
     * the near rounds after the far one (C4): the side's camp on the player's bank this far north of its walk, no river
     * between (the way round is made in a pair's time); C3 and C4 by turns, ntmgunfeel.checkAiWarNear of them
     */
    static final double NEAR_Z = 240.5;
    /**
     * the pinned rounds after the near ones (C5): camp and player as near, all the staff on; from PIN_AT the stand keeps
     * the first squad suppressed (as under a machine gun's fire); ntmgunfeel.checkAiWarPinned of them. Help: the other
     * squad sent round the group or to pin it within HELP_IN of the report
     */
    static final int PIN_AT = 300, HELP_IN = 200;
    /** a squad's task changed again within this: a flap (C5: the staff answering reports at once), ticks */
    static final int FLAP = 200;
    /**
     * the share of changes that were flaps before C5: C4b's war of 60 rounds, 110 of 1032 changes (10.7 %). The check's
     * flaps as a binomial of it: more than flapsAllowed of n changes happen by chance under FLAP_LEVEL, more is a rise
     */
    static final double FLAPS_BEFORE = 110.0 / 1032;
    /** the chance of failing the flaps' check with no rise at all (the coordinator's 1 %, 2026-11-16) */
    static final double FLAP_LEVEL = 0.01;
    /**
     * the most flaps of {@code n} squads' changes the check lets pass: the largest k with P(X >= k) >= FLAP_LEVEL, X of
     * Binomial(n, FLAPS_BEFORE) (n 30: 8; n 90: 17)
     */
    static int flapsAllowed(long n) {
        // P(X >= k) from the top down: the tail grows as k falls
        double p = FLAPS_BEFORE, tail = 0;
        for (long k = n; k >= 0; k--) {
            tail += Math.exp(logChoose(n, k) + k * Math.log(p) + (n - k) * Math.log1p(-p));
            if (tail >= FLAP_LEVEL) return (int) k;
        }
        return 0;
    }

    private static double logChoose(long n, long k) {
        double s = 0;
        for (long i = 1; i <= k; i++) s += Math.log((double) (n - k + i) / i);
        return s;
    }

    /** why one stands with no foe known and no order: its order ran out (the staff gave it none again), the staff took it back (cannot get there), in no squad (no staff's order for it), none given */
    static final int IDLE_EXPIRED = 0, IDLE_GAVE_UP = 1, IDLE_NO_SQUAD = 2, IDLE_NONE = 3;
    private static final String[] IDLE_NAMES = {"order ran out", "taken back", "in no squad", "none given"};

    /** the idle's whys (a round's: IDLE_*), counted as idle is; the ticks past their order's end of those it ran out on */
    private final long[] idleWhy = new long[4];
    private long idleLate;

    /** the idle's whys of a round's idles line (alive, idle, the whys, the late ticks): "(order ran out 3, ...)" or "" */
    private static String whys(long[] id) {
        if (id.length < 7 || id[1] == 0) return "";
        StringBuilder s = new StringBuilder(" (");
        for (int k = 0; k < IDLE_NAMES.length; k++) {
            if (id[2 + k] == 0) continue;
            if (s.length() > 2) s.append(", ");
            s.append(IDLE_NAMES[k]).append(' ').append(id[2 + k]);
            if (k == IDLE_EXPIRED) s.append(String.format(Locale.ROOT, ", %.0f ticks past on the mean", (double) id[6] / id[2]));
        }
        return s.append(" of the looks)").toString();
    }

    /** a squad going round at its place: one of it within this of it */
    static final double AT_PLACE = 8;
    /** a squad within this north of the camp's line is still at the camp */
    static final double CAMP_NEAR = 20;
    /** fewer squads going round seen on the river's field than this: their angle not checked (few go round there now) */
    static final int FLANKS_MIN = 4;
    /** the near rounds by default (half of them C4); the side angles at the pairs' signals fewer than this: not measured, a FAIL */
    static final int NEAR_ROUNDS = 12, ANGLES_MIN = 3;
    /** C4 on the river's field: in the player's sight before it is seen this much (a share) more than C3 at most (C4b) */
    static final double PAIRS_WORSE = 0.03;
    static final int FAR_RUN = 2400;
    static final String A = "war_a";
    /** the reports' ages: up to each, ticks (the last: never seen) */
    static final long[] AGES = {100, 400, 1200, Long.MAX_VALUE};
    static final String[] AGE_NAMES = {"0-5 s", "5-20 s", "20-60 s", "over 60 s", "never seen"};
    static final Identifier RIFLE = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");

    private final AiCheck check;
    private final ServerLevel level;
    /** AiCrossing's counters when the stage began */
    private final int crossBegun0 = AiCrossing.begun, crossOver0 = AiCrossing.over, crossNotOver0 = AiCrossing.notOver, crossWatches0 = AiCrossing.watches, crossFound0 = AiCrossing.found;
    private final int[] crossWhy0 = AiCrossing.whyNot.clone();
    private final int crossSmoked0 = AiCrossing.smoked;
    private final int[] smokeWhy0 = AiCrossing.smokeWhy.clone();
    private final int y, rounds;
    private boolean started;
    private int round = -1, ticks;
    private final List<Mob> ours = new ArrayList<>();
    private FakePlayer player;
    private Item rifle;
    private long reportsSeen, nanos0, reports0;
    /** the player first seen by one of A, per round (-1: not) */
    private int seenAt;
    /** per age: the errors summed, reports, the most; reports with the player out of its circle; in all */
    private final double[] errSum = new double[AGE_NAMES.length], errMost = new double[AGE_NAMES.length];
    private final int[] errN = new int[AGE_NAMES.length];
    private int outside, reports, missing, orderedAt, orderedOut;
    private final List<int[]> results = new ArrayList<>();
    /**
     * per round, till the player is first seen: A's ticks counted (every LOOK), those within the player's reach
     * (HqGrid.RANGE), those in its sight; blocks walked; how far from the player the first of A it saw was (-1: none)
     */
    private long looks, inReach, inSight;
    /** per round, all of it: A's ticks counted (every LOOK) alive, those with no foe known and no order (C2: a task) */
    private long alive, idle;
    /** the tasks given (AiCommander.given) at the round's start */
    private int[] given0 = new int[StaffPlan.Kind.values().length];
    private int strays0;
    /** per round: {alive, idle} and the tasks given in it */
    private final List<long[]> idles = new ArrayList<>();
    private final List<int[]> tasksOf = new ArrayList<>();
    /** per round (C3): the squads' looks (every LOOK) in each mode; bounding, and of those one of the half covering in cover */
    private final long[] modeTicks = new long[StaffMove.Mode.values().length];
    private long bounding, covering, inCover;
    private final List<long[]> movesOf = new ArrayList<>();
    /**
     * per round (C4): the squads going round that saw the player, those of them at WIDE degrees or more from the
     * pinning one; pairs made, ready in time, out of time, the ticks to ready
     */
    private final java.util.Set<AiCommander.Job> flanksSeen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private long flankContacts, flankWide, roundTicks, crossTicks;
    /** the round's soldiers that went round (a FLANK task) */
    private final java.util.Set<Integer> wentRound = new java.util.HashSet<>();
    private int paired0, coupled0, expired0;
    private long coupleTicks0, pressed0;
    private final List<long[]> pairsOf = new ArrayList<>();
    /** a squad going round sees the player this many degrees or more off the line from the pinning one: round its side */
    static final double WIDE = 60;
    /** a squad going round comes on the player: it sees it within this, blocks (seen from afar on the way it is not there yet) */
    static final double CLOSE = 64;
    private double firstSeenFrom;
    /** the staff's ways made before the player was first seen this round */
    private int madeBefore;
    private double walked;
    private final double[] lastX = new double[SQUADS * EACH], lastZ = new double[SQUADS * EACH];
    /** per round: {on, looks, in reach, in sight, walked, first seen from} */
    private final List<double[]> approach = new ArrayList<>();
    /** fewer of the staff's ways than this: the check of them tells nothing (a run has 2 to 4 under /tick sprint) */
    static final int WAYS_MIN = 2;
    /** the blocks of the ways in the enemy's sight up to the river against those of the shortest ways there, at most */
    static final double WAY_SEEN = 0.85;
    /** fewer ways counted one by one than this: by the blocks alone (2 of 2 would be needed: one in two runs failed on a clean main) */
    static final int BY_WAY = 3;
    /** the staff's ways in the rounds it was on */
    private final List<HqGrid.Route> ways = new ArrayList<>();
    private long routeNanos0, routes0;
    static final int LOOK = 10;
    /** -PaiCheckWarDump: the staff's maps as each way was made, the ways, the soldiers' and the player's places */
    private java.io.PrintWriter dump;
    /** the far round: on; the tick the first of the side woke (-1: none), the most awake at once, the nearest any came */
    private boolean far;
    /** the near rounds: on, how many run, how many to run; each: its mode, pairs made, ready, late, ticks one going round sees the player, of them with one pinning, squads going round, at their place */
    private boolean near, pinRound;
    private int nearDone, pinDone;
    private final int nears, pinRounds;
    /** the pinned rounds: each, the tick of the pinned report, of the pinned squad's task to pin where it is, of the other's help, its kind (-1 none) */
    private final List<long[]> pinOf = new ArrayList<>();
    private long pinReport, pinHeld, pinHelp, pinHelpKind, pinMade0;
    /** each soldier's task and when it changed; changes, of them within FLAP of the one before */
    private final java.util.Map<Integer, Object[]> lastJob = new java.util.HashMap<>();
    private long jobChanges, flaps, flapsAnswered, flapsHelp;
    /** the staff's tasks counted already: a squad's change once, not once for each of it */
    private final java.util.Set<AiCommander.Job> changeCounted = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final long[] changesOf = new long[MODES], flapsOf = new long[MODES];
    private final java.util.Map<String, Integer> flapKinds = new java.util.TreeMap<>();
    /** the pinned round's fire begun (the side knows of the player: a task for its group) */
    private boolean pinFire;
    private final List<long[]> nearOf = new ArrayList<>();
    private final java.util.Set<AiCommander.Job> flankJobs = new java.util.HashSet<>(), flankThere = new java.util.HashSet<>(), flankReady = new java.util.HashSet<>();
    /** the near rounds: the one going round at its signal (ready), the angle at the player between it and the pinning one's middle: counted, WIDE or more */
    private long readyAngles, readyWide;
    /**
     * the pairs (C4, not near) whose squad going round was sent across the river: from the camp (its way by the ford too
     * long for the pair's time), from anywhere on the camp's bank
     */
    private int pairsAcross, pairsAcrossAll;
    private int farWoke = -1, farMost;
    private double farNearest = Double.MAX_VALUE;
    private long drifted0, driftBlocked0;
    /** the far round: those seen asleep (a new mob is awake till AiBots looks first: a wake counts after a sleep) */
    private final java.util.Set<Integer> slept = new java.util.HashSet<>();
    private HqGrid dumped;
    private int madeSeen, grids;

    AiCheckWar(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
        this.rounds = Math.max(1, Integer.getInteger("ntmgunfeel.checkAiWarRounds", MODES));
        // near rounds by turns C3, C4: NEAR_ROUNDS / 2 of C4, a pair's signal about one a round (the side angle measured)
        this.nears = Math.max(0, Integer.getInteger("ntmgunfeel.checkAiWarNear", NEAR_ROUNDS));
        this.pinRounds = Math.max(0, Integer.getInteger("ntmgunfeel.checkAiWarPinned", 2));
    }

    boolean tick() {
        if (!started) {
            started = true;
            for (int cx = X0 >> 4; cx <= (X0 + FIELD) >> 4; cx++)
                for (int cz = Z0 >> 4; cz <= (Z0 + FIELD) >> 4; cz++) level.setChunkForced(cx, cz, true);
            AiBots.awake = true;
            rifle = BuiltInRegistries.ITEM.getValue(RIFLE);
            build();
            String to = System.getProperty("ntmgunfeel.checkAiWarDump");
            if (to != null) try {
                dump = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(java.nio.file.Path.of(to)));
            } catch (java.io.IOException x) {
                throw new java.io.UncheckedIOException(x);
            }
            nanos0 = AiIntel.nanos;
            reports0 = AiIntel.reports;
            routeNanos0 = AiCommander.routeNanos.get();
            routes0 = AiCommander.routes.get();
            start(0);
            return false;
        }
        if (far) return farTick();
        ticks++;
        AiCommander.on = true;
        AiCommander.staff = staff();
        AiCommander.tasks = mode() >= TASKS;
        StaffMove.on = mode() >= MOVES;
        AiCommander.pairs = mode() == PAIRS;
        long now = level.getGameTime();
        // a squad going round (FLANK) first seeing the player within CLOSE: the angle at the player between it and the middle of the
        // squad pinning the same group (C4: the pair's; before it, any pinning that group)
        // the player under fire from two sides: one of a squad pinning and one of a squad going round (or that went
        // round this round and advances now) see it at once
        // C5: the pinned rounds, the first squad kept under fire; the staff's answer
        if (pinRound) pinTick(now);
        // C5: each soldier's task changing again soon (the staff answering reports at once: no flapping)
        if (mode() >= TASKS && ticks % LOOK == 0) for (Mob m : ours) {
            AiBot b = AiBots.get(m);
            if (b == null || !m.isAlive()) continue;
            // its task's kind (what the soldier does), not the staff's Job made again for the same
            AiCommander.Job jb = b.order == null || !AiCommander.has(b, now) ? null : b.order.job;
            Object j = jb == null ? null : jb.kind;
            Object[] was = lastJob.get(m.getId());
            // a flap: a change within FLAP of its last change (its first task given is none)
            if (was == null) lastJob.put(m.getId(), new Object[] {j, Long.MIN_VALUE});
            else if (j != null && was[0] != j) {
                // a squad's change once (the task is the squad's: each of it changes with it), by the first of it seen
                if (was[0] != null && changeCounted.add(jb)) {
                    jobChanges++;
                    changesOf[mode()]++;
                    if ((Long) was[1] != Long.MIN_VALUE && now - (Long) was[1] < FLAP) {
                        String kind = was[0] + ">" + j + (pinRound ? " (pinned)" : near ? " (near)" : "");
                        // sent to help a squad pinned down (C5's drill, at once by design): no flap
                        if (jb.helps) {
                            flapsHelp++;
                            flapKinds.merge(kind + " help", 1, Integer::sum);
                        } else {
                            flaps++;
                            flapsOf[mode()]++;
                            // its new task given in answer to a report (C5), not on the staff's own time
                            if (now - AiCommander.answeredAt <= LOOK) flapsAnswered++;
                            flapKinds.merge(kind, 1, Integer::sum);
                        }
                    }
                }
                lastJob.put(m.getId(), new Object[] {j, now});
            }
        }
        if (mode() >= TASKS) {
            boolean pinSees = false, roundSees = false;
            for (Mob m : ours) {
                AiBot b = AiBots.get(m);
                AiCommander.Job j = b == null || !m.isAlive() || !AiCommander.has(b, now) ? null : b.order.job;
                if (j != null && j.kind == StaffPlan.Kind.FLANK) {
                    wentRound.add(m.getId());
                    if (flankJobs.add(j) && !near && j.partner != null && m.getZ() < Z0 + RIVER_Z && b.order.z > Z0 + RIVER_Z + RIVER_W) {
                        pairsAcrossAll++;
                        if (m.getZ() < Z0 + CAMP_Z + CAMP_NEAR) pairsAcross++;
                    }
                    if (Math.hypot(m.getX() - b.order.x, m.getZ() - b.order.z) <= AT_PLACE) flankThere.add(j);
                    if (near && j.ready != Long.MIN_VALUE && flankReady.add(j)) {
                        double[] pin = pinMiddle(j, now);
                        if (pin != null) {
                            readyAngles++;
                            if (angle(pin, m) >= WIDE) readyWide++;
                        }
                    }
                }
                TargetMemory.Contact c = j == null ? null : b.memory.contact(player.getId());
                if (c == null || c.state != TargetMemory.State.VISIBLE) continue;
                if (j.kind == StaffPlan.Kind.PIN) pinSees = true;
                else if (wentRound.contains(m.getId())) roundSees = true;
            }
            if (roundSees) roundTicks++;
            if (pinSees && roundSees) crossTicks++;
        }
        if (mode() >= TASKS) for (Mob m : ours) {
            AiBot b = AiBots.get(m);
            AiCommander.Job j = b == null || !m.isAlive() || !AiCommander.has(b, now) ? null : b.order.job;
            if (j == null || j.kind != StaffPlan.Kind.FLANK || flanksSeen.contains(j)) continue;
            TargetMemory.Contact c = b.memory.contact(player.getId());
            if (c == null || c.state != TargetMemory.State.VISIBLE || m.distanceTo(player) > CLOSE) continue;
            double[] pin = pinMiddle(j, now);
            if (pin == null) continue;
            flanksSeen.add(j);
            flankContacts++;
            if (angle(pin, m) >= WIDE) flankWide++;
        }
        // the picture as of this tick's report, against where the player was when it was made (before it moves on)
        long sideReports = AiIntel.reports(level, A);
        if (sideReports != reportsSeen) {
            reportsSeen = sideReports;
            if (sideReports > 0) picture(now);
        }
        // when the side gives its orders: how far the player is from the picture's middle then (the picture is as of
        // its last report, the player may have gone on since: the staff knows a little behind)
        if (AiCommander.commandedAt.getOrDefault(A, Long.MIN_VALUE) == now) {
            for (AiIntel.Group g : AiCommander.groups(level, A))
                if (g.has(player.getId())) {
                    orderedAt++;
                    if (Math.hypot(g.x() - player.getX(), g.z() - player.getZ()) > g.r()) orderedOut++;
                }
        }
        // the player walks to and fro across the river's far side, kept whole; for a while it hides in a stone box
        // farther off (none of the side sees it: the picture grows old)
        boolean hides = ticks >= HIDE_FROM && ticks < HIDE_TO && !Boolean.getBoolean("ntmgunfeel.checkAiWarStill");
        double px = hides ? X0 + BOX_X + 0.5 : X0 + WALK_X + WALK_A * Math.sin(2 * Math.PI * ticks / WALK_T);
        player.snapTo(px, y, Z0 + (hides ? BOX_Z + 0.5 : WALK_Z), 180F, 0F);
        player.setHealth(player.getMaxHealth());
        if (seenAt < 0) for (Mob m : ours) {
            AiBot b = AiBots.get(m);
            TargetMemory.Contact c = b == null || !m.isAlive() ? null : b.memory.contact(player.getId());
            if (c != null && c.state == TargetMemory.State.VISIBLE) seenAt = ticks;
        }
        if (seenAt < 0) madeBefore = AiCommander.made.size();
        // all the round: alive, knowing of no foe and with no order
        if (ticks % LOOK == 0) for (Mob m : ours) {
            AiBot b = AiBots.get(m);
            if (b == null || !m.isAlive()) continue;
            alive++;
            if (b.memory.focus(m.getX(), m.getZ()) == null && !AiCommander.has(b, now)) {
                idle++;
                // why (a soldier waiting for its squad's way has its order already: never here)
                int why = b.order != null ? IDLE_EXPIRED : b.orderFailedUntil > now ? IDLE_GAVE_UP : AiSquads.of(b) == null ? IDLE_NO_SQUAD : IDLE_NONE;
                idleWhy[why]++;
                if (why == IDLE_EXPIRED) idleLate += now - b.order.until;
            }
        }
        // the squads' ways of going (C3): each squad's mode, and bounding, whether one of the half covering is in cover
        if (ticks % LOOK == 0) {
            java.util.Set<AiCommander.Job> jobs = new java.util.HashSet<>(), crossing = new java.util.HashSet<>();
            for (Mob m : ours) {
                AiBot b = AiBots.get(m);
                if (b != null && m.isAlive() && b.order != null && b.order.job != null && AiCommander.has(b, now)) {
                    jobs.add(b.order.job);
                    if (AiCrossing.of(b) != null) crossing.add(b.order.job);
                }
            }
            for (AiCommander.Job j : jobs) {
                // a squad at its task's place stands there, its last mode left over; one in a river crossing goes by its
                // drill (AiCrossing), not by its way of going
                if (j.move.mode == null || j.there || crossing.contains(j)) continue;
                modeTicks[j.move.mode.ordinal()]++;
                // bounding out of a fight (in one the soldiers fight from cover of their own: C4, C5)
                if (j.move.mode == StaffMove.Mode.BOUND && j.move.turn > 0 && StaffMove.quiet(j)) {
                    bounding++;
                    int c = StaffMove.covered(j);
                    if (c > 0) covering++;
                    if (c > 1) inCover++;
                }
            }
        }
        // till the first contact: in the player's sight, and the way walked
        if (seenAt < 0 && ticks % LOOK == 0) for (int i = 0; i < ours.size(); i++) {
            Mob m = ours.get(i);
            if (!m.isAlive()) continue;
            looks++;
            if (player.getEyePosition().distanceTo(m.position()) <= HqGrid.RANGE) inReach++;
            if (inSight(m)) {
                inSight++;
                if (firstSeenFrom < 0) firstSeenFrom = Math.hypot(m.getX() - player.getX(), m.getZ() - player.getZ());
            }
            walked += Math.hypot(m.getX() - lastX[i], m.getZ() - lastZ[i]);
            lastX[i] = m.getX();
            lastZ[i] = m.getZ();
        }
        if (dump != null) dump(now);
        if (ticks < RUN) return false;
        madeSeen = 0;
        if (near) {
            nearOf.add(new long[] {mode(), AiCommander.paired - paired0, AiCommander.coupled - coupled0, AiCommander.expired - expired0, roundTicks, crossTicks, flankJobs.size(),
                    flankThere.size(), flankContacts, flankWide, inReach, inSight});
            AiCommander.made.clear();
            clear();
            if (++nearDone >= nears) {
                near = false;
                if (pinRounds == 0) return finish();
                pinRound = true;
                pinDone = 0;
            }
            start(round + 1);
            return false;
        }
        if (pinRound) {
            pinOf.add(new long[] {pinReport, pinHeld, pinHelp, pinHelpKind});
            AiCommander.made.clear();
            clear();
            if (++pinDone >= pinRounds) {
                pinRound = false;
                return finish();
            }
            start(round + 1);
            return false;
        }
        results.add(new int[] {seenAt, down()});
        idles.add(new long[] {alive, idle, idleWhy[0], idleWhy[1], idleWhy[2], idleWhy[3], idleLate});
        // the tasks given, then the stragglers sent after a squad
        int[] t = new int[given0.length + 1];
        for (int k = 0; k < given0.length; k++) t[k] = AiCommander.given[k] - given0[k];
        t[given0.length] = AiCommander.strays - strays0;
        tasksOf.add(t);
        movesOf.add(new long[] {modeTicks[0], modeTicks[1], modeTicks[2], modeTicks[3], bounding, covering, inCover});
        pairsOf.add(new long[] {flankContacts, flankWide, AiCommander.paired - paired0, AiCommander.coupled - coupled0, AiCommander.expired - expired0, AiCommander.coupleTicks - coupleTicks0,
                roundTicks, crossTicks, AiCommander.pressed - pressed0});
        approach.add(new double[] {mode(), looks, inReach, inSight, walked / Math.max(1, ours.size()), firstSeenFrom});
        if (mode() == STAFF) {
            // a copy first: a way may still come in off the server thread
            List<HqGrid.Route> made = new ArrayList<>(AiCommander.made);
            ways.addAll(made.subList(0, Math.min(madeBefore, made.size())));
        }
        AiCommander.made.clear();
        clear();
        if (round + 1 >= rounds) {
            startFar();
            return false;
        }
        start(round + 1);
        return false;
    }

    /** the far round (C2b): the side asleep in its camp, the player beyond aiRadius standing still */
    private void startFar() {
        far = true;
        ticks = 0;
        round = rounds;
        seenAt = -1;
        AiCommander.on = AiCommander.staff = AiCommander.tasks = StaffMove.on = AiCommander.pairs = true;
        AiCommander.forget(level);
        AiSquads.on = true;
        AiSquads.offTeams.clear();
        AiSquads.forget(level);
        AiBots.awake = false;
        AiCommander.made.clear();
        slept.clear();
        drifted0 = AiCommander.drifted;
        driftBlocked0 = AiCommander.driftBlocked;
        for (int s = 0; s < SQUADS; s++)
            for (int i = 0; i < EACH; i++) ours.add(spawn(X0 + SQUAD_X[s] + (i - 1) * 2, Z0 + CAMP_Z, A, 0F));
        player = new FakePlayer(level, new GameProfile(UUID.fromString("6a1b2c3d-0000-4000-8000-c7c7c7c7e0ff"), "armsai_warfar"));
        player.setGameMode(GameType.SURVIVAL);
        player.snapTo(X0 + FAR_X, y, Z0 + FAR_Z, 180F, 0F);
        level.addNewPlayer(player);
        AiCheckReact.hittable(player);
        AiBots.refreshTargets(level);
    }

    private boolean farTick() {
        ticks++;
        AiCommander.on = AiCommander.staff = AiCommander.tasks = StaffMove.on = AiCommander.pairs = true;
        AiBots.awake = false;
        player.snapTo(X0 + FAR_X, y, Z0 + FAR_Z, 180F, 0F);
        player.setHealth(player.getMaxHealth());
        if (ticks % 20 == 0) {
            int awake = 0;
            for (Mob m : ours) {
                AiBot b = AiBots.get(m);
                if (b == null || !m.isAlive()) continue;
                if (!b.active) slept.add(m.getId());
                else if (slept.contains(m.getId())) awake++;
                farNearest = Math.min(farNearest, Math.hypot(m.getX() - player.getX(), m.getZ() - player.getZ()));
            }
            if (awake > 0 && farWoke < 0) farWoke = ticks;
            farMost = Math.max(farMost, awake);
        }
        if (dump != null) dump(level.getGameTime());
        if (ticks < FAR_RUN) return false;
        clear();
        AiCommander.made.clear();
        AiBots.awake = true;
        far = false;
        if (nears == 0 && pinRounds == 0) return finish();
        near = nears > 0;
        pinRound = !near;
        nearDone = pinDone = 0;
        start(round + 1);
        return false;
    }

    private void dump(long now) {
        for (; madeSeen < AiCommander.made.size(); madeSeen++) {
            HqGrid g = AiHqMap.of(level, A);
            if (g != null && g != dumped) {
                dumped = g;
                grids++;
                dump.printf(Locale.ROOT, "G %d %d %d%n", grids, g.x0, g.z0);
                float[][] all = {g.h, g.crown, g.wet, g.deep, g.seen};
                for (float[] f : all) {
                    StringBuilder b = new StringBuilder();
                    for (float v : f) b.append(String.format(Locale.ROOT, "%.2f ", v));
                    dump.println(b.toString().trim());
                }
                StringBuilder b = new StringBuilder();
                for (boolean v : g.known) b.append(v ? '1' : '0');
                dump.println(b);
            }
            HqGrid.Route r = AiCommander.made.get(madeSeen);
            StringBuilder b = new StringBuilder();
            for (double v : r.points()) b.append(String.format(Locale.ROOT, " %.1f", v));
            dump.printf(Locale.ROOT, "R %d %d %d %.1f %.1f %.1f %.1f %.3f %.3f %.1f %.1f%s%n", round, ticks, grids, r.fromX(), r.fromZ(), r.toX(), r.toZ(), r.seenPart(),
                    r.straightSeenPart(), r.length(), r.straight(), b);
        }
        if (ticks % LOOK == 0) {
            dump.printf(Locale.ROOT, "E %d %d %d %.1f %.1f %d%n", round, ticks, staff() ? 1 : 0, player.getX(), player.getZ(), seenAt);
            for (int i = 0; i < ours.size(); i++) {
                Mob m = ours.get(i);
                if (!m.isAlive()) continue;
                // its order: the task's kind (-1 R5's area, -2 none), where to, until when; a foe known (1) or not
                AiBot b = AiBots.get(m);
                AiCommander.Order o = b == null ? null : b.order;
                int kind = o == null ? -2 : o.job == null ? -1 : o.job.kind.ordinal();
                // C3: the squad's mode (-1 none), its turn, whether it goes this turn (bounds: its half), its cover found and at it; awake
                StaffMove.State st = o == null || o.job == null ? null : o.job.move;
                dump.printf(Locale.ROOT, "P %d %d %d %.1f %.1f %d %d %.1f %.1f %d %d %d %d %d %d %d %d %d%n", round, ticks, i, m.getX(), m.getZ(), inSight(m) ? 1 : 0, kind,
                        o == null ? 0 : o.x, o == null ? 0 : o.z, o == null ? 0 : o.until - now, b != null && b.memory.focus(m.getX(), m.getZ()) != null ? 1 : 0,
                        AiCommander.gaveUp, st == null || st.mode == null ? -1 : st.mode.ordinal(), st == null ? 0 : st.turn, o == null || st == null ? 0 : st.mode == StaffMove.Mode.BOUND ? (o.halfSince == st.since && o.half == st.moving ? 1 : 0) : o.coverSince == st.since && o.coverTurn == st.turn ? 1 : 0,
                        o != null && o.covered ? 1 : 0, o != null && StaffMove.atCover(b, o) ? 1 : 0, b != null && b.active ? 1 : 0);
            }
        }
        if (ticks >= RUN) dump.flush();
    }

    /** side A's picture of the player now: how far off, by how long unseen */
    private void picture(long now) {
        AiIntel.Group g = null;
        for (AiIntel.Group x : AiIntel.groups(level, A)) if (x.has(player.getId())) g = x;
        reports++;
        if (g == null) {
            missing++;
            return;
        }
        double err = Math.hypot(g.x() - player.getX(), g.z() - player.getZ());
        if (err > g.r() + 1e-6) outside++;
        long seen = g.known().seen;
        int k = AGE_NAMES.length - 1;
        if (seen != Long.MIN_VALUE) for (k = 0; now - seen > AGES[k]; k++) {
        }
        errSum[k] += err;
        errN[k]++;
        errMost[k] = Math.max(errMost[k], err);
    }

    /** the rounds by turns: no staff (before, R5), the staff's map and ways (C1), its tasks too (C2), the squads' ways of going too (C3) */
    static final int OFF = 0, STAFF = 1, TASKS = 2, MOVES = 3, PAIRS = 4, MODES = 5;
    static final String[] MODE_NAMES = {"no staff (before)", "the staff's ways (C1)", "the staff's ways and tasks (C2)", "the staff's ways, tasks and ways of going (C3)",
            "the staff's pairs working together too (C4)"};

    private int mode() {
        if (pinRound) return PAIRS;
        if (near) return nearDone % 2 == 0 ? MOVES : PAIRS;
        return round % MODES;
    }

    private boolean staff() {
        return mode() != OFF;
    }

    /** within the player's sight: a look from its eyes reaches the mob's head or chest past blocks and leaves */
    private boolean inSight(Mob m) {
        Vec3 eye = player.getEyePosition();
        if (eye.distanceTo(m.position()) > HqGrid.RANGE) return false;
        for (double up : new double[] {m.getBbHeight() * 0.9, m.getBbHeight() * 0.6}) {
            Vec3 at = m.position().add(0, up, 0);
            var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, at, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, player));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return true;
        }
        return false;
    }

    /** the middle of the squad pinning the group this squad going round is for (its pair's, else any), null: none */
    private double[] pinMiddle(AiCommander.Job flank, long now) {
        AiCommander.Job pin = flank.partner;
        double x = 0, z = 0;
        int n = 0;
        for (Mob m : ours) {
            AiBot b = AiBots.get(m);
            AiCommander.Job j = b == null || !m.isAlive() || !AiCommander.has(b, now) ? null : b.order.job;
            if (j == null || j.kind != StaffPlan.Kind.PIN || pin != null && j != pin) continue;
            if (pin == null && !shares(j.ids, flank.ids)) continue;
            x += m.getX();
            z += m.getZ();
            n++;
        }
        return n == 0 ? null : new double[] {x / n, z / n};
    }

    private static boolean shares(int[] a, int[] b) {
        for (int x : a) for (int y : b) if (x == y) return true;
        return false;
    }

    private int down() {
        int n = 0;
        for (Mob m : ours) if (!m.isAlive()) n++;
        return n;
    }

    private void start(int r) {
        round = r;
        ticks = 0;
        seenAt = -1;
        reportsSeen = 0;
        looks = inReach = inSight = 0;
        alive = idle = 0;
        java.util.Arrays.fill(idleWhy, 0);
        idleLate = 0;
        java.util.Arrays.fill(modeTicks, 0);
        bounding = covering = inCover = 0;
        given0 = AiCommander.given.clone();
        strays0 = AiCommander.strays;
        firstSeenFrom = -1;
        madeBefore = 0;
        walked = 0;
        AiCommander.made.clear();
        AiCommander.staff = staff();
        AiCommander.tasks = mode() >= TASKS;
        StaffMove.on = mode() >= MOVES;
        AiCommander.pairs = mode() == PAIRS;
        AiCommander.on = true;
        flanksSeen.clear();
        wentRound.clear();
        flankJobs.clear();
        flankThere.clear();
        flankReady.clear();
        pinReport = pinHeld = pinHelp = -1;
        pinHelpKind = -1;
        pinMade0 = AiReports.made[AiReports.Kind.PINNED.ordinal()];
        pinFire = false;
        lastJob.clear();
        flankContacts = flankWide = roundTicks = crossTicks = 0;
        paired0 = AiCommander.paired;
        coupled0 = AiCommander.coupled;
        expired0 = AiCommander.expired;
        coupleTicks0 = AiCommander.coupleTicks;
        pressed0 = AiCommander.pressed;
        AiCommander.forget(level);
        AiSquads.on = true;
        AiSquads.offTeams.clear();
        AiSquads.forget(level);
        for (int s = 0; s < SQUADS; s++)
            for (int i = 0; i < EACH; i++) ours.add(spawn(X0 + SQUAD_X[s] + (i - 1) * 2, Z0 + (near || pinRound ? NEAR_Z : CAMP_Z), A, near || pinRound ? 180F : 0F));
        for (int i = 0; i < ours.size(); i++) {
            lastX[i] = ours.get(i).getX();
            lastZ[i] = ours.get(i).getZ();
        }
        player = new FakePlayer(level, new GameProfile(UUID.fromString("6a1b2c3d-0000-4000-8000-c7c7c7c7e0" + String.format(Locale.ROOT, "%02d", r % 100)), "armsai_war" + r));
        player.setGameMode(GameType.SURVIVAL);
        player.snapTo(X0 + WALK_X, y, Z0 + WALK_Z, 180F, 0F);
        level.addNewPlayer(player);
        AiCheckReact.hittable(player);
        AiBots.refreshTargets(level);
    }

    private void clear() {
        for (Mob m : ours) m.discard();
        ours.clear();
        if (player != null) {
            level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
            player = null;
        }
    }

    private Mob spawn(double x, double z, String team, float yaw) {
        Mob m = AiSpawn.spawn(level, new Vec3(x, y, z), AiSpawn.Kind.DUMMY, 1, rifle).get(0);
        m.setPos(x, y, z);
        m.setYRot(yaw);
        m.setYHeadRot(yaw);
        m.setYBodyRot(yaw);
        AiTeams.join(level, m, team);
        AiBot b = AiBots.get(m);
        if (b != null) b.gunner.ammo.set(b.gunner.capacity() * 4);
        return m;
    }

    /** a pinned round's tick (C5): the first squad suppressed from PIN_AT; its report, its task to pin where it is, the other's help */
    private void pinTick(long now) {
        if (ticks < PIN_AT) return;
        // the fire from the player once the side knows of it (a group to send help round), not before
        if (!pinFire) for (Mob m : ours) {
            AiBot b = AiBots.get(m);
            if (b != null && m.isAlive() && AiCommander.has(b, now) && b.order.ids.length > 0) pinFire = true;
        }
        if (!pinFire) return;
        if (ticks % 10 == 0) for (int i = 0; i < EACH && i < ours.size(); i++) {
            AiBot b = AiBots.get(ours.get(i));
            if (b != null && ours.get(i).isAlive()) b.prone.feel(acup.gunfeel.SuppressionScale.MAX, true, now);
        }
        if (pinReport < 0 && AiReports.made[AiReports.Kind.PINNED.ordinal()] > pinMade0) pinReport = ticks;
        if (pinReport < 0) return;
        for (int i = 0; i < ours.size(); i++) {
            AiBot b = AiBots.get(ours.get(i));
            AiCommander.Job j = b == null || !ours.get(i).isAlive() || !AiCommander.has(b, now) ? null : b.order.job;
            if (j == null) continue;
            // the pinned one: told to pin where it is (its place within PLACE of it)
            // the pinned one: the base of fire, pinning (its pair's place, or where it is)
            if (i < EACH && pinHeld < 0 && j.kind == StaffPlan.Kind.PIN) pinHeld = ticks;
            // the other: going round (as the pinned one's pair already, or sent so), or sent to pin as well
            boolean round = j.kind == StaffPlan.Kind.FLANK && j.partner != null;
            if (!round && j.since < now - (ticks - pinReport)) continue;
            if (i >= EACH && pinHelp < 0 && (round || j.kind == StaffPlan.Kind.PIN)) {
                pinHelp = ticks;
                pinHelpKind = j.kind.ordinal();
            }
        }
    }

    /** the angle at the player between a place and a mob, degrees */
    private double angle(double[] at, Mob m) {
        double ax = at[0] - player.getX(), az = at[1] - player.getZ(), bx = m.getX() - player.getX(), bz = m.getZ() - player.getZ();
        double cos = (ax * bx + az * bz) / Math.max(1e-6, Math.hypot(ax, az) * Math.hypot(bx, bz));
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }

    private boolean finish() {
        if (dump != null) dump.close();
        long drifted = AiCommander.drifted - drifted0, blocked = AiCommander.driftBlocked - driftBlocked0;
        check.out(String.format(Locale.ROOT, "     war, far (C2b): the side asleep %.0f blocks off the player (aiRadius %.0f); the first woke at tick %d, %d of %d awake at most, the nearest %.0f"
                + " blocks off; %d steps the staff moved them asleep, %d it could not", Math.hypot(FAR_X - SQUAD_X[0], FAR_Z - CAMP_Z), AiBots.radius(), farWoke, farMost,
                SQUADS * EACH, farNearest, drifted, blocked));
        check.check(String.format(Locale.ROOT, "war: far (C2b), the staff moves its squads asleep till they wake near the player: %d of %d woke (half at least), the first at tick %d"
                + " (%d at most)", farMost, SQUADS * EACH, farWoke, FAR_RUN), drifted > 0 && farMost * 2 >= SQUADS * EACH);
        AiCommander.on = true;
        AiCommander.staff = true;
        AiCommander.tasks = true;
        StaffMove.on = true;
        AiCommander.pairs = true;
        long reportsAll = AiIntel.reports - reports0;
        double usReport = reportsAll == 0 ? 0 : (AiIntel.nanos - nanos0) / 1000.0 / reportsAll;
        // one side reports every REPORT ticks: its cost a tick
        double usTick = usReport / AiIntel.REPORT;
        double[] sightOf = new double[MODES], walkOf = new double[MODES], firstOf = new double[MODES], fromOf = new double[MODES];
        int[] fromN = new int[MODES];
        // rounds of each, and of them those in which one of the side came within the player's reach
        int[] roundsOf = new int[MODES], reachedOf = new int[MODES];
        long[] aliveOf = new long[MODES], idleOf = new long[MODES], tasksWhy = new long[7];
        long bounds = 0, covers = 0, coversHard = 0, pairsMade = 0, pairsReady = 0, pairsLate = 0;
        long[] flankOf = new long[MODES], wideOf = new long[MODES], roundOf = new long[MODES], crossOf = new long[MODES];
        for (int i = 0; i < results.size(); i++) {
            double[] a = approach.get(i);
            int on = (int) a[0];
            check.out(String.format(Locale.ROOT, "     war, round %d, %s: the player first seen at tick %d, ours down %d; till then in its sight %.1f%% of the time"
                    + " within its reach (%.0f%% of the time within it), first in its sight %.0f blocks off, %.0f blocks walked each", i + 1, MODE_NAMES[on],
                    results.get(i)[0], results.get(i)[1], a[2] == 0 ? 0 : 100 * a[3] / a[2], a[1] == 0 ? 0 : 100 * a[2] / a[1], a[5], a[4]));
            roundsOf[on]++;
            if (a[2] > 0) {
                sightOf[on] += a[3] / a[2];
                reachedOf[on]++;
            }
            walkOf[on] += a[4];
            if (a[5] >= 0) {
                fromOf[on] += a[5];
                fromN[on]++;
            }
            firstOf[on] += results.get(i)[0] < 0 ? RUN : results.get(i)[0];
            long[] id = idles.get(i);
            aliveOf[on] += id[0];
            idleOf[on] += id[1];
            // the whys with the staff's tasks, all such rounds
            if (on >= TASKS && id.length >= 7) for (int k = 0; k < 7; k++) tasksWhy[k] += id[k];
            StringBuilder kinds = new StringBuilder();
            for (StaffPlan.Kind k : StaffPlan.Kind.values())
                if (tasksOf.get(i)[k.ordinal()] > 0) kinds.append(kinds.length() == 0 ? "" : ", ").append(k.name().toLowerCase(Locale.ROOT)).append(' ').append(tasksOf.get(i)[k.ordinal()]);
            int strays = tasksOf.get(i)[StaffPlan.Kind.values().length];
            check.out(String.format(Locale.ROOT, "     war, round %d: standing with no foe known and no order %.1f%% of the soldiers' time%s; tasks given: %s%s", i + 1,
                    id[0] == 0 ? 0 : 100.0 * id[1] / id[0], whys(id), kinds.length() == 0 ? "none" : kinds, strays > 0 ? "; " + strays + " sent after their squad" : ""));
            long[] mv = movesOf.get(i);
            long[] pr = pairsOf.get(i);
            if (on >= TASKS) check.out(String.format(Locale.ROOT, "     war, round %d: squads going round that saw the player within 64 blocks %d, of them %.0f degrees or more off the pinning one %d; pairs %d,"
                    + " ready in time %d (%.0f s on average), out of time %d; the player seen by one going round %d ticks, by it and one pinning at once %d; pinning ones firing on its places for the pair %d", i + 1, pr[0], WIDE, pr[1], pr[2], pr[3],
                    pr[3] == 0 ? 0 : pr[5] / 20.0 / pr[3], pr[4], pr[6], pr[7], pr[8]));
            flankOf[on] += pr[0];
            roundOf[on] += pr[6];
            crossOf[on] += pr[7];
            wideOf[on] += pr[1];
            if (on == PAIRS) {
                pairsMade += pr[2];
                pairsReady += pr[3];
                pairsLate += pr[4];
            }
            if (on >= MOVES) {
                check.out(String.format(Locale.ROOT, "     war, round %d: the squads' going (C3), looks: march %d, bounds %d, creeping %d, assault %d; bounding out of a fight %d, one of the half covering at its place %d, in cover there %d",
                        i + 1, mv[0], mv[1], mv[2], mv[3], mv[4], mv[5], mv[6]));
                bounds += mv[4];
                covers += mv[5];
                coversHard += mv[6];
            }
        }
        for (int on = 0; on < MODES; on++)
            if (roundsOf[on] > 0) check.out(String.format(Locale.ROOT, "     war, %s, %d rounds: standing with no foe known and no order %.1f%% of the soldiers' time; in the player's sight till first seen"
                    + " %.1f%% of the time within its reach, first in its sight %.0f blocks off, first seen at tick %.0f, %.0f blocks walked each", MODE_NAMES[on], roundsOf[on],
                    aliveOf[on] == 0 ? 0 : 100.0 * idleOf[on] / aliveOf[on],
                    reachedOf[on] == 0 ? 0 : 100 * sightOf[on] / reachedOf[on], fromN[on] == 0 ? -1 : fromOf[on] / fromN[on], firstOf[on] / roundsOf[on], walkOf[on] / roundsOf[on]));
        double waySeen = 0, straightSeen = 0, longer = 0, wayAll = 0, shortestAll = 0, seenBlocks = 0, shortestBlocks = 0;
        // each way against the shortest one there, up to the river (beyond its ford every way goes the same, over open
        // ground): how many are less in the enemy's sight than it (strictly), and the blocks in sight of them all
        // against those of the shortest (one round's few ways averaged swung); a way whose shortest is out of sight
        // there has nothing to keep out of, and one that is the shortest had no better: neither counted way by way
        int lessSeen = 0, open = 0;
        for (HqGrid.Route r : ways) {
            waySeen += r.dryPart() / ways.size();
            straightSeen += r.shortestDryPart() / ways.size();
            wayAll += r.seenPart() / ways.size();
            shortestAll += r.shortestSeenPart() / ways.size();
            longer += r.length() / Math.max(1, r.shortest()) / ways.size();
            seenBlocks += r.dryPart() * r.dry();
            shortestBlocks += r.shortestDryPart() * r.shortestDry();
                if (r.shortestDryPart() <= 0 || r.length() == r.shortest()) continue;
            open++;
            if (r.dryPart() < r.shortestDryPart()) lessSeen++;
        }
        long routesAll = AiCommander.routes.get() - routes0;
        double msRoute = routesAll == 0 ? 0 : (AiCommander.routeNanos.get() - routeNanos0) / 1e6 / routesAll;
        check.out(String.format(Locale.ROOT, "     war, the staff's ways (C1 rounds) before the player was seen: %d, in the enemy's sight (as the staff thinks) up to the river %.1f%% against %.1f%% on the shortest way, all the way (short of the last %.0f blocks)"
                + " %.1f%% against %.1f%%, %.2f times as long as the shortest; %.2f ms a way (off the server thread); %s", ways.size(), 100 * waySeen, 100 * straightSeen, HqGrid.END,
                100 * wayAll, 100 * shortestAll, longer, msRoute, AiHqMap.info()));
        StringBuilder t = new StringBuilder("     war, the picture's error by how long the player was unseen:");
        for (int k = 0; k < AGE_NAMES.length; k++)
            t.append(String.format(Locale.ROOT, " %s %s", AGE_NAMES[k], errN[k] == 0 ? "-" : String.format(Locale.ROOT, "%.1f (most %.1f, %d)", errSum[k] / errN[k], errMost[k], errN[k])));
        check.out(t.toString());
        check.out(String.format(Locale.ROOT, "     war, reports: %d of side A, %d without the player, %d with it out of its circle; %.1f us a report, %.2f us a tick a side", reports,
                missing, outside, usReport, usTick));
        check.out(String.format(Locale.ROOT, "     war, orders: the player out of the picture's circle when the side gave its orders %d times of %d (the picture as of its last report)",
                orderedOut, orderedAt));
        check.out(String.format(Locale.ROOT, "     war, river crossings (AiCrossing, all rounds): begun %d, over %d, not over %d; watches at a bank %d, places found %d",
                AiCrossing.begun - crossBegun0, AiCrossing.over - crossOver0, AiCrossing.notOver - crossNotOver0, AiCrossing.watches - crossWatches0, AiCrossing.found - crossFound0) + String.format(Locale.ROOT, "; not over: all down %d, too long %d, the order taken back %d, none came by %d",
                AiCrossing.whyNot[0] - crossWhy0[0], AiCrossing.whyNot[1] - crossWhy0[1], AiCrossing.whyNot[2] - crossWhy0[2], AiCrossing.whyNot[3] - crossWhy0[3])
                + String.format(Locale.ROOT, "; with smoke %d (under fire %d, its own fire heard %d, a foe known at the far bank %d)", AiCrossing.smoked - crossSmoked0,
                AiCrossing.smokeWhy[0] - smokeWhy0[0], AiCrossing.smokeWhy[1] - smokeWhy0[1], AiCrossing.smokeWhy[2] - smokeWhy0[2]));
        check.check("war: all rounds ran", results.size() == rounds);
        check.check("war: the side's picture has the player at every report, always within its circle", reports > 0 && missing == 0 && outside == 0);
        check.check(String.format(Locale.ROOT, "war: seen lately (0-5 s), the picture is off by %.1f on average (at most the circle's least radius, %.0f)",
                errN[0] == 0 ? -1 : errSum[0] / errN[0], IntelBook.R_MIN), errN[0] > 0 && errSum[0] / errN[0] <= IntelBook.R_MIN);
        check.check(String.format(Locale.ROOT, "war: the picture costs %.2f us a tick a side (at most 20)", usTick), usTick <= 20);
        boolean staffRan = roundsOf[STAFF] > 0, tasksRan = roundsOf[TASKS] + roundsOf[MOVES] + roundsOf[PAIRS] > 0;
        boolean enoughWays = ways.size() >= WAYS_MIN;
        check.check(String.format(Locale.ROOT, "war: the staff's ways keep out of the enemy's sight up to the river: %d of %d (the shortest aside) less in it than the shortest ways there (2/3 at least, from 3 on),"
                        + " %.0f blocks of them in it against %.0f of the shortest (%.0f%% of it, %.0f%% at most), %.2f times as long as the shortest (1.5 at most)%s", lessSeen, open,
                seenBlocks, shortestBlocks, shortestBlocks == 0 ? 0 : 100 * seenBlocks / shortestBlocks, 100 * WAY_SEEN, longer,
                enoughWays ? "" : String.format(Locale.ROOT, "; too few ways (%d of them needed): not checked", WAYS_MIN)),
                // a staff that ran and made no way at all fails
                !staffRan || !ways.isEmpty() && (!enoughWays || (open < BY_WAY || 3 * lessSeen >= 2 * open) && seenBlocks <= WAY_SEEN * shortestBlocks && longer <= 1.5));
        check.check(String.format(Locale.ROOT, "war: a way takes %.2f ms off the server thread (5 at most)", msRoute), msRoute <= 5);
        // C2: with the staff's tasks no squad stands about with nothing to do, and the squads were sent at the player
        // (a reserve or a hold is an order too: not all of them)
        int sentAt = 0;
        long tasksAlive = 0, tasksIdle = 0;
        for (int on = TASKS; on < MODES; on++) {
            tasksAlive += aliveOf[on];
            tasksIdle += idleOf[on];
        }
        for (int i = 0; i < tasksOf.size(); i++)
            if (i % MODES >= TASKS) for (StaffPlan.Kind k : new StaffPlan.Kind[] {StaffPlan.Kind.ADVANCE, StaffPlan.Kind.PIN, StaffPlan.Kind.FLANK}) sentAt += tasksOf.get(i)[k.ordinal()];
        check.check(String.format(Locale.ROOT, "war: with the staff's tasks, the soldiers stand with no foe known and no order %.1f%% of their time%s (2%% at most); %d tasks to advance, pin or go round",
                tasksAlive == 0 ? 0 : 100.0 * tasksIdle / tasksAlive, whys(tasksWhy), sentAt), !tasksRan || tasksAlive > 0 && tasksIdle * 50 <= tasksAlive && sentAt > 0);
        // C3: bounding, one half at least is in cover facing the enemy most of the time
        // C4: the squad going round comes on the group from its side; before it (C2, C3) as a share too
        long nearFlank = 0, nearWide = 0;
        for (long[] r : nearOf)
            if (r[0] == PAIRS) {
                nearFlank += r[8];
                nearWide += r[9];
            }
        if (roundsOf[PAIRS] > 0) {
            check.out(String.format(Locale.ROOT, "     war, squads going round that saw the player within 64 blocks %.0f degrees or more off the pinning one: C2 %d of %d, C3 %d of %d, C4 %d of %d;"
                    + " C4 pairs %d, ready in time %d, out of time %d", WIDE, wideOf[TASKS], flankOf[TASKS], wideOf[MOVES], flankOf[MOVES], wideOf[PAIRS], flankOf[PAIRS],
                    pairsMade, pairsReady, pairsLate));
            check.out(String.format(Locale.ROOT, "     war, the player under fire from two sides (one going round and one pinning see it at once), ticks a round: C2 %.0f, C3 %.0f, C4 %.0f;"
                    + " of the ticks one going round sees it: C2 %.0f%%, C3 %.0f%%, C4 %.0f%%", (double) crossOf[TASKS] / Math.max(1, roundsOf[TASKS]),
                    (double) crossOf[MOVES] / Math.max(1, roundsOf[MOVES]), (double) crossOf[PAIRS] / Math.max(1, roundsOf[PAIRS]),
                    100.0 * crossOf[TASKS] / Math.max(1, roundOf[TASKS]), 100.0 * crossOf[MOVES] / Math.max(1, roundOf[MOVES]), 100.0 * crossOf[PAIRS] / Math.max(1, roundOf[PAIRS])));
        }
        // C4 run near or on the field: its angle checked (near alone the default war's: its field rounds may hold no C4)
        if (roundsOf[PAIRS] > 0 || nearOf.stream().anyMatch(r -> r[0] == PAIRS)) {
            // near, the camp 80 blocks off: one of the pair sees the player within CLOSE before they part; there the angle at its signal
            // measured where it is sure to be: near, at the pair's signal (the one going round at its place); and where it
            // sees the player within CLOSE (seldom on the field: the river); too few of them all, nothing measured: a FAIL
            long angles = readyAngles + flankOf[PAIRS] + nearFlank, wide = readyWide + wideOf[PAIRS] + nearWide;
            check.check(String.format(Locale.ROOT, "war: the pairs (C4), the squad going round %.0f degrees or more off the pinning one: %d of %d (half at least, %d measured at least%s):"
                    + " near at its signal %d of %d, coming on the player within %.0f near %d of %d, on the field %d of %d; %d pairs made", WIDE, wide, angles, ANGLES_MIN,
                    angles < ANGLES_MIN ? ", NOT MEASURED" : "", readyWide, readyAngles, CLOSE, nearWide, nearFlank, wideOf[PAIRS], flankOf[PAIRS], pairsMade),
                    angles >= ANGLES_MIN && wide * 2 >= angles);
        }
        if (roundsOf[PAIRS] > 0)
            check.check(String.format(Locale.ROOT, "war: the pairs (C4), no squad going round for a pair sent across the river from the camp (its ford too far for the pair's time):"
                    + " %d (from nearer the ford, in time: %d)", pairsAcross, pairsAcrossAll - pairsAcross), pairsAcross == 0);
        // the near rounds (C4): the way round made in time, the pair works as one
        if (!nearOf.isEmpty()) {
            long[][] n = new long[MODES][12];
            for (long[] r : nearOf) {
                int on = (int) r[0];
                n[on][0]++;
                for (int k = 1; k < r.length; k++) n[on][k] += r[k];
            }
            long[] c3 = n[MOVES], c4 = n[PAIRS];
            check.out(String.format(Locale.ROOT, "     war, near (the camp %.0f blocks off the player's walk, no river between): rounds C3 %d, C4 %d; squads going round at their place (within %.0f)"
                    + " C3 %d of %d, C4 %d of %d; C4 pairs %d, ready in time %d, out of time %d; the player under fire from two sides, ticks a round: C3 %.0f, C4 %.0f;"
                    + " of the ticks one going round sees it: C3 %.0f%%, C4 %.0f%%", NEAR_Z - WALK_Z, c3[0], c4[0], AT_PLACE, c3[7], c3[6], c4[7], c4[6], c4[1], c4[2], c4[3],
                    (double) c3[5] / Math.max(1, c3[0]), (double) c4[5] / Math.max(1, c4[0]), 100.0 * c3[5] / Math.max(1, c3[4]), 100.0 * c4[5] / Math.max(1, c4[4])));
            // its place follows the player walking to and fro, and in its sight within 60 blocks it creeps (C3): seldom round by its signal here
            if (c4[0] > 0) check.out(String.format(Locale.ROOT, "     war, near (C4), the squad going round %.0f degrees or more off the pinning one at its signal %d times of %d",
                    WIDE, readyWide, readyAngles));
            if (c3[0] > 0 && c4[0] > 0 && c3[10] > 0 && c4[10] > 0) check.check(String.format(Locale.ROOT, "war: near (C4), the side no more in the player's sight within its reach before"
                    + " it is seen than C3's: %.1f%% against %.1f%% (10 points more at most)", 100.0 * c4[11] / c4[10], 100.0 * c3[11] / c3[10]),
                    (double) c4[11] / c4[10] <= (double) c3[11] / c3[10] + 0.1);
            if (c4[0] > 0) check.check(String.format(Locale.ROOT, "war: near (C4), the staff pairs its squads and the one going round is ready in time: %d pairs, %d ready in time"
                    + " (half at least), %d out of time", c4[1], c4[2], c4[3]), c4[1] > 0 && c4[2] * 2 >= c4[1]);
        }
        // C5: the squads' reports, the staff's answer, no flapping
        StringBuilder kinds = new StringBuilder();
        for (AiReports.Kind k : AiReports.Kind.values()) kinds.append(' ').append(k.name().toLowerCase(Locale.ROOT)).append(' ').append(AiReports.made[k.ordinal()]);
        check.out(String.format(Locale.ROOT, "     war, reports (C5):%s; answered (orders again) %d times, %.1f ticks after on the mean, %d at most; broke contact %d",
                kinds, AiCommander.askedN, AiCommander.askedN == 0 ? 0 : (double) AiCommander.askedLag / AiCommander.askedN, AiCommander.askedMost, AiCommander.broke));
        StringBuilder fl = new StringBuilder();
        for (int on = TASKS; on < MODES; on++) if (changesOf[on] > 0) fl.append(String.format(Locale.ROOT, " %s %d of %d;", MODE_NAMES[on], flapsOf[on], changesOf[on]));
        check.out("     war, task changes within " + FLAP + " ticks by mode:" + fl + " by kind " + flapKinds);
        // flaps were there before C5 (C4b's war, 60 rounds: 110 of 1032 changes, FLAPS_BEFORE: scouting to advancing,
        // falling back out of rounds and back); the reports' answers (most orders now) add none to speak of
        // by squads' tasks (each of a squad changes with it), not by soldiers; a help to a squad pinned down no flap; at
        // most what chance gives at the rate before C5 (flapsAllowed: n changes, k flaps, the most allowed)
        if (jobChanges > 0) check.check(String.format(Locale.ROOT, "war: a squad's task changed again within %d ticks of the last: n %d, k %d (%.1f%%), %d allowed (%.1f%% before C5,"
                + " %.0f%% by chance at most); %d of them given in answer to a report; %d sent to help one pinned down, not counted", FLAP, jobChanges, flaps, 100.0 * flaps / jobChanges,
                flapsAllowed(jobChanges), 100 * FLAPS_BEFORE, 100 * FLAP_LEVEL, flapsAnswered, flapsHelp), flaps <= flapsAllowed(jobChanges));
        if (AiCommander.askedN > 0) check.check(String.format(Locale.ROOT, "war: the staff gives its orders again at most %d ticks after a report (%d most)", AiCommander.ASKED_GAP + AiCommander.LOOK,
                AiCommander.askedMost), AiCommander.askedMost <= AiCommander.ASKED_GAP + AiCommander.LOOK);
        if (!pinOf.isEmpty()) {
            int said = 0, held = 0, helped = 0, flank = 0;
            StringBuilder ticksOf = new StringBuilder();
            for (long[] r : pinOf) {
                if (r[0] >= 0) said++;
                if (r[0] >= 0 && r[1] >= 0) held++;
                if (r[0] >= 0 && r[2] >= 0 && r[2] - r[0] <= HELP_IN) helped++;
                if (r[3] == StaffPlan.Kind.FLANK.ordinal()) flank++;
                ticksOf.append(String.format(Locale.ROOT, " %d/%d/%d", r[0] < 0 ? -1 : r[0] - PIN_AT, r[1] < 0 ? -1 : r[1] - r[0], r[2] < 0 ? -1 : r[2] - r[0]));
            }
            check.out(String.format(Locale.ROOT, "     war, pinned (C5), a round each (report after the fire / its pin after the report / help after it, ticks):%s; help going round %d of %d",
                    ticksOf, flank, helped));
            check.check(String.format(Locale.ROOT, "war: pinned (C5), the squad under fire reports and is told to hold where it is: %d and %d of %d rounds", said, held, pinOf.size()),
                    said == pinOf.size() && held == pinOf.size());
            check.check(String.format(Locale.ROOT, "war: pinned (C5), the other squad sent round or to pin within %d ticks of the report: %d of %d rounds", HELP_IN, helped, pinOf.size()),
                    helped == pinOf.size());
        }
        if (roundsOf[MOVES] + roundsOf[PAIRS] > 0)
            check.check(String.format(Locale.ROOT, "war: the squads bound (C3), out of a fight one half standing at its place facing the enemy (in cover where there was one) while the other goes"
                    + " %.1f%% of the time (80%% at least; %d looks; in cover %.1f%%)", bounds == 0 ? 0 : 100.0 * covers / bounds, bounds, bounds == 0 ? 0 : 100.0 * coversHard / bounds),
                    bounds > 0 && covers * 5 >= bounds * 4);
        // C3 against C2 (the cost of care: later seen, less in sight)
        if (roundsOf[TASKS] > 0 && roundsOf[MOVES] > 0)
            check.out(String.format(Locale.ROOT, "     war, C3 against C2: in the player's sight till first seen %.1f%% against %.1f%% of the time within its reach, first seen at tick %.0f against %.0f",
                    reachedOf[MOVES] == 0 ? 0 : 100 * sightOf[MOVES] / reachedOf[MOVES], reachedOf[TASKS] == 0 ? 0 : 100 * sightOf[TASKS] / reachedOf[TASKS],
                    firstOf[MOVES] / roundsOf[MOVES], firstOf[TASKS] / roundsOf[TASKS]));
        // with two rounds or more each way (the full war, -PaiCheckWarRounds): within the player's reach, before they
        // see it, less of the side in its sight with the staff than without; a round in which none of the side came
        // within its reach counts for neither (the side may have stood: that is no hiding), and most rounds must
        for (int on = STAFF; on < MODES; on++)
            if (roundsOf[OFF] >= 2 && roundsOf[on] >= 2) {
                String line = String.format(Locale.ROOT, "war: with %s, less of the side in the player's sight within its reach before it is seen: %.1f%% against %.1f%%"
                        + " (rounds within its reach %d of %d, %d of %d)", MODE_NAMES[on], reachedOf[on] == 0 ? 0 : 100 * sightOf[on] / reachedOf[on],
                        reachedOf[OFF] == 0 ? 0 : 100 * sightOf[OFF] / reachedOf[OFF], reachedOf[on], roundsOf[on], reachedOf[OFF], roundsOf[OFF]);
                // C4 on the river's field: the way round not made in time, no pair; the pinning one gets to its place out of
                // sight (C4b): no more in sight than C3's (PAIRS_WORSE at most)
                if (on == PAIRS && roundsOf[MOVES] >= 2 && reachedOf[MOVES] > 0)
                    check.check(line + String.format(Locale.ROOT, "; C3 %.1f%% (%.0f points more at most)", 100 * sightOf[MOVES] / reachedOf[MOVES], 100 * PAIRS_WORSE),
                            reachedOf[on] * 2 > roundsOf[on] && sightOf[on] / reachedOf[on] <= sightOf[MOVES] / reachedOf[MOVES] + PAIRS_WORSE);
                else check.check(line, reachedOf[on] * 2 > roundsOf[on] && reachedOf[OFF] * 2 > roundsOf[OFF] && sightOf[on] / reachedOf[on] < sightOf[OFF] / reachedOf[OFF]);
            }
        return true;
    }

    // ------------------------------------------------------------------ the field

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    private void build() {
        BlockState stone = Blocks.STONE.defaultBlockState(), grass = Blocks.GRASS_BLOCK.defaultBlockState(), air = Blocks.AIR.defaultBlockState(),
                water = Blocks.WATER.defaultBlockState(), log = Blocks.OAK_LOG.defaultBlockState(),
                leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int dx = 0; dx < FIELD; dx++)
            for (int dz = 0; dz < FIELD; dz++) {
                int x = X0 + dx, z = Z0 + dz;
                for (int h = 0; h < 18; h++) set(x, y + h, z, air);
                // the river short of the field's edges by a block: its ends walled in, its water stays in (the
                // field stands in the air)
                boolean river = dz >= RIVER_Z && dz < RIVER_Z + RIVER_W && dx > 0 && dx < FIELD - 1, ford = river && dx >= FORD_X0 && dx <= FORD_X1;
                int deep = !river ? 0 : ford ? 1 : RIVER_D;
                // solid down to under the river's bed everywhere
                for (int d = deep + 1; d <= RIVER_D + 1; d++) set(x, y - d, z, stone);
                for (int d = 1; d <= deep; d++) set(x, y - d, z, water);
                if (deep == 0) set(x, y - 1, z, grass);
                // the hill: round, steepest half way up
                double r = Math.hypot(dx - HILL_X, dz - HILL_Z);
                int hill = r >= HILL_R ? 0 : (int) Math.round(HILL_H * 0.5 * (1 + Math.cos(Math.PI * r / HILL_R)));
                for (int h = 0; h < hill; h++) set(x, y + h, z, h == hill - 1 ? grass : stone);
                if (hill > 0) set(x, y - 1, z, stone);
            }
        // the box the player hides in for a while: stone all round and over it
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int h = 0; h < 4; h++)
                    if (Math.abs(dx) == 2 || Math.abs(dz) == 2 || h == 3) set(X0 + BOX_X + dx, y + h, Z0 + BOX_Z + dz, stone);
        // the wood: a tree about every TREE blocks, a little off the grid (the same each run), with a bush round its foot
        java.util.Random rng = new java.util.Random(7);
        for (int tx = WOOD_X0; tx <= WOOD_X1; tx += TREE)
            for (int tz = WOOD_Z0; tz <= WOOD_Z1; tz += TREE) {
                int x = X0 + tx + rng.nextInt(3) - 1, z = Z0 + tz + rng.nextInt(3) - 1, trunk = 5 + rng.nextInt(2);
                for (int h = 0; h < trunk; h++) set(x, y + h, z, log);
                for (int lx = -2; lx <= 2; lx++)
                    for (int lz = -2; lz <= 2; lz++)
                        for (int h = trunk - 2; h <= trunk + 1; h++) {
                            boolean top = h >= trunk;
                            if (top && (Math.abs(lx) == 2 || Math.abs(lz) == 2) || lx == 0 && lz == 0 && h < trunk) continue;
                            set(x + lx, y + h, z + lz, leaves);
                        }
                // its undergrowth: a bush two high round the trunk (a wood of crowns over the head alone hides no one
                // standing in it from one standing level with it, a look goes under them); none with -PaiCheckWarBare
                if (!Boolean.getBoolean("ntmgunfeel.checkAiWarBare")) for (int lx = -1; lx <= 1; lx++)
                    for (int lz = -1; lz <= 1; lz++)
                        if (lx != 0 || lz != 0) for (int h = 0; h < 2; h++) set(x + lx, y + h, z + lz, leaves);
            }
        // the thicket by the ford: a bush two high, three across, on both banks (a way to the ford out of sight)
        if (!Boolean.getBoolean("ntmgunfeel.checkAiWarBare"))
            for (int tx = THICKET_FAR_X0; tx <= THICKET_X1; tx += THICKET)
                for (int tz = RIVER_Z - BANK; tz <= RIVER_Z + RIVER_W + BANK; tz += THICKET) {
                    boolean near = tz < RIVER_Z;
                    if (near && tx < THICKET_X0 || tz >= RIVER_Z - 2 && tz <= RIVER_Z + RIVER_W + 1) continue;
                    if (tx >= FORD_X0 - 3 && tx <= FORD_X1 + 3 && Math.abs(tz - (near ? RIVER_Z : RIVER_Z + RIVER_W)) <= 6) continue;
                    for (int lx = -1; lx <= 1; lx++)
                        for (int lz = -1; lz <= 1; lz++)
                            for (int h = 0; h < 2; h++) set(X0 + tx + lx, y + h, Z0 + tz + lz, leaves);
                }
    }
}
