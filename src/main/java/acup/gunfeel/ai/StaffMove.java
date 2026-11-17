package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ReaimTimes;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * How a squad goes along its staff's way (stage 7, C3), set again every AGAIN ticks by how near the enemy the side
 * knows of is and whether the staff's map has the squad in its sight:
 * <ul>
 * <li>a march, far off and out of its sight: in file behind the first, at a run;</li>
 * <li>bounds, nearer or in its sight: half the squad (its base of fire and marksman first) in cover facing the enemy
 * while the other half runs to cover farther along the way, then the other way round;</li>
 * <li>creeping, in its sight and near: low and slow, one or two at a time from cover to cover, the rest low in theirs;</li>
 * <li>an assault, the last of a way to advance: at a run, and no holding back in the fight that follows.</li>
 * </ul>
 * Only with a task (C2) and a way (C1); straight legs take the mode's pace alone.
 */
final class StaffMove {
    enum Mode {
        MARCH, BOUND, CREEP, ASSAULT
    }

    /** the server setting; off only for the stands' "before" (C2: all walk) */
    public static final String KEY = "aiCommanderMoves";
    static boolean on = true;
    /** farther than this from the nearest enemy (its circle's edge) and out of its sight: a march; blocks */
    static final double MARCH = 100;
    /** in its sight and nearer than this: creeping */
    static final double CREEP = 60;
    /** an advance this near its area (its middle half): the assault */
    static final double ASSAULT = 25;
    /** a march: this far behind the one ahead */
    static final double FILE = 3;
    /** a bound: this far past the foremost of the half covering along the way; the halves change over when the one going is there, or after BOUND_FOR ticks */
    static final double BOUND = 12;
    static final int BOUND_FOR = 200;
    /** creeping: a rush this far along the way; the next ones go when these are there, or after RUSH_FOR ticks */
    static final double RUSH = 10;
    static final int RUSH_FOR = 160;
    /** cover looked for this far round where a bound or rush ends; in it this near its middle */
    static final double COVER_R = 8, AT_COVER = 1.5;
    /** a place with no cover found (the open): there this near (a walk's end, AiCommander.go) */
    static final double AT_PLACE = 2.5;
    /** held on the cover cell this near its middle */
    static final double ON_CELL = 0.6;
    /** a bound's end with no cover round it: tried again this much nearer and farther along the way */
    static final double TRY = 6;
    /** a bound: the half going waits this long at most for the other to be in its cover, ticks */
    static final int SET_FOR = 60;
    /** the mode looked at again this often; a new one kept this long at least (not an assault), ticks */
    static final int AGAIN = 20, KEEP = 60;
    /** after an assault's last step it does not hold back this long (into its fight), ticks */
    static final int STORM = 400;
    /** an enemy's eyes, over the ground; places on the edge of its circle weighed besides its middle */
    static final double EYE = 1.6;
    static final int EDGE = 4;
    /** a cell of the staff's map this little in the enemy's sight is cover enough (out of its sight) */
    static final double HIDDEN = 0.25;

    private StaffMove() {
    }

    static boolean on() {
        return on && ReaimTimes.flag(KEY, true);
    }

    /** a squad's (its task's): its mode and since when, the nearest enemy, its bounds or rushes */
    static final class State {
        @Nullable Mode mode;
        long since, next = Long.MIN_VALUE;
        /** the nearest enemy the side knows of: where it faces it from cover, the radius of its circle (none known: false) */
        double foeX, foeZ, foeR;
        boolean foe;
        /** bounds and rushes begun in this mode; the half going (bounds) or the first rushing (creeping); since when; where along the way the bound ends */
        int turn, moving;
        long turnAt;
        double to;
        /** bounds: the half covering is in its cover (or had its time to get there), the other goes */
        boolean set;
    }

    /** a soldier's step: go to x, z (the cover cell, else the ground there) at speed, or hold where it is facing the enemy; low: crouching; again: its leg set again so soon at the latest, ticks */
    record Leg(double x, double z, long cell, double speed, boolean hold, boolean low, boolean fresh, int again) {
    }

