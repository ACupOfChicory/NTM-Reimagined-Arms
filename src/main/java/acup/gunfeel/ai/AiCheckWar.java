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
 * one ford, and open ground. Side A, two squads of three (both sent: two to a group at most), at one edge, one by the hill, one by the wood; at the other, across the river, a player (a
 * fake one, in survival, kept whole) walking to and fro, for a while hidden in a stone box farther off. The hill's
 * crest and the wood lie well within the player's sight (128 blocks; A sees 96), the camp just out of it: whether the
 * side comes on behind them tells in how near it gets before the player first sees one of it. ROUNDS rounds of RUN ticks (-PaiCheckWarRounds, 4 by default: the full
 * check's short war), by turns with no staff (R5), with its map and ways (C1), with its tasks too (C2), with the
 * squads' ways of going too (C3: march, bounds, creeping, assault). Counted, at each of side A's reports
 * (AiIntel): how far its picture's middle is from the player by how long ago one of the side saw it, whether the
 * player was within the circle; the tick one of A first saw it; till then, how often each of A stood where the
 * player's eyes reached it (a look from its eyes to A's head or chest, blocks and leaves in the way), how far each
 * walked; the staff's ways (C1): how much of each lies where the staff thinks the enemy sees it, against the straight
 * line, how long it is against the straight; the time the reports, maps and ways took.
 */
final class AiCheckWar {
    static final int X0 = 3200, Z0 = 0, FIELD = 256, RUN = 2400, SQUADS = 2, EACH = 3;
    /** the hill: its middle, radius, height */
    static final int HILL_X = 80, HILL_Z = 96, HILL_R = 30, HILL_H = 12;
    /** the wood, a tree every TREE blocks or so */
    static final int WOOD_X0 = 150, WOOD_X1 = 210, WOOD_Z0 = 56, WOOD_Z1 = 124, TREE = 6;
    /** the river right across: from RIVER_Z, RIVER_W wide, RIVER_D deep; the ford, a block deep */
    static final int RIVER_Z = 128, RIVER_W = 6, RIVER_D = 3, FORD_X0 = 220, FORD_X1 = 227;
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
    static final int FAR_RUN = 2400;
    static final String A = "war_a";
    /** the reports' ages: up to each, ticks (the last: never seen) */
    static final long[] AGES = {100, 400, 1200, Long.MAX_VALUE};
    static final String[] AGE_NAMES = {"0-5 s", "5-20 s", "20-60 s", "over 60 s", "never seen"};
    static final Identifier RIFLE = Identifier.fromNamespaceAndPath("hbm", "gun_carbine");

