package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ReaimTimes;
import acup.gunfeel.GameMath;
import acup.gunfeel.PosLong;
import acup.gunfeel.Vec3d;
import acup.gunfeel.host.WorldView;

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
public abstract class StaffMoveBase {

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

    protected StaffMoveBase() {
    }

    static boolean movesOn() {
        return on && ReaimTimes.flag(KEY, true);
    }

    /** a soldier's step: go to x, z (the cover cell, else the ground there) at speed, or hold where it is facing the enemy; low: crouching; again: its leg set again so soon at the latest, ticks */
    record Leg(double x, double z, long cell, double speed, boolean hold, boolean low, boolean fresh, int again) {
    }

    /** the mode for a squad foe blocks from the nearest enemy, in its sight or not, advancing or not, left blocks from its area */
    static Mode movementMode(double foe, boolean seen, boolean advance, double left) {
        if (advance && left <= ASSAULT) return Mode.ASSAULT;
        if (seen && foe < CREEP) return Mode.CREEP;
        if (seen || foe <= MARCH) return Mode.BOUND;
        return Mode.MARCH;
    }

    /** a bound's halves, per one of the squad (in its order): 0 covers first (the base of fire and marksman, then the first), 1 goes first */
    static int[] roleHalves(List<AiRole> roles) {
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
    static int rushCount(int n) {
        return Math.max(1, Math.min(2, n / 2));
    }

    /** how far along the way (from where it began) the place on it nearest x, z is */
    static double distanceAlong(HqGrid.Route r, double x, double z) {
        double px = r.fromX(), pz = r.fromZ(), s = 0, best = 0, bestD = Double.MAX_VALUE;
        for (int j = 0; j < r.size(); j++) {
            double qx = r.x(j), qz = r.z(j), lx = qx - px, lz = qz - pz, l2 = lx * lx + lz * lz, l = Math.sqrt(l2);
            double t = l2 == 0 ? 1 : GameMath.clamp(((x - px) * lx + (z - pz) * lz) / l2, 0, 1);
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
    static double[] pointAt(HqGrid.Route r, double s) {
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
    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> List<B> taskSquad(AiCommanderBase.Staff<B, J, O> staff, J j) {
        List<B> out = new ArrayList<>();
        for (B b : j.members) if (b.body.alive() && staff.order(b) != null && staff.order(b).job == j) out.add(b);
        return out;
    }

    /** the squad's mode, looked at again every AGAIN ticks by the first of it to step */
    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> void refreshMode(AiCommanderBase.Staff<B, J, O> staff, Object level, O o, long tick) {
        J j = o.job;
        if (j == null) return;
        State st = j.move;
        if (tick < st.next) return;
        st.next = tick + AGAIN;
        List<B> of = taskSquad(staff, j);
        if (of.isEmpty()) return;
        double mx = 0, mz = 0;
        for (B b : of) {
            mx += b.body.x() / of.size();
            mz += b.body.z() / of.size();
        }
        String team = AiSquadsBase.teamOf(of.get(0));
        double foe = Double.MAX_VALUE;
        st.foe = false;
        for (AiIntelBase.Group g : AiCommanderBase.enemyGroups(staff, level, team)) {
            double d = Math.hypot(g.x() - mx, g.z() - mz) - g.r();
            if (d < foe) {
                foe = d;
                st.foeX = g.x();
                st.foeZ = g.z();
                st.foeR = g.r();
                st.foe = true;
            }
        }
        HqGrid grid = staff.hq(level, team);
        int i = grid == null ? -1 : grid.cellX(mx), k = grid == null ? -1 : grid.cellZ(mz);
        boolean seen = i >= 0 && k >= 0 && grid.seen(i, k) > 0.5;
        // falling back: at a run, never creeping
        Mode m = j.kind == StaffPlan.Kind.BACK ? Mode.MARCH : movementMode(foe, seen, j.kind == StaffPlan.Kind.ADVANCE, Math.hypot(o.x - mx, o.z - mz) - o.r * 0.5);
        if (m == st.mode || st.mode != null && m != Mode.ASSAULT && tick - st.since < KEEP) return;
        st.mode = m;
        st.since = tick;
        st.turn = 0;
        st.moving = 0;
        st.to = -1;
        st.set = false;
    }

    /** its way of going begun again at its next look (a river crossed: AiCommander.crossing) */
    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> void restartMode(AiCommanderBase.Staff<B, J, O> staff, J j) {
        j.move.mode = null;
        j.move.next = Long.MIN_VALUE;
    }

    /** its pace on a straight leg (no way): by the squad's mode */
    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> double orderSpeed(AiCommanderBase.Staff<B, J, O> staff, O o) {
        Mode m = o.job == null ? null : o.job.move.mode;
        return m == Mode.MARCH ? marchSpeed(staff, o.job.kind) : m == Mode.ASSAULT ? staff.speed(Pace.RUN) : m == Mode.CREEP ? staff.speed(Pace.CREEP) : staff.speed(Pace.WALK);
    }

    /** a march's pace: a jog to the fight; falling back, a run */
    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> double marchSpeed(AiCommanderBase.Staff<B, J, O> staff, StaffPlan.Kind kind) {
        return kind == StaffPlan.Kind.BACK ? staff.speed(Pace.RUN) : staff.speed(Pace.MARCH);
    }

    /** holding its cover: facing the nearest enemy */
    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> void faceEnemy(AiCommanderBase.Staff<B, J, O> staff, B bot, O o) {
        State st = o.job == null ? null : o.job.move;
        if (st != null && st.foe && bot.body.alive()) staff.aim(bot, new Vec3d(st.foeX, eyeY(bot), st.foeZ));
    }

    /** an assault: it does not hold back in the fight after it */
    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> void stormOn(AiCommanderBase.Staff<B, J, O> staff, B bot, O o, long tick) {
        if (o.job != null && o.job.move.mode == Mode.ASSAULT) bot.confidence.storm = tick + STORM;
    }

    /** on a leg of its own (straight, or catching up on the way): the mode's assault, and crouched while creeping */
    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> void applyPace(AiCommanderBase.Staff<B, J, O> staff, B bot, O o, long tick) {
        stormOn(staff, bot, o, tick);
        if (o.job != null && o.job.move.mode == Mode.CREEP && bot.soldier()) staff.crouch(bot, true);
    }

    /**
     * a soldier's step along the way by its squad's mode; null: none set (as C2). stuck: its last leg of the mode could
     * not be walked (the cover cell out of reach): that cell is not picked again, it stays where it is for this turn
     */
    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> @Nullable Leg nextLeg(AiCommanderBase.Staff<B, J, O> staff, B bot, Object level, O o, HqGrid.Route way, long tick, boolean stuck) {
        J j = o.job;
        if (j == null || j.move.mode == null) return null;
        if (stuck) {
            if (o.coverCell != Long.MIN_VALUE) o.badCell = o.coverCell;
            placeAt(staff, o, bot.body.x(), bot.body.z());
        }
        State st = j.move;
        List<B> of = taskSquad(staff, j);
        int me = of.indexOf(bot);
        if (me < 0) return null;
        double spread = o.of > 1 ? 3 : 0, a = Math.PI * 2 * o.slot / Math.max(1, o.of);
        switch (st.mode) {
            case MARCH -> {
                // the first to the next point of the way; each after it a little behind the one ahead, on the way (one
                // past it waits where it is)
                if (me == 0) return new Leg(way.x(o.at), way.z(o.at), Long.MIN_VALUE, marchSpeed(staff, j.kind), false, false, false, AiCommanderBase.LEG_AGAIN);
                B ahead = of.get(me - 1);
                double[] p = pointAt(way, Math.max(distanceAlong(way, bot.body.x(), bot.body.z()), distanceAlong(way, ahead.body.x(), ahead.body.z()) - FILE));
                return new Leg(p[0], p[1], Long.MIN_VALUE, marchSpeed(staff, j.kind), false, false, false, AGAIN);
            }
            case ASSAULT -> {
                stormOn(staff, bot, o, tick);
                return new Leg(way.x(o.at) + Math.cos(a) * spread, way.z(o.at) + Math.sin(a) * spread, Long.MIN_VALUE, staff.speed(Pace.RUN), false, false, false, AiCommanderBase.LEG_AGAIN);
            }
            case BOUND -> {
                int[] half = stableHalves(staff, of, st);
                if (st.turn == 0 || st.set && turnOver(staff, of, half, st, tick, BOUND_FOR)) {
                    st.turn++;
                    st.turnAt = tick;
                    st.set = false;
                    st.moving = st.turn == 1 ? 1 : 1 - st.moving;
                }
                // the half going sets off once the other is in its cover (or has had SET_FOR to get there); the bound
                // then ends BOUND past the foremost of the half covering (of the squad, if none of that half is left)
                if (!st.set && (coverSet(staff, of, half, st) || tick - st.turnAt >= SET_FOR)) {
                    st.set = true;
                    st.turnAt = tick;
                    double front = -1, all = 0;
                    for (int i = 0; i < of.size(); i++) {
                        double s = distanceAlong(way, of.get(i).body.x(), of.get(i).body.z());
                        all = Math.max(all, s);
                        if (half[i] != st.moving) front = Math.max(front, s);
                    }
                    st.to = Math.min(way.length(), (front < 0 ? all : front) + BOUND);
                }
                if (half[me] != st.moving) return holdCover(staff, bot, level, o, of, st, staff.speed(Pace.RUN), false);
                if (st.set) return goCover(staff, bot, level, o, of, st, way, st.to, a, spread, staff.speed(Pace.RUN), false);
                // waiting for the cover: where it is, in the cover it came to if it is in one
                return holdCover(staff, bot, level, o, of, st, staff.speed(Pace.RUN), false);
            }
            case CREEP -> {
                int n = rushCount(of.size());
                if (st.turn == 0 || turnOver(staff, of, null, st, tick, RUSH_FOR)) {
                    st.moving = st.turn == 0 ? 0 : (st.moving + n) % of.size();
                    st.turn++;
                    st.turnAt = tick;
                }
                boolean rushes = (me - st.moving + of.size()) % of.size() < n;
                if (rushes) {
                    double to = o.coverSince == st.since && o.coverTurn == st.turn ? o.coverTo : distanceAlong(way, bot.body.x(), bot.body.z()) + RUSH;
                    return goCover(staff, bot, level, o, of, st, way, Math.min(way.length(), to), a, spread, staff.speed(Pace.CREEP), true);
                }
                return holdCover(staff, bot, level, o, of, st, staff.speed(Pace.CREEP), true);
            }
        }
        return null;
    }

    /**
     * a bound's halves as they were given at the mode's start, each kept to its own (the staff's squads are made again
     * as they move, their order with them); one new to the squad to the smaller half; a half left empty, all halved
     * again
     */
    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> int[] stableHalves(AiCommanderBase.Staff<B, J, O> staff, List<B> of, State st) {
        int[] base = roleHalves(squadRoles(staff, of)), h = new int[of.size()], n = new int[2];
        for (int i = 0; i < of.size(); i++) {
            O o = staff.order(of.get(i));
            if (o != null && o.halfSince == st.since) n[h[i] = o.half]++;
            else h[i] = -1;
        }
        // the mode's start: by their roles; one new later: to the smaller half
        boolean first = n[0] + n[1] == 0;
        for (int i = 0; i < of.size(); i++) {
            if (h[i] >= 0) continue;
            h[i] = first || n[0] == n[1] ? base[i] : n[0] < n[1] ? 0 : 1;
            n[h[i]]++;
            O o = staff.order(of.get(i));
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
                O o = staff.order(of.get(i));
                if (o != null) {
                    o.half = h[i];
                    o.halfSince = st.since;
                }
            }
        }
        return h;
    }

    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> List<AiRole> squadRoles(AiCommanderBase.Staff<B, J, O> staff, List<B> of) {
        List<AiRole> r = new ArrayList<>(of.size());
        for (B b : of) r.add(b.role());
        return r;
    }

    /**
     * the bound (rush) under way is over: all of those going are at their cover (given them for it), or it took too
     * long. half null: creeping, those rushing being the turn's from st.moving.
     */
    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> boolean turnOver(AiCommanderBase.Staff<B, J, O> staff, List<B> of, int @Nullable [] half, State st, long tick, int most) {
        if (tick - st.turnAt >= most) return true;
        int n = rushCount(of.size());
        for (int i = 0; i < of.size(); i++) {
            boolean going = half != null ? half[i] == st.moving : (i - st.moving + of.size()) % of.size() < n;
            if (!going || !takesSteps(staff, of.get(i))) continue;
            O o = staff.order(of.get(i));
            if (o == null || o.coverSince != st.since || o.coverTurn != st.turn || !reachedCover(staff, of.get(i), o)) return false;
        }
        return true;
    }

    /** bounds: all of the half covering are in their cover (found for them in this mode) */
    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> boolean coverSet(AiCommanderBase.Staff<B, J, O> staff, List<B> of, int[] half, State st) {
        for (int i = 0; i < of.size(); i++) {
            if (half[i] == st.moving || !takesSteps(staff, of.get(i))) continue;
            O o = staff.order(of.get(i));
            if (o == null || o.coverSince != st.since || !reachedCover(staff, of.get(i), o)) return false;
        }
        return true;
    }

    /**
     * it goes by the squad's bounds now: awake, knowing of no foe, on the way (one fighting, asleep or past the way's
     * end is not waited for)
     */
    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> boolean takesSteps(AiCommanderBase.Staff<B, J, O> staff, B b) {
        if (!b.active || staff.order(b) == null || staff.order(b).route == null) return false;
        // in a fight it stands, unless it goes round for its pair (C4: AiSoldier goes on by its order)
        TargetMemory.Contact c = b.memory.focus(b.body.x(), b.body.z());
        return c == null || AiCommanderBase.goesAround(staff, b, c, staff.world(b.body.level()).gameTime());
    }

    /** on its cover cell (or at its place in the open) */
    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> boolean atPlace(AiCommanderBase.Staff<B, J, O> staff, B b, O o) {
        return Math.hypot(o.coverX - b.body.x(), o.coverZ - b.body.z()) <= (o.coverCell == Long.MIN_VALUE ? AT_PLACE : ON_CELL);
    }

    /** come up to its cover (or place): a bound or rush counted done */
    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> boolean reachedCover(AiCommanderBase.Staff<B, J, O> staff, B b, O o) {
        return Math.hypot(o.coverX - b.body.x(), o.coverZ - b.body.z()) <= (o.coverCell == Long.MIN_VALUE ? AT_PLACE : AT_COVER);
    }

    /** one going: to cover by where the bound ends (s along the way), its end itself if that is the way's (the place) */
    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> Leg goCover(AiCommanderBase.Staff<B, J, O> staff, B bot, Object level, O o, List<B> of, State st, HqGrid.Route way, double s, double a, double spread,
            double speed, boolean low) {
        boolean fresh = o.coverSince != st.since || o.coverTurn != st.turn;
        if (fresh) {
            o.coverSince = st.since;
            o.coverTurn = st.turn;
            o.coverTo = s;
            double[] p = pointAt(way, s);
            if (s >= way.length() - 1) placeAt(staff, o, p[0] + Math.cos(a) * spread, p[1] + Math.sin(a) * spread);
            else {
                // no cover round the end: a little nearer, then a little farther (not past the way's end); none
                // anywhere, the end itself
                for (double t : new double[] {s, s - TRY, s + TRY}) {
                    if (t >= way.length() - 1) continue;
                    double[] q = pointAt(way, t);
                    pickCover(staff, bot, level, o, of, st, q[0], q[1]);
                    if (o.covered) break;
                }
                if (!o.covered) placeAt(staff, o, p[0], p[1]);
            }
        }
        return new Leg(o.coverX, o.coverZ, o.coverCell, speed, false, low, fresh, AiCommanderBase.LEG_AGAIN);
    }

    /**
     * one covering: to cover near where it is (the one it went to on its last bound kept, unless it is still far off:
     * its bound ran out of time short of it), then in it facing the enemy
     */
    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> Leg holdCover(AiCommanderBase.Staff<B, J, O> staff, B bot, Object level, O o, List<B> of, State st, double speed, boolean low) {
        boolean fresh = o.coverSince != st.since || Math.hypot(o.coverX - bot.body.x(), o.coverZ - bot.body.z()) > COVER_R + 2;
        if (fresh) {
            o.coverSince = st.since;
            o.coverTurn = -1;
            pickCover(staff, bot, level, o, of, st, bot.body.x(), bot.body.z());
        }
        // in it (on the cover cell itself: a step aside is in sight): down on a knee (its head behind low cover),
        // facing the enemy
        if (atPlace(staff, bot, o)) return new Leg(o.coverX, o.coverZ, o.coverCell, speed, true, true, false, AiCommanderBase.LEG_AGAIN);
        return new Leg(o.coverX, o.coverZ, o.coverCell, speed, false, low, fresh, AiCommanderBase.LEG_AGAIN);
    }

    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> void placeAt(AiCommanderBase.Staff<B, J, O> staff, O o, double x, double z) {
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
    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> void pickCover(AiCommanderBase.Staff<B, J, O> staff, B bot, Object level, O o, List<B> of, State st, double x, double z) {
        placeAt(staff, o, x, z);
        int bx = GameMath.floor(x), bz = GameMath.floor(z), fx = GameMath.floor(st.foeX), fz = GameMath.floor(st.foeZ);
        if (!st.foe || !staff.world(level).hasChunk(bx >> 4, bz >> 4)) return;
        HqGrid grid = staff.hq(level, AiSquadsBase.teamOf(bot));
        int gi = grid == null ? -1 : grid.cellX(x), gk = grid == null ? -1 : grid.cellZ(z);
        if (gi >= 0 && gk >= 0 && grid.known[HqGrid.index(gi, gk)] && grid.seen(gi, gk) <= HIDDEN) {
            o.covered = true;
            return;
        }
        int y = staff.world(level).height(bx, bz, WorldView.HEIGHT_MOTION_BLOCKING_NO_LEAVES);
        int fy = staff.world(level).hasChunk(fx >> 4, fz >> 4) ? staff.world(level).height(fx, fz, WorldView.HEIGHT_MOTION_BLOCKING_NO_LEAVES) : y;
        List<Vec3d> eyes = new ArrayList<>();
        eyes.add(new Vec3d(st.foeX, fy + EYE, st.foeZ));
        for (int k = 0; k < EDGE; k++) {
            double a = Math.PI * 2 * k / EDGE;
            eyes.add(new Vec3d(st.foeX + Math.cos(a) * st.foeR, fy + EYE, st.foeZ + Math.sin(a) * st.foeR));
        }
        CoverQuery.Params p = new CoverQuery.Params();
        p.othersSoft = true;
        p.radius = COVER_R;
        p.minFromThreat = 5;
        p.maxFromThreat = Double.MAX_VALUE;
        p.fightAt = Math.hypot(st.foeX - x, st.foeZ - z);
        var held = staff.heldByOthers(bot);
        java.util.function.LongPredicate taken = c -> {
            if (held.test(c) || c == o.badCell) return true;
            for (B b : of) if (b != bot && staff.order(b) != null && staff.order(b).coverCell == c) return true;
            return false;
        };
        List<CoverQuery.Spot> found = CoverQuery.find(staff.map(level), staff.voxels(level), new Vec3d(x, y, z), eyes, p, taken, 1);
        if (found.isEmpty()) return;
        long c = found.get(0).cell();
        o.coverCell = c;
        o.coverX = PosLong.blockX(c) + 0.5;
        o.coverZ = PosLong.blockZ(c) + 0.5;
        o.covered = true;
    }

    /** none of the squad on its task knows of a foe (its bounds go by the staff, not by a fight) */
    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> boolean taskQuiet(AiCommanderBase.Staff<B, J, O> staff, J j) {
        for (B b : taskSquad(staff, j)) if (b.memory.focus(b.body.x(), b.body.z()) != null) return false;
        return true;
    }

    /** The stands: a covering half at rest, 2 in cover, 1 in the open, else 0. */
    static <B extends AiBotBase, J extends AiCommanderBase.Job<B, J>, O extends AiCommanderBase.Order<B, J>> int covering(AiCommanderBase.Staff<B, J, O> staff, J j) {
        State st = j.move;
        if (st.mode != Mode.BOUND || st.turn == 0 || !taskQuiet(staff, j)) return 0;
        List<B> of = taskSquad(staff, j);
        int[] half = stableHalves(staff, of, st), best = {0};
        for (int i = 0; i < of.size(); i++) {
            B b = of.get(i);
            O o = staff.order(b);
            if (half[i] == st.moving || o == null || o.coverSince != st.since || !reachedCover(staff, b, o) || staff.movingHeld(b)) continue;
            best[0] = Math.max(best[0], o.covered ? 2 : 1);
        }
        return best[0];
    }

    /** The version's four soldier paces. */
    public enum Pace {
        RUN, MARCH, WALK, CREEP
    }

    private static double eyeY(AiBotBase bot) {
        double[] eye = new double[3];
        bot.body.eye(eye);
        return eye[1];
    }

    enum Mode {
        MARCH, BOUND, CREEP, ASSAULT
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
}