    /** the mode for a squad foe blocks from the nearest enemy, in its sight or not, advancing or not, left blocks from its area */
    static Mode mode(double foe, boolean seen, boolean advance, double left) {
        if (advance && left <= ASSAULT) return Mode.ASSAULT;
        if (seen && foe < CREEP) return Mode.CREEP;
        if (seen || foe <= MARCH) return Mode.BOUND;
        return Mode.MARCH;
    }

    /** a bound's halves, per one of the squad (in its order): 0 covers first (the base of fire and marksman, then the first), 1 goes first */
    static int[] halves(List<AiRole> roles) {
        int n = roles.size(), cover = (n + 1) / 2, c = 0;
        int[] h = new int[n];
        java.util.Arrays.fill(h, 1);
        for (int i = 0; i < n && c < cover; i++)
            if (roles.get(i) == AiRole.BASE || roles.get(i) == AiRole.MARKSMAN) {
                h[i] = 0;
                c++;
            }
        for (int i = 0; i < n && c < cover; i++)
            if (h[i] == 1) {
                h[i] = 0;
                c++;
            }
        return h;
    }

    /** rushing at once while creeping: one or two, half the squad at most */
    static int rushing(int n) {
        return Math.max(1, Math.min(2, n / 2));
    }

    /** how far along the way (from where it began) the place on it nearest x, z is */
    static double along(HqGrid.Route r, double x, double z) {
        double px = r.fromX(), pz = r.fromZ(), s = 0, best = 0, bestD = Double.MAX_VALUE;
        for (int j = 0; j < r.size(); j++) {
            double qx = r.x(j), qz = r.z(j), lx = qx - px, lz = qz - pz, l2 = lx * lx + lz * lz, l = Math.sqrt(l2);
            double t = l2 == 0 ? 1 : Mth.clamp(((x - px) * lx + (z - pz) * lz) / l2, 0, 1);
            double d = Math.hypot(px + lx * t - x, pz + lz * t - z);
            if (d < bestD) {
                bestD = d;
                best = s + l * t;
            }
            s += l;
            px = qx;
            pz = qz;
        }
        return best;
    }

    /** the place s along the way (its start before 0, its end past its length) */
    static double[] at(HqGrid.Route r, double s) {
        double px = r.fromX(), pz = r.fromZ();
        for (int j = 0; j < r.size(); j++) {
            double qx = r.x(j), qz = r.z(j), l = Math.hypot(qx - px, qz - pz);
            if (s <= l) {
                double t = l == 0 || s <= 0 ? 0 : s / l;
                return new double[] {px + (qx - px) * t, pz + (qz - pz) * t};
            }
            s -= l;
            px = qx;
            pz = qz;
        }
        return new double[] {px, pz};
    }

    /** the squad on its task now, in its order: those of it alive with this task */
    static List<AiBot> squad(AiCommander.Job j) {
        List<AiBot> out = new ArrayList<>();
        for (AiBot b : j.members) if (b.mob.isAlive() && b.order != null && b.order.job == j) out.add(b);
        return out;
    }

    /** the squad's mode, looked at again every AGAIN ticks by the first of it to step */
    static void update(ServerLevel level, AiCommander.Order o, long tick) {
        AiCommander.Job j = o.job;
        if (j == null) return;
        State st = j.move;
        if (tick < st.next) return;
        st.next = tick + AGAIN;
        List<AiBot> of = squad(j);
        if (of.isEmpty()) return;
        double mx = 0, mz = 0;
        for (AiBot b : of) {
            mx += b.mob.getX() / of.size();
            mz += b.mob.getZ() / of.size();
        }
        String team = AiSquads.team(of.get(0));
        double foe = Double.MAX_VALUE;
        st.foe = false;
        for (AiIntel.Group g : AiCommander.groups(level, team)) {
            double d = Math.hypot(g.x() - mx, g.z() - mz) - g.r();
            if (d < foe) {
                foe = d;
                st.foeX = g.x();
                st.foeZ = g.z();
                st.foeR = g.r();
                st.foe = true;
            }
        }
        HqGrid grid = AiHqMap.of(level, team);
        int i = grid == null ? -1 : grid.cellX(mx), k = grid == null ? -1 : grid.cellZ(mz);
        boolean seen = i >= 0 && k >= 0 && grid.seen(i, k) > 0.5;
        // falling back: at a run, never creeping
        Mode m = j.kind == StaffPlan.Kind.BACK ? Mode.MARCH : mode(foe, seen, j.kind == StaffPlan.Kind.ADVANCE, Math.hypot(o.x - mx, o.z - mz) - o.r * 0.5);
        if (m == st.mode || st.mode != null && m != Mode.ASSAULT && tick - st.since < KEEP) return;
        st.mode = m;
        st.since = tick;
        st.turn = 0;
        st.moving = 0;
        st.to = -1;
        st.set = false;
    }