    private final AiCheck check;
    private final ServerLevel level;
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
    private double firstSeenFrom;
    /** the staff's ways made before the player was first seen this round */
    private int madeBefore;
    private double walked;
    private final double[] lastX = new double[SQUADS * EACH], lastZ = new double[SQUADS * EACH];
    /** per round: {on, looks, in reach, in sight, walked, first seen from} */
    private final List<double[]> approach = new ArrayList<>();
    /** the staff's ways in the rounds it was on */
    private final List<HqGrid.Route> ways = new ArrayList<>();
    private long routeNanos0, routes0;
    static final int LOOK = 10;
    /** -PaiCheckWarDump: the staff's maps as each way was made, the ways, the soldiers' and the player's places */
    private java.io.PrintWriter dump;
    /** the far round: on; the tick the first of the side woke (-1: none), the most awake at once, the nearest any came */
    private boolean far;
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
        StaffMove.on = mode() == MOVES;
        long now = level.getGameTime();
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
        boolean hides = ticks >= HIDE_FROM && ticks < HIDE_TO;
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
            if (b.memory.focus(m.getX(), m.getZ()) == null && !AiCommander.has(b, now)) idle++;
        }
        // the squads' ways of going (C3): each squad's mode, and bounding, whether one of the half covering is in cover
        if (ticks % LOOK == 0) {
            java.util.Set<AiCommander.Job> jobs = new java.util.HashSet<>();
            for (Mob m : ours) {
                AiBot b = AiBots.get(m);
                if (b != null && m.isAlive() && b.order != null && b.order.job != null && AiCommander.has(b, now)) jobs.add(b.order.job);
            }
            for (AiCommander.Job j : jobs) {
                // a squad at its task's place stands there, its last mode left over
                if (j.move.mode == null || j.there) continue;
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
        results.add(new int[] {seenAt, down()});
        idles.add(new long[] {alive, idle});
        // the tasks given, then the stragglers sent after a squad
        int[] t = new int[given0.length + 1];
        for (int k = 0; k < given0.length; k++) t[k] = AiCommander.given[k] - given0[k];
        t[given0.length] = AiCommander.strays - strays0;
        tasksOf.add(t);
        movesOf.add(new long[] {modeTicks[0], modeTicks[1], modeTicks[2], modeTicks[3], bounding, covering, inCover});
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
        AiCommander.on = AiCommander.staff = AiCommander.tasks = StaffMove.on = true;
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
        AiCommander.on = AiCommander.staff = AiCommander.tasks = StaffMove.on = true;
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
        return finish();
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
    static final int OFF = 0, STAFF = 1, TASKS = 2, MOVES = 3, MODES = 4;
    static final String[] MODE_NAMES = {"no staff (before)", "the staff's ways (C1)", "the staff's ways and tasks (C2)", "the staff's ways, tasks and ways of going (C3)"};

    private int mode() {
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
        StaffMove.on = mode() == MOVES;
        AiCommander.on = true;
        AiCommander.forget(level);
        AiSquads.on = true;
        AiSquads.offTeams.clear();
        AiSquads.forget(level);
        for (int s = 0; s < SQUADS; s++)
            for (int i = 0; i < EACH; i++) ours.add(spawn(X0 + SQUAD_X[s] + (i - 1) * 2, Z0 + CAMP_Z, A, 0F));
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
        long reportsAll = AiIntel.reports - reports0;
        double usReport = reportsAll == 0 ? 0 : (AiIntel.nanos - nanos0) / 1000.0 / reportsAll;
        // one side reports every REPORT ticks: its cost a tick
        double usTick = usReport / AiIntel.REPORT;
        double[] sightOf = new double[MODES], walkOf = new double[MODES], firstOf = new double[MODES], fromOf = new double[MODES];
        int[] fromN = new int[MODES];
        // rounds of each, and of them those in which one of the side came within the player's reach
        int[] roundsOf = new int[MODES], reachedOf = new int[MODES];
        long[] aliveOf = new long[MODES], idleOf = new long[MODES];
        long bounds = 0, covers = 0, coversHard = 0;
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
            StringBuilder kinds = new StringBuilder();
            for (StaffPlan.Kind k : StaffPlan.Kind.values())
                if (tasksOf.get(i)[k.ordinal()] > 0) kinds.append(kinds.length() == 0 ? "" : ", ").append(k.name().toLowerCase(Locale.ROOT)).append(' ').append(tasksOf.get(i)[k.ordinal()]);
            int strays = tasksOf.get(i)[StaffPlan.Kind.values().length];
            check.out(String.format(Locale.ROOT, "     war, round %d: standing with no foe known and no order %.1f%% of the soldiers' time; tasks given: %s%s", i + 1,
                    id[0] == 0 ? 0 : 100.0 * id[1] / id[0], kinds.length() == 0 ? "none" : kinds, strays > 0 ? "; " + strays + " sent after their squad" : ""));
            long[] mv = movesOf.get(i);
            if (on == MOVES) {
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
        double waySeen = 0, straightSeen = 0, longer = 0;
        for (HqGrid.Route r : ways) {
            waySeen += r.seenPart() / ways.size();
            straightSeen += r.straightSeenPart() / ways.size();
            longer += r.length() / Math.max(1, r.straight()) / ways.size();
        }
        long routesAll = AiCommander.routes.get() - routes0;
        double msRoute = routesAll == 0 ? 0 : (AiCommander.routeNanos.get() - routeNanos0) / 1e6 / routesAll;
        check.out(String.format(Locale.ROOT, "     war, the staff's ways (C1 rounds) before the player was seen: %d, in the enemy's sight (as the staff thinks, short of the last %.0f blocks) %.1f%% against %.1f%% straight, %.2f times as long;"
                + " %.2f ms a way (off the server thread); %s", ways.size(), HqGrid.END, 100 * waySeen, 100 * straightSeen, longer, msRoute, AiHqMap.info()));
        StringBuilder t = new StringBuilder("     war, the picture's error by how long the player was unseen:");
        for (int k = 0; k < AGE_NAMES.length; k++)
            t.append(String.format(Locale.ROOT, " %s %s", AGE_NAMES[k], errN[k] == 0 ? "-" : String.format(Locale.ROOT, "%.1f (most %.1f, %d)", errSum[k] / errN[k], errMost[k], errN[k])));
        check.out(t.toString());
        check.out(String.format(Locale.ROOT, "     war, reports: %d of side A, %d without the player, %d with it out of its circle; %.1f us a report, %.2f us a tick a side", reports,
                missing, outside, usReport, usTick));
        check.out(String.format(Locale.ROOT, "     war, orders: the player out of the picture's circle when the side gave its orders %d times of %d (the picture as of its last report)",
                orderedOut, orderedAt));
        check.check("war: all rounds ran", results.size() == rounds);
        check.check("war: the side's picture has the player at every report, always within its circle", reports > 0 && missing == 0 && outside == 0);
        check.check(String.format(Locale.ROOT, "war: seen lately (0-5 s), the picture is off by %.1f on average (at most the circle's least radius, %.0f)",
                errN[0] == 0 ? -1 : errSum[0] / errN[0], IntelBook.R_MIN), errN[0] > 0 && errSum[0] / errN[0] <= IntelBook.R_MIN);
        check.check(String.format(Locale.ROOT, "war: the picture costs %.2f us a tick a side (at most 20)", usTick), usTick <= 20);
        boolean staffRan = roundsOf[STAFF] > 0, tasksRan = roundsOf[TASKS] + roundsOf[MOVES] > 0;
        check.check(String.format(Locale.ROOT, "war: the staff's ways keep out of the enemy's sight: %.1f%% of them in it against %.1f%% straight (40%% less at least), %.2f times as long (1.5 at most)",
                100 * waySeen, 100 * straightSeen, longer), !staffRan || !ways.isEmpty() && waySeen <= straightSeen * 0.6 && longer <= 1.5);
        check.check(String.format(Locale.ROOT, "war: a way takes %.2f ms off the server thread (5 at most)", msRoute), msRoute <= 5);
        // C2: with the staff's tasks no squad stands about with nothing to do, and the squads were sent at the player
        // (a reserve or a hold is an order too: not all of them)
        int sentAt = 0;
        long tasksAlive = aliveOf[TASKS] + aliveOf[MOVES], tasksIdle = idleOf[TASKS] + idleOf[MOVES];
        for (int i = 0; i < tasksOf.size(); i++)
            if (i % MODES >= TASKS) for (StaffPlan.Kind k : new StaffPlan.Kind[] {StaffPlan.Kind.ADVANCE, StaffPlan.Kind.PIN, StaffPlan.Kind.FLANK}) sentAt += tasksOf.get(i)[k.ordinal()];
        check.check(String.format(Locale.ROOT, "war: with the staff's tasks, the soldiers stand with no foe known and no order %.1f%% of their time (2%% at most); %d tasks to advance, pin or go round",
                tasksAlive == 0 ? 0 : 100.0 * tasksIdle / tasksAlive, sentAt), !tasksRan || tasksAlive > 0 && tasksIdle * 50 <= tasksAlive && sentAt > 0);
        // C3: bounding, one half at least is in cover facing the enemy most of the time
        if (roundsOf[MOVES] > 0)
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
            if (roundsOf[OFF] >= 2 && roundsOf[on] >= 2)
                check.check(String.format(Locale.ROOT, "war: with %s, less of the side in the player's sight within its reach before it is seen: %.1f%% against %.1f%%"
                        + " (rounds within its reach %d of %d, %d of %d)", MODE_NAMES[on], reachedOf[on] == 0 ? 0 : 100 * sightOf[on] / reachedOf[on],
                        reachedOf[OFF] == 0 ? 0 : 100 * sightOf[OFF] / reachedOf[OFF], reachedOf[on], roundsOf[on], reachedOf[OFF], roundsOf[OFF]),
                        reachedOf[on] * 2 > roundsOf[on] && reachedOf[OFF] * 2 > roundsOf[OFF] && sightOf[on] / reachedOf[on] < sightOf[OFF] / reachedOf[OFF]);
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
    }
}