    /** its way of going begun again at its next look (a river crossed: AiCommander.crossing) */
    static void afresh(AiCommander.Job j) {
        j.move.mode = null;
        j.move.next = Long.MIN_VALUE;
    }

    /** its pace on a straight leg (no way): by the squad's mode */
    static double speed(AiCommander.Order o) {
        Mode m = o.job == null ? null : o.job.move.mode;
        return m == Mode.MARCH ? march(o.job.kind) : m == Mode.ASSAULT ? AiSoldier.RUN : m == Mode.CREEP ? AiSoldier.CREEP : AiSoldier.WALK;
    }

    /** a march's pace: a jog to the fight; falling back, a run */
    static double march(StaffPlan.Kind kind) {
        return kind == StaffPlan.Kind.BACK ? AiSoldier.RUN : AiSoldier.MARCH;
    }

    /** holding its cover: facing the nearest enemy */
    static void face(AiBot bot, AiCommander.Order o) {
        State st = o.job == null ? null : o.job.move;
        if (st != null && st.foe && bot.mob.isAlive()) bot.gunner.aim(new Vec3(st.foeX, bot.mob.getEyeY(), st.foeZ));
    }

    /** an assault: it does not hold back in the fight after it */
    static void storm(AiBot bot, AiCommander.Order o, long tick) {
        if (o.job != null && o.job.move.mode == Mode.ASSAULT) bot.confidence.storm = tick + STORM;
    }

    /** on a leg of its own (straight, or catching up on the way): the mode's assault, and crouched while creeping */
    static void pace(AiBot bot, AiCommander.Order o, long tick) {
        storm(bot, o, tick);
        if (o.job != null && o.job.move.mode == Mode.CREEP && bot.brain instanceof AiSoldier s) s.crouch(true);
    }

    /**
     * a soldier's step along the way by its squad's mode; null: none set (as C2). stuck: its last leg of the mode could
     * not be walked (the cover cell out of reach): that cell is not picked again, it stays where it is for this turn
     */
    static @Nullable Leg leg(AiBot bot, ServerLevel level, AiCommander.Order o, HqGrid.Route way, long tick, boolean stuck) {
        AiCommander.Job j = o.job;
        if (j == null || j.move.mode == null) return null;
        if (stuck) {
            if (o.coverCell != Long.MIN_VALUE) o.badCell = o.coverCell;
            place(o, bot.mob.getX(), bot.mob.getZ());
        }
        State st = j.move;
        List<AiBot> of = squad(j);
        int me = of.indexOf(bot);
        if (me < 0) return null;
        double spread = o.of > 1 ? 3 : 0, a = Math.PI * 2 * o.slot / Math.max(1, o.of);
        switch (st.mode) {
            case MARCH -> {
                // the first to the next point of the way; each after it a little behind the one ahead, on the way (one
                // past it waits where it is)
                if (me == 0) return new Leg(way.x(o.at), way.z(o.at), Long.MIN_VALUE, march(j.kind), false, false, false, AiCommander.LEG_AGAIN);
                AiBot ahead = of.get(me - 1);
                double[] p = at(way, Math.max(along(way, bot.mob.getX(), bot.mob.getZ()), along(way, ahead.mob.getX(), ahead.mob.getZ()) - FILE));
                return new Leg(p[0], p[1], Long.MIN_VALUE, march(j.kind), false, false, false, AGAIN);
            }
            case ASSAULT -> {
                storm(bot, o, tick);
                return new Leg(way.x(o.at) + Math.cos(a) * spread, way.z(o.at) + Math.sin(a) * spread, Long.MIN_VALUE, AiSoldier.RUN, false, false, false, AiCommander.LEG_AGAIN);
            }
            case BOUND -> {
                int[] half = halves(of, st);
                if (st.turn == 0 || st.set && over(of, half, st, tick, BOUND_FOR)) {
                    st.turn++;
                    st.turnAt = tick;
                    st.set = false;
                    st.moving = st.turn == 1 ? 1 : 1 - st.moving;
                }
                // the half going sets off once the other is in its cover (or has had SET_FOR to get there); the bound
                // then ends BOUND past the foremost of the half covering (of the squad, if none of that half is left)
                if (!st.set && (set(of, half, st) || tick - st.turnAt >= SET_FOR)) {
                    st.set = true;
                    st.turnAt = tick;
                    double front = -1, all = 0;
                    for (int i = 0; i < of.size(); i++) {
                        double s = along(way, of.get(i).mob.getX(), of.get(i).mob.getZ());
                        all = Math.max(all, s);
                        if (half[i] != st.moving) front = Math.max(front, s);
                    }
                    st.to = Math.min(way.length(), (front < 0 ? all : front) + BOUND);
                }
                if (half[me] != st.moving) return cover(bot, level, o, of, st, AiSoldier.RUN, false);
                if (st.set) return goTo(bot, level, o, of, st, way, st.to, a, spread, AiSoldier.RUN, false);
                // waiting for the cover: where it is, in the cover it came to if it is in one
                return cover(bot, level, o, of, st, AiSoldier.RUN, false);
            }
            case CREEP -> {
                int n = rushing(of.size());
                if (st.turn == 0 || over(of, null, st, tick, RUSH_FOR)) {
                    st.moving = st.turn == 0 ? 0 : (st.moving + n) % of.size();
                    st.turn++;
                    st.turnAt = tick;
                }
                boolean rushes = (me - st.moving + of.size()) % of.size() < n;
                if (rushes) {
                    double to = o.coverSince == st.since && o.coverTurn == st.turn ? o.coverTo : along(way, bot.mob.getX(), bot.mob.getZ()) + RUSH;
                    return goTo(bot, level, o, of, st, way, Math.min(way.length(), to), a, spread, AiSoldier.CREEP, true);
                }
                return cover(bot, level, o, of, st, AiSoldier.CREEP, true);
            }
        }
        return null;
    }

    /**
     * a bound's halves as they were given at the mode's start, each kept to its own (the staff's squads are made again
     * as they move, their order with them); one new to the squad to the smaller half; a half left empty, all halved
     * again
     */
    private static int[] halves(List<AiBot> of, State st) {
        int[] base = halves(roles(of)), h = new int[of.size()], n = new int[2];
        for (int i = 0; i < of.size(); i++) {
            AiCommander.Order o = of.get(i).order;
            if (o != null && o.halfSince == st.since) n[h[i] = o.half]++;
            else h[i] = -1;
        }
        // the mode's start: by their roles; one new later: to the smaller half
        boolean first = n[0] + n[1] == 0;
        for (int i = 0; i < of.size(); i++) {
            if (h[i] >= 0) continue;
            h[i] = first || n[0] == n[1] ? base[i] : n[0] < n[1] ? 0 : 1;
            n[h[i]]++;
            AiCommander.Order o = of.get(i).order;
            if (o != null) {
                o.half = h[i];
                o.halfSince = st.since;
            }
        }
        // a half left empty (those in it gone from the squad): halved again by their roles, else the other half
        // would go on its own with no one covering it, and the empty one's bounds be over at once
        if (of.size() >= 2 && (n[0] == 0 || n[1] == 0)) {
            for (int i = 0; i < of.size(); i++) {
                h[i] = base[i];
                AiCommander.Order o = of.get(i).order;
                if (o != null) {
                    o.half = h[i];
                    o.halfSince = st.since;
                }
            }
        }
        return h;
    }

    private static List<AiRole> roles(List<AiBot> of) {
        List<AiRole> r = new ArrayList<>(of.size());
        for (AiBot b : of) r.add(AiRole.of(b));
        return r;
    }

    /**
     * the bound (rush) under way is over: all of those going are at their cover (given them for it), or it took too
     * long. half null: creeping, those rushing being the turn's from st.moving.
     */
    private static boolean over(List<AiBot> of, int @Nullable [] half, State st, long tick, int most) {
        if (tick - st.turnAt >= most) return true;
        int n = rushing(of.size());
        for (int i = 0; i < of.size(); i++) {
            boolean going = half != null ? half[i] == st.moving : (i - st.moving + of.size()) % of.size() < n;
            if (!going || !steps(of.get(i))) continue;
            AiCommander.Order o = of.get(i).order;
            if (o == null || o.coverSince != st.since || o.coverTurn != st.turn || !atCover(of.get(i), o)) return false;
        }
        return true;
    }

    /** bounds: all of the half covering are in their cover (found for them in this mode) */
    private static boolean set(List<AiBot> of, int[] half, State st) {
        for (int i = 0; i < of.size(); i++) {
            if (half[i] == st.moving || !steps(of.get(i))) continue;
            AiCommander.Order o = of.get(i).order;
            if (o == null || o.coverSince != st.since || !atCover(of.get(i), o)) return false;
        }
        return true;
    }

    /**
     * it goes by the squad's bounds now: awake, knowing of no foe, on the way (one fighting, asleep or past the way's
     * end is not waited for)
     */
    private static boolean steps(AiBot b) {
        if (!b.active || b.order == null || b.order.route == null) return false;
        // in a fight it stands, unless it goes round for its pair (C4: AiSoldier goes on by its order)
        TargetMemory.Contact c = b.memory.focus(b.mob.getX(), b.mob.getZ());
        return c == null || AiCommander.goesRound(b, c, b.mob.level().getGameTime());
    }

    /** on its cover cell (or at its place in the open) */
    private static boolean inPlace(AiBot b, AiCommander.Order o) {
        return Math.hypot(o.coverX - b.mob.getX(), o.coverZ - b.mob.getZ()) <= (o.coverCell == Long.MIN_VALUE ? AT_PLACE : ON_CELL);
    }

    /** come up to its cover (or place): a bound or rush counted done */
    static boolean atCover(AiBot b, AiCommander.Order o) {
        return Math.hypot(o.coverX - b.mob.getX(), o.coverZ - b.mob.getZ()) <= (o.coverCell == Long.MIN_VALUE ? AT_PLACE : AT_COVER);
    }

    /** one going: to cover by where the bound ends (s along the way), its end itself if that is the way's (the place) */
    private static Leg goTo(AiBot bot, ServerLevel level, AiCommander.Order o, List<AiBot> of, State st, HqGrid.Route way, double s, double a, double spread,
            double speed, boolean low) {
        boolean fresh = o.coverSince != st.since || o.coverTurn != st.turn;
        if (fresh) {
            o.coverSince = st.since;
            o.coverTurn = st.turn;
            o.coverTo = s;
            double[] p = at(way, s);
            if (s >= way.length() - 1) place(o, p[0] + Math.cos(a) * spread, p[1] + Math.sin(a) * spread);
            else {
                // no cover round the end: a little nearer, then a little farther (not past the way's end); none
                // anywhere, the end itself
                for (double t : new double[] {s, s - TRY, s + TRY}) {
                    if (t >= way.length() - 1) continue;
                    double[] q = at(way, t);
                    pick(bot, level, o, of, st, q[0], q[1]);
                    if (o.covered) break;
                }
                if (!o.covered) place(o, p[0], p[1]);
            }
        }
        return new Leg(o.coverX, o.coverZ, o.coverCell, speed, false, low, fresh, AiCommander.LEG_AGAIN);
    }

    /**
     * one covering: to cover near where it is (the one it went to on its last bound kept, unless it is still far off:
     * its bound ran out of time short of it), then in it facing the enemy
     */
    private static Leg cover(AiBot bot, ServerLevel level, AiCommander.Order o, List<AiBot> of, State st, double speed, boolean low) {
        boolean fresh = o.coverSince != st.since || Math.hypot(o.coverX - bot.mob.getX(), o.coverZ - bot.mob.getZ()) > COVER_R + 2;
        if (fresh) {
            o.coverSince = st.since;
            o.coverTurn = -1;
            pick(bot, level, o, of, st, bot.mob.getX(), bot.mob.getZ());
        }
        // in it (on the cover cell itself: a step aside is in sight): down on a knee (its head behind low cover),
        // facing the enemy
        if (inPlace(bot, o)) return new Leg(o.coverX, o.coverZ, o.coverCell, speed, true, true, false, AiCommander.LEG_AGAIN);
        return new Leg(o.coverX, o.coverZ, o.coverCell, speed, false, low, fresh, AiCommander.LEG_AGAIN);
    }

    private static void place(AiCommander.Order o, double x, double z) {
        o.coverX = x;
        o.coverZ = z;
        o.coverCell = Long.MIN_VALUE;
        o.covered = false;
    }

    /**
     * cover from the nearest enemy round x, z: x, z itself if the staff's map has it out of the enemy's sight (behind a
     * hill, in a wood); else a cell that hides from the middle of its circle, its edge weighed too (none of the
     * squad's own, none another holds); none found: x, z itself, in the open
     */
    private static void pick(AiBot bot, ServerLevel level, AiCommander.Order o, List<AiBot> of, State st, double x, double z) {
        place(o, x, z);
        int bx = Mth.floor(x), bz = Mth.floor(z), fx = Mth.floor(st.foeX), fz = Mth.floor(st.foeZ);
        if (!st.foe || !level.hasChunk(bx >> 4, bz >> 4)) return;
        HqGrid grid = AiHqMap.of(level, AiSquads.team(bot));
        int gi = grid == null ? -1 : grid.cellX(x), gk = grid == null ? -1 : grid.cellZ(z);
        if (gi >= 0 && gk >= 0 && grid.known[HqGrid.index(gi, gk)] && grid.seen(gi, gk) <= HIDDEN) {
            o.covered = true;
            return;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
        int fy = level.hasChunk(fx >> 4, fz >> 4) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, fx, fz) : y;
        List<Vec3> eyes = new ArrayList<>();
        eyes.add(new Vec3(st.foeX, fy + EYE, st.foeZ));
        for (int k = 0; k < EDGE; k++) {
            double a = Math.PI * 2 * k / EDGE;
            eyes.add(new Vec3(st.foeX + Math.cos(a) * st.foeR, fy + EYE, st.foeZ + Math.sin(a) * st.foeR));
        }
        CoverQuery.Params p = new CoverQuery.Params();
        p.othersSoft = true;
        p.radius = COVER_R;
        p.minFromThreat = 5;
        p.maxFromThreat = Double.MAX_VALUE;
        p.fightAt = Math.hypot(st.foeX - x, st.foeZ - z);
        var held = AiBots.heldByOthers(bot);
        java.util.function.LongPredicate taken = c -> {
            if (held.test(c) || c == o.badCell) return true;
            for (AiBot b : of) if (b != bot && b.order != null && b.order.coverCell == c) return true;
            return false;
        };
        List<CoverQuery.Spot> found = CoverQuery.find(TacticalMap.of(level), AiBots.voxels(level), new Vec3(x, y, z), eyes, p, taken, 1);
        if (found.isEmpty()) return;
        long c = found.get(0).cell();
        o.coverCell = c;
        o.coverX = BlockPos.getX(c) + 0.5;
        o.coverZ = BlockPos.getZ(c) + 0.5;
        o.covered = true;
    }

    /** none of the squad on its task knows of a foe (its bounds go by the staff, not by a fight) */
    static boolean quiet(AiCommander.Job j) {
        for (AiBot b : squad(j)) if (b.memory.focus(b.mob.getX(), b.mob.getZ()) != null) return false;
        return true;
    }

    /**
     * the stands: the squad bounding out of a fight, one of the half covering at least is at its place, standing
     * (facing the enemy): 1; in cover there (one was found): 2; else 0
     */
    /**
     * the stands: why the half covering is not at its place while the squad bounds out of a fight, one line a soldier
     * of it: the squad's task, why not (no order, no place given this bound, how far off it, moving there), and its
     * own mode and why
     */
    static void offPlace(AiCommander.Job j, java.util.function.Consumer<String> out) {
        State st = j.move;
        List<AiBot> of = squad(j);
        int[] half = halves(of, st);
        for (int i = 0; i < of.size(); i++) {
            if (half[i] == st.moving) continue;
            AiBot b = of.get(i);
            AiCommander.Order o = b.order;
            String why;
            if (o == null) why = "no order";
            else if (o.coverSince != st.since) {
                double d = Math.hypot(o.x - b.mob.getX(), o.z - b.mob.getZ());
                boolean in = j.place() ? d <= o.r * 2 : d <= o.r;
                why = "no place this bound, " + (in ? "in its task's area" : o.route == null ? "past the way's end" + (o.way != null ? " (a way)" : "")
                        + (o.walked == null ? " (none walked)" : "") + (o.noRoute ? " (no route)" : "") + (o.noTail ? " (no tail)" : "") + (j.tail == null ? " (tail none)" : "") : "on the way");
            }
            else if (!atCover(b, o)) {
                double d = Math.hypot(o.coverX - b.mob.getX(), o.coverZ - b.mob.getZ());
                why = d <= 16 ? "off its place 16 at most" : d <= 32 ? "off its place 16 to 32" : "off its place over 32";
            } else if (b.mob.getDeltaMovement().horizontalDistance() >= 0.05) why = "at its place, moving";
            else why = "at its place";
            String mode = b.brain instanceof AiSoldier s ? s.mode() + "/" + s.why : "-";
            out.accept(j.kind + " | " + why + " | " + mode);
        }
    }

    /**
     * the stands' C3 trace (-PaiCheckWarDump): a bound missed, one line a soldier of the squad: its half, awake, going by
     * the bounds (steps), its place for this mode and how far off, its speed, its last step by the mode (how long ago, a
     * hold), its way (on it, its tail, straight legs), its walk, its mode, the nearest of the half going
     */
    static void trace(AiCommander.Job j, long now, java.util.function.ToIntFunction<AiBot> id, java.util.function.Consumer<String> out) {
        State st = j.move;
        List<AiBot> of = squad(j);
        int[] half = halves(of, st);
        for (int i = 0; i < of.size(); i++) {
            AiBot b = of.get(i);
            AiCommander.Order o = b.order;
            double near = Double.MAX_VALUE;
            for (int k = 0; k < of.size(); k++)
                if (k != i && half[k] == st.moving) near = Math.min(near, of.get(k).mob.position().distanceTo(b.mob.position()));
            String way = o == null ? "-" : o.route == null ? (o.noRoute ? "straight(noRoute)" : o.noTail ? "straight(noTail)" : o.walked != null ? "straight(walked)" : "straight")
                    : (o.onTail ? "tail " : "way ") + o.at + "/" + o.route.size();
            out.accept(String.format(java.util.Locale.ROOT, "%d %s %d %s set%d turnAge%d id%d act%d steps%d since%d cell%d covered%d d%.2f in%d at%d v%.3f moveAgo%s hold%d %s | %s | %s near%.1f",
                    System.identityHashCode(j), j.kind, st.turn, half[i] == st.moving ? "G" : "c", st.set ? 1 : 0, now - st.turnAt, id.applyAsInt(b), b.active ? 1 : 0,
                    steps(b) ? 1 : 0, o != null && o.coverSince == st.since ? 1 : 0, o != null && o.coverCell != Long.MIN_VALUE ? 1 : 0, o != null && o.covered ? 1 : 0,
                    o == null ? -1 : Math.hypot(o.coverX - b.mob.getX(), o.coverZ - b.mob.getZ()), o != null && inPlace(b, o) ? 1 : 0, o != null && atCover(b, o) ? 1 : 0,
                    b.mob.getDeltaMovement().horizontalDistance(), o == null || o.moveAt == Long.MIN_VALUE ? "-" : Long.toString(now - o.moveAt), o != null && o.moveHold ? 1 : 0,
                    way, b.mover.state(), b.brain instanceof AiSoldier s ? s.mode() + "/" + s.why : "-", near == Double.MAX_VALUE ? -1 : near));
        }
    }

    static int covered(AiCommander.Job j) {
        State st = j.move;
        if (st.mode != Mode.BOUND || st.turn == 0 || !quiet(j)) return 0;
        List<AiBot> of = squad(j);
        int[] half = halves(of, st), best = {0};
        for (int i = 0; i < of.size(); i++) {
            AiBot b = of.get(i);
            AiCommander.Order o = b.order;
            if (half[i] == st.moving || o == null || o.coverSince != st.since || !atCover(b, o) || b.mob.getDeltaMovement().horizontalDistance() >= 0.05) continue;
            best[0] = Math.max(best[0], o.covered ? 2 : 1);
        }
        return best[0];
    }
}
