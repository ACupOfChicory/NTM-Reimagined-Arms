package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.GameMath;
import acup.gunfeel.LevelMap;
import acup.gunfeel.PosLong;
import acup.gunfeel.Vec3d;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.NtmGuns;
import acup.gunfeel.host.Shell;
import acup.gunfeel.host.WorldView;
import acup.gunfeel.host.Worlds;

/** Shared grenade flight, throw selection and per-level budgets. */
public abstract class AiGrenadesBase {
    protected AiGrenadesBase() {
    }

    @FunctionalInterface
    public interface Vec3Maker<T> {
        T make(double x, double y, double z);
    }

    /** The caller's vector, kept through flight and selection without copying it. */
    interface Vectors<T> extends Vec3Maker<T> {
        double x(T v);
        double y(T v);
        double z(T v);
        double distanceTo(T a, T b);
        double distanceToSqr(T a, T b);
    }

    @FunctionalInterface
    interface BurstObserver {
        void burst(VoxelRay.Source src, double x, double y, double z, double vx, double vy, double vz,
                int ticks, int armed, double bounce, int minY, double gravity, double drag, @Nullable List<double[]> path,
                double @Nullable [] land, @Nullable Vec3d answer, int input);
    }

    interface Staff {
        /** 26.2: AiBots.voxels(level). */
        VoxelRay.Source voxels(Object level);
        /** 26.2: AiGrenades.ownNear(level, src, mob, bx, by, bz, down), until G2. */
        boolean ownNear(Object level, VoxelRay.Source src, Body mob, double bx, double by, double bz, double down);
        /** Publishes a new vector, or the body's original position object for an expired timer. */
        default Object burstRef(Body body, Vec3d burst, boolean position) { return burst; }
        /** Optional comparison of live flight calls, before glue selects the landing point. */
        default @Nullable BurstObserver flightObserver() { return null; }
    }

    /** A live projectile and its last prediction. The vector token belongs to the host. */
    protected static final class Live implements Vec3Maker<Live> {
        public final Body e;
        public @Nullable Vec3d burst;
        public @Nullable Object burstRef;
        public long at = Long.MIN_VALUE;
        public final it.unimi.dsi.fastutil.ints.IntOpenHashSet known = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
        public @Nullable List<double[]> path;
        public @Nullable String start, parted;
        private final double[] motion = new double[3];
        private final Shell shell = new Shell();
        private double bx, by, bz;
        boolean position;

        public Live(Body e) { this.e = e; }
        @Override public Live make(double x, double y, double z) { bx = x; by = y; bz = z; return this; }
    }

    protected static final LevelMap<List<Live>> LEVELS = new LevelMap<>();
    static final int AGAIN = 4;
    protected static boolean trace;
    protected static int traced;
    protected static final List<String> partings = new ArrayList<>();
    protected static java.util.function.@Nullable Consumer<String> journal;

    static void event(String event) { if (journal != null) journal.accept(event); }

    static Live joined(Object level, Body body) {
        List<Live> list = LEVELS.computeIfAbsent(level, k -> new ArrayList<>());
        for (Live old : list) if (old.e == body) { if (journal != null) event("DUP " + list.indexOf(old)); return old; }
        Live live = new Live(body);
        list.add(live);
        if (journal != null) event("JOIN " + (list.size() - 1));
        return live;
    }

    public static boolean any(Object level) {
        List<Live> list = LEVELS.get(level);
        return list != null && !list.isEmpty();
    }

    static @Nullable Object predicted(Object level, Body body) {
        List<Live> list = LEVELS.get(level);
        if (list != null) for (Live live : list) if (live.e == body) return live.burstRef;
        return null;
    }

    static boolean sameBurst(Live live, Object burst) { return live.burstRef == burst; }

    static @Nullable Live oldest(Object level, @Nullable Body owner) {
        List<Live> list = LEVELS.get(level);
        Live best = null;
        if (list != null) for (Live g : list)
            if (g.e.owner() == owner && (best == null || g.e.age() > best.e.age())) best = g;
        return best;
    }

    static @Nullable String parted(Object level, @Nullable Body owner) {
        Live best = oldest(level, owner);
        return best == null || best.start == null ? null : best.start + "; " + (best.parted == null ? "kept to it" : best.parted);
    }

    static Object @Nullable [] bursting(Staff staff, Object level, @Nullable Body owner) {
        Live best = oldest(level, owner);
        return best == null ? null : new Object[] {staff.burstRef(best.e, new Vec3d(best.e.x(), best.e.y(), best.e.z()), true), best.burstRef};
    }

    static void tick(Staff staff, NtmGuns guns, Object level) {
        List<Live> list = LEVELS.get(level);
        if (list == null) return;
        for (int i = 0; i < list.size();) {
            if (list.get(i).e.removed()) { if (journal != null) event("REMOVE " + i); list.remove(i); }
            else i++;
        }
        if (list.isEmpty()) {
            LEVELS.remove(level);
            if (journal != null) event("EMPTY");
            return;
        }
        WorldView world = Worlds.of(level);
        long now = world.gameTime();
        if (trace) for (Live g : list) follow(staff, level, g);
        for (Live g : list) if (g.at == Long.MIN_VALUE || now - g.at >= (guns.liveKind(g.e) == NtmGuns.MK4 ? 1 : AGAIN)) {
            g.at = now;
            if (journal != null) event("AT " + list.indexOf(g) + " " + now);
            g.burst = burstOf(staff, guns, level, world, g);
            g.burstRef = g.burst == null ? null : staff.burstRef(g.e, g.burst, g.position);
            if (journal != null) event("BURST " + list.indexOf(g));
        }
    }

    static @Nullable Vec3d burstOf(Staff staff, NtmGuns guns, Object level, Live g) {
        return burstOf(staff, guns, level, Worlds.of(level), g);
    }

    private static @Nullable Vec3d burstOf(Staff staff, NtmGuns guns, Object level, WorldView world, Live g) {
        g.position = false;
        return flight(staff, guns, level, world, g) == null ? null : new Vec3d(g.bx, g.by, g.bz);
    }

    private static @Nullable Live position(Live g) {
        g.position = true;
        return g.make(g.e.x(), g.e.y(), g.e.z());
    }

    private static @Nullable Live flight(Staff staff, NtmGuns guns, Object level, WorldView world, Live g) {
        Body p = g.e;
        p.motion(g.motion);
        double vx = g.motion[0], vy = g.motion[1], vz = g.motion[2];
        int kind = guns.liveKind(p);
        if (kind == NtmGuns.BOUNCY) {
            int left = guns.grenadeMaxTimer(p) - p.age();
            if (left <= 0) return position(g);
            return flightBurst(staff, staff.voxels(level), p.x(), p.y(), p.z(), vx, vy, vz, left, -1, guns.grenadeBounce(p), world.minY(), GRAVITY, DRAG, null, null, g, 0);
        }
        if (kind == NtmGuns.MK4) {
            if (!guns.mk4Config(p, g.shell)) return null;
            double m = g.shell.velocity + guns.mk4Accel(p);
            int left = g.shell.expires > 0 ? Math.min(200, g.shell.expires - p.age()) : 200;
            if (left <= 0) return position(g);
            return flightBurst(staff, staff.voxels(level), p.x(), p.y(), p.z(), vx * m, vy * m, vz * m, left, 0, 0, world.minY(), g.shell.gravity * m, 1, null, null, g, 1);
        }
        if (kind != NtmGuns.UNIVERSAL) return null;
        int f = guns.grenadeFuze(p);
        int left = switch (f) {
            case NtmGuns.FUZE_S3 -> 60;
            case NtmGuns.FUZE_S7 -> 140;
            case NtmGuns.FUZE_S15 -> 300;
            case NtmGuns.FUZE_IMPACT -> 200;
            default -> -1;
        };
        if (left < 0) return null;
        if (f != NtmGuns.FUZE_IMPACT) left -= guns.grenadeTimer(p);
        if (left <= 0) return position(g);
        if (guns.grenadeGlue(p)) {
            double[] land = new double[3];
            Live b = flightBurst(staff, staff.voxels(level), p.x(), p.y(), p.z(), vx, vy, vz, left, -1, guns.grenadeBounce(p), world.minY(), GRAVITY, DRAG, null, land, g, 2);
            return Double.isNaN(land[0]) ? b : g.make(land[0], land[1], land[2]);
        }
        return flightBurst(staff, staff.voxels(level), p.x(), p.y(), p.z(), vx, vy, vz, left,
                f == NtmGuns.FUZE_IMPACT ? Math.max(0, IMPACT_ARMED - guns.grenadeTimer(p)) : -1, guns.grenadeBounce(p), world.minY(), GRAVITY, DRAG, null, null, g, 0);
    }

    private static @Nullable Live flightBurst(Staff staff, VoxelRay.Source src, double x, double y, double z,
            double vx, double vy, double vz, int ticks, int armed, double bounce, int minY, double gravity, double drag,
            @Nullable List<double[]> path, double @Nullable [] land, Live g, int input) {
        Live answer = burst(src, x, y, z, vx, vy, vz, ticks, armed, bounce, minY, gravity, drag, path, land, g);
        BurstObserver observer = staff.flightObserver();
        if (observer != null) observer.burst(src, x, y, z, vx, vy, vz, ticks, armed, bounce, minY, gravity, drag,
                path, land, answer == null ? null : new Vec3d(g.bx, g.by, g.bz), input);
        return answer;
    }

    static void follow(Staff staff, Object level, Live g) {
        if (g.path == null || g.parted != null) return;
        int k = g.e.age();
        if (k <= 0 || k >= g.path.size()) return;
        double[] p = g.path.get(k), q = g.path.get(k - 1);
        double x = g.e.x(), y = g.e.y(), z = g.e.z();
        g.e.motion(g.motion);
        for (int j = Math.max(0, k - 1); j <= Math.min(g.path.size() - 1, k + 1); j++) {
            double[] o = g.path.get(j);
            double dx = x - o[0], dy = y - o[1], dz = z - o[2];
            if (dx * dx + dy * dy + dz * dz < 0.25) return;
        }
        StringBuilder near = new StringBuilder();
        WorldView world = Worlds.of(level);
        int cx = GameMath.floor(x), cy = GameMath.floor(y), cz = GameMath.floor(z);
        for (int bz = cz - 1; bz <= cz + 1; bz++) for (int by = cy - 1; by <= cy + 1; by++) for (int bx = cx - 1; bx <= cx + 1; bx++) {
            int state = world.state(bx, by, bz);
            if (world.air(state)) continue;
            near.append(String.format(java.util.Locale.ROOT, " %d %d %d %s%s", bx, by, bz, world.facts(state).path(),
                    world.boxes(bx, by, bz, WorldView.COLLIDER, null, new double[0]) == 0 ? "(no box)"
                            : Voxel.open(staff.voxels(level).voxel(bx, by, bz)) ? "(open to us)" : ""));
        }
        g.parted = String.format(java.util.Locale.ROOT, "tick %d it is at %.2f %.2f %.2f moving %.3f %.3f %.3f, planned at %.2f %.2f %.2f moving %.3f %.3f %.3f"
                + " (a tick before %.2f %.2f %.2f); blocks round it:%s", k, x, y, z, g.motion[0], g.motion[1], g.motion[2], p[0], p[1], p[2], p[3], p[4], p[5], q[0], q[1], q[2],
                near.length() == 0 ? " none" : near);
        partings.add(g.start + "; " + g.parted);
        if (journal != null) event("PARTED " + g.parted);
    }

    static void tossed(Staff staff, NtmGuns guns, Object level, Body mob, Body grenade, float yaw, float pitch, double share) {
        if (!trace) return;
        traced++;
        if (journal != null) event("TRACED");
        Live live = joined(level, grenade);
        List<double[]> path = new ArrayList<>();
        from(staff.voxels(level), mob, yaw, pitch, guns.fragForce() * share, guns.fragBounce(), Worlds.of(level).minY(), 0, 0, 0, path, null, -1, Vec3d::new);
        live.path = path;
        grenade.motion(live.motion);
        live.start = String.format(java.util.Locale.ROOT, "thrown from %.2f %.2f %.2f (%s, eye %.2f), grenade at %.2f %.2f %.2f moving %.3f %.3f %.3f, planned %.2f %.2f %.2f"
                + " moving %.3f %.3f %.3f", mob.x(), mob.y(), mob.z(), mob.poseName(), mob.eyeHeight(), grenade.x(), grenade.y(), grenade.z(), live.motion[0], live.motion[1], live.motion[2],
                path.get(0)[0], path.get(0)[1], path.get(0)[2], path.get(0)[3], path.get(0)[4], path.get(0)[5]);
        if (journal != null) event("PATH " + live.start);
    }

    /** thrown at a place this far off, blocks */
    static final double NEAR = 15, FAR = 26;
    /**
     * nearer than NEAR, a near throw: a place this far off at least, the burst this far off it at least (a burst
     * worked out a little off), and blocks keep all of it off the burst once it is down behind its cover; blocks
     */
    static final double CLOSE = 8, CLOSE_BURST = 6;
    /** bursts within this of the place, blocks */
    static final double LAND = 3;
    /**
     * none of its own (nor it) within this of the burst, nor on its way to a place within it, blocks: HE's blast
     * (7.5) hurts mobs out to twice that, 25 at the burst less a fifteenth a block (stand: 23 at 1.5 blocks, 9.7 at
     * 9.5); at 12 its own were still hit (the battles)
     */
    static final double SAFE = 15;
    /**
     * its own, besides: this much more, for the way they go in the three seconds (out to a peek, to other cover: hit
     * from 13 and 15 blocks off where they stood, the battles)
     */
    static final double OWN_MARGIN = 2;
    /**
     * a mob running from its own side's grenade: to this far off it, blocks (no block keeps that one off: cover); a
     * block past where it starts to run, so that the burst worked out again a little nearer does not send it on
     */
    static final double OWN_CLEAR = SAFE + OWN_MARGIN + 1;
    /**
     * a refuge from one burst keeps out of the others' reach too: its own side's this far (all of its reach, NTM counting
     * from the mob's box, and the burst worked out within 1.5: a stand, its own grenade 15 blocks off where running from
     * another led; as far as it keeps from its own anyway, so it does not run again once the first has gone), blocks
     */
    static final double OTHER_OWN = SAFE + OWN_MARGIN;
    /** a mob running from a burst: to this far off it, or behind a block (2 of the blast's damage there), blocks */
    static final double CLEAR = 12;
    /** a burst this near: it runs (8 and more of the blast's damage), to CLEAR off or behind a block; blocks */
    static final double DANGER = 10;
    /** it sees a grenade this far off, hears one land this near, blocks */
    static final double SEE = 24, HEAR = 5;
    /** NTM's three seconds' fuze, ticks */
    static final int FUSE = 60;
    /** an impact fuze is set off by a hit from this many ticks in the air on (NTM) */
    static final int IMPACT_ARMED = 10;
    /** NTM's flight: gravity a tick, the air's drag, the step off a face it hits, the speed along a face kept */
    static final double GRAVITY = 0.03, DRAG = 0.99, OFF_FACE = 0.05, ALONG = 0.8;
    /** pitches tried: from up this much to down this much, by this, then halved twice round the best */
    static final float PITCH_UP = -70F, PITCH_DOWN = 10F, PITCH_STEP = 5F;

    /** the game tick a throw was last worked out on in each level: one a tick at most there, whoever asks */
    private static final LevelMap<Long> PLANNED = new LevelMap<>();

    /** whether a throw may still be worked out in this level this tick (none taken by mayPlan yet) */
    public static boolean planFree(Object level, long tick) {
        Long last = PLANNED.get(level);
        return last == null || last != tick;
    }

    public static boolean mayPlan(Object level, long tick) {
        Long last = PLANNED.put(level, tick);
        return last == null || last != tick;
    }

    /** refuges looked for in a level this tick, and the tick: a few a tick at most (a grenade in a crowd) */
    private static final LevelMap<long[]> REFUGES = new LevelMap<>();
    static final int REFUGES_A_TICK = 6;

    /** whether one more refuge may be looked for this tick in this level */
    public static boolean mayRefuge(Object level, long tick) {
        long[] r = REFUGES.computeIfAbsent(level, k -> new long[] {Long.MIN_VALUE, 0});
        if (r[0] != tick) {
            r[0] = tick;
            r[1] = 0;
        }
        return r[1]++ < REFUGES_A_TICK;
    }

    /** a block between the burst and this place (a mob's chest there): the blast is stopped */
    static boolean behind(VoxelRay.Source src, Vec3d burst, double x, double y, double z) {
        return !VoxelRay.clear(src, burst.x(), burst.y() + 0.3, burst.z(), x, y + 0.9, z, Voxel.MOVE_MASK);
    }

    /**
     * Out of this burst's reach behind blocks: behind, but never from its own side's grenade, which must do it no harm
     * at all (behind a low wall, the head hidden too, NTM's blast still hurt them a little: 0.3 at 15 blocks, 0.5 at
     * 14.7, the battles); it keeps out of that one's reach instead
     */
    static boolean cover(VoxelRay.Source src, double bx, double by, double bz, double x, double y, double z, boolean own) {
        // all of one standing there, from the burst and from NTM's nodes round it: one ray from the burst to its middle
        // leaves one round a trench's turn a block and a half off, and the blast there still downs it
        return !own && shielded(src, x, y, z, STANDING, bx, by, bz, false);
    }

    /** a soldier standing, blocks (a refuge is where it stands up again) */
    static final double STANDING = 1.95;

    /**
     * a shield's rays: from the burst and from NTM's nodes round it (EntityProcessorCrossSmooth, a frag's: a block off
     * each way; the one below is in the ground), each a little farther (the burst is worked out within a fraction of
     * a block), blocks
     */
    static final double NODE = 1, SHIELD_MORE = 0.3;

    /**
     * A mob down at x, y, z, this high (lying: AiProne.SIZE; crouching), all of it off a burst here: every ray from the
     * burst, and from NTM's nodes round it (NODE aside and up, SHIELD_MORE farther), to its box's corners, the middle
     * of its top and a lying body's head and feet (drawn longer than its box, BlastParts takes them; along the burst's
     * way and across it, whichever way it lies) meets a block that hides what is behind it (an opaque block, sandbags;
     * not glass, bars, a fence or a slab). NTM's blast, and a blast by parts, do no harm there (BlastParts' stand:
     * lying behind a block's wall 2 blocks from the burst, 0).
     */
    static boolean shielded(VoxelRay.Source src, double x, double y, double z, double height, double bx, double by, double bz) {
        return shielded(src, x, y, z, height, bx, by, bz, true);
    }

    /** the same; not lying: its box alone, no body drawn longer than it (one standing behind a wall: AiEntry) */
    static boolean shielded(VoxelRay.Source src, double x, double y, double z, double height, double bx, double by, double bz, boolean lying) {
        double hx = bx - x, hz = bz - z, h = Math.sqrt(hx * hx + hz * hz);
        if (h < 1e-6) return false;
        double ux = hx / h, uz = hz / h, half = 0.3, low = y + 0.05, top = y + height - 0.05;
        double[][] to = { { x - half, low, z - half }, { x + half, low, z - half }, { x - half, low, z + half }, { x + half, low, z + half },
                { x - half, top, z - half }, { x + half, top, z - half }, { x - half, top, z + half }, { x + half, top, z + half },
                { x, top, z }, { x + ux * 0.9, y + 0.35, z + uz * 0.9 }, { x - ux * 0.9, y + 0.35, z - uz * 0.9 },
                { x - uz * 0.9, y + 0.35, z + ux * 0.9 }, { x + uz * 0.9, y + 0.35, z - ux * 0.9 } };
        if (!lying) to = java.util.Arrays.copyOf(to, 9);
        double r = NODE + SHIELD_MORE;
        double[][] from = { { 0, 0.3, 0 }, { r, 0.3, 0 }, { -r, 0.3, 0 }, { 0, 0.3, r }, { 0, 0.3, -r }, { 0, 0.3 + r, 0 } };
        for (double[] f : from)
            for (double[] t : to)
                if (VoxelRay.clear(src, bx + f[0], by + f[1], bz + f[2], t[0], t[1], t[2], Voxel.SIGHT)) return false;
        return true;
    }

    /** a throw: its turn, and where it bursts */
    record Throw<T>(float yaw, float pitch, T burst, double share) {}

    /**
     * A frag thrown as hard as NTM throws it bursts this far off at the nearest (a lob), blocks; a near throw is
     * softer, a share of that so that its nearest burst is some NEAR_SHORT of the place (the steep lobs fall short
     * of it, the flatter ones reach it)
     */
    static final double LOB = 16, NEAR_SHORT = 0.8;

    /** a near throw's share of NTM's force, for a place this far off */
    static double share(double far) {
        return Math.min(1, Math.sqrt(NEAR_SHORT * far / LOB));
    }

    static <T> @Nullable Throw<T> planNow(Staff staff, NtmGuns guns, Object level, Body m, T at, boolean smoke, double down, double smokeForce, Vectors<T> vectors) {
        double dx = vectors.x(at) - m.x(), dz = vectors.z(at) - m.z();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90F;
        // a smoke grenade: thrown softer, going off where it first hits once armed (AiSmoke), for the near throws it wants
        // a near throw: softer (a frag as hard as NTM throws it falls LOB blocks off at the nearest)
        double share = smoke || down <= 0 ? 1 : share(Math.hypot(dx, dz));
        double force = smoke ? smokeForce : guns.fragForce() * share, bounce = guns.fragBounce();
        int armed = smoke ? IMPACT_ARMED : -1;
        VoxelRay.Source src = staff.voxels(level);
        int minY = Worlds.of(level).minY();
        float best = Float.NaN;
        double bestD = Double.MAX_VALUE;
        List<Throw<T>> near = new ArrayList<>();
        double[] land = new double[3];
        for (float p = PITCH_UP; p <= PITCH_DOWN; p += PITCH_STEP) {
            T b = rolled(src, m, yaw, p, force, bounce, minY, land, armed, vectors);
            if (b == null) continue;
            near.add(new Throw<>(yaw, p, b, share));
            if (vectors.distanceTo(b, at) < bestD) {
                bestD = vectors.distanceTo(b, at);
                best = p;
            }
        }
        if (Float.isNaN(best)) return null;
        for (float step = PITCH_STEP / 2; step >= PITCH_STEP / 4; step /= 2) {
            float mid = best;
            for (float p : new float[] {mid - step, mid + step}) {
                T b = rolled(src, m, yaw, p, force, bounce, minY, land, armed, vectors);
                if (b == null) continue;
                near.add(new Throw<>(yaw, p, b, share));
                if (vectors.distanceTo(b, at) < bestD) {
                    bestD = vectors.distanceTo(b, at);
                    best = p;
                }
            }
        }
        // the nearest to the place of those within LAND that burst far from its own
        near.sort((a, b) -> Double.compare(vectors.distanceToSqr(a.burst(), at), vectors.distanceToSqr(b.burst(), at)));
        for (Throw<T> t : near) {
            if (vectors.distanceTo(t.burst(), at) > LAND) break;
            if ((smoke || !staff.ownNear(level, src, m, vectors.x(t.burst()), vectors.y(t.burst()), vectors.z(t.burst()), down)) && steady(staff, level, src, m, t, at, force, bounce, minY, armed, smoke, down, vectors)) return t;
        }
        return null;
    }

    /**
     * NTM's grenade box: half its width, and its height over where it is (0.25 by 0.25), and a little more round it;
     * blocks
     */
    static final double BOX_HALF = 0.125, BOX_HIGH = 0.25, BOX_MORE = 0.05;

    /** the box's corners (and BOX_MORE) as starts off NTM's: the box, not its middle, bumps into a block's edge */
    private static final double[][] CORNERS = new double[8][];

    static {
        int i = 0;
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2)
                for (double y : new double[] {-BOX_MORE, BOX_HIGH + BOX_MORE})
                    CORNERS[i++] = new double[] {sx * (BOX_HALF + BOX_MORE), y, sz * (BOX_HALF + BOX_MORE)};
    }

    /**
     * Whether the throw holds started from each of the box's corners (the grenade's box clips a corner its middle
     * passes: the battles, one burst 22 blocks short, among the thrower's own; its box's corner caught a wall's top
     * edge on its second tick, one off it each way alone passed over it): each such flight bursts near the place too
     * (LAND and 2 more), none near its own.
     */
    private static <T> boolean steady(Staff staff, Object level, VoxelRay.Source src, Body m, Throw<T> t, T at, double force, double bounce, int minY, int armed,
            boolean smoke, double down, Vectors<T> vectors) {
        for (double[] o : CORNERS) {
            T b = from(src, m, t.yaw(), t.pitch(), force, bounce, minY, o[0], o[1], o[2], null, null, armed, vectors);
            if (b == null || vectors.distanceTo(b, at) > LAND + 2 || !smoke && staff.ownNear(level, src, m, vectors.x(b), vectors.y(b), vectors.z(b), down)) return false;
        }
        return true;
    }

    /**
     * A grenade that comes down rolls only so far past where it first hits a block before it bursts, blocks: past that
     * its flight is not known (a flat throw sinks into the ground where it lands and stops dead or bounces back off
     * the next block's side, NTM's box against the ground: bursts 5 and 19 blocks short, its own hit, the battles;
     * a high one rolls on as worked out)
     */
    static final double ROLL = 2.5;

    /** where a grenade thrown so bursts; null if it rolls more than ROLL past where it first hits a block */
    private static <T> @Nullable T rolled(VoxelRay.Source src, Body m, float yaw, float pitch, double force, double bounce, int minY, double[] land, int armed, Vectors<T> vectors) {
        T b = from(src, m, yaw, pitch, force, bounce, minY, 0, 0, 0, null, land, armed, vectors);
        return b == null || !Double.isNaN(land[0]) && Math.hypot(vectors.x(b) - land[0], vectors.z(b) - land[2]) > ROLL ? null : b;
    }

    /** where a grenade thrown by the mob with this turn bursts (NTM's start: by its eye, a quarter block off; moved by o) */
    /** an impact fuze's flight is followed this far at most, ticks */
    static final int IMPACT_TICKS = 100;

    private static <T> @Nullable T from(VoxelRay.Source src, Body m, float yaw, float pitch, double force, double bounce, int minY, double ox, double oy,
            double oz, @Nullable List<double[]> path, double @Nullable [] land, int armed, Vec3Maker<T> maker) {
        return fromAt(src, m.x(), m.y() + m.eyeHeight(), m.z(), yaw, pitch, force, bounce, minY, ox, oy, oz, path, land, armed, maker);
    }

    /** the same thrown by one standing with its feet's middle and its eye at this place */
    static <T> @Nullable T fromAt(VoxelRay.Source src, double mx, double eye, double mz, float yaw, float pitch, double force, double bounce, int minY,
            double ox, double oy, double oz, @Nullable List<double[]> path, double @Nullable [] land, int armed, Vec3Maker<T> maker) {
        double r = Math.toRadians(180F - yaw);
        double x = mx + 0.25 * Math.cos(r) + ox, y = eye - 0.25 + oy, z = mz - 0.25 * Math.sin(r) + oz;
        double f = Math.toRadians(pitch), g = Math.toRadians(-yaw);
        return burst(src, x, y, z, Math.sin(g) * Math.cos(f) * force, -Math.sin(f) * force, Math.cos(g) * Math.cos(f) * force, armed >= 0 ? IMPACT_TICKS : FUSE, armed, bounce, minY,
                GRAVITY, DRAG, path, land, maker);
    }

    static <T> @Nullable Throw<T> planStick(Staff staff, NtmGuns guns, Object level, double x, double eye, double z, T at, java.util.function.Predicate<T> safe, Vectors<T> vectors) {
        double dx = vectors.x(at) - x, dz = vectors.z(at) - z;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90F;
        double share = share(Math.hypot(dx, dz)), force = guns.fragForce() * share, bounce = guns.fragBounce();
        VoxelRay.Source src = staff.voxels(level);
        int minY = Worlds.of(level).minY();
        double[] land = new double[3];
        List<Throw<T>> all = new ArrayList<>();
        for (float p = PITCH_UP; p <= PITCH_DOWN; p += PITCH_STEP) {
            fromAt(src, x, eye, z, yaw, p, force, bounce, minY, 0, 0, 0, null, land, -1, vectors);
            if (Double.isNaN(land[0])) continue;
            T b = vectors.make(land[0], land[1], land[2]);
            if (safe.test(b)) all.add(new Throw<>(yaw, p, b, share));
        }
        all.sort((a, b) -> Double.compare(vectors.distanceToSqr(a.burst(), at), vectors.distanceToSqr(b.burst(), at)));
        next:
        for (Throw<T> t : all) {
            for (double[] o : CORNERS) {
                fromAt(src, x, eye, z, yaw, t.pitch(), force, bounce, minY, o[0], o[1], o[2], null, land, -1, vectors);
                if (Double.isNaN(land[0]) || !safe.test(vectors.make(land[0], land[1], land[2]))) continue next;
            }
            return t;
        }
        return null;
    }

    static boolean near(long cell, double bx, double by, double bz, double d2) {
        if (cell == Long.MIN_VALUE) return false;
        double dx = PosLong.blockX(cell) + 0.5 - bx, dy = PosLong.blockY(cell) - by, dz = PosLong.blockZ(cell) + 0.5 - bz;
        return dx * dx + dy * dy + dz * dz < d2;
    }

    /**
     * Where a grenade bursts after ticks more ticks (an impact fuze, armed after so many more ticks: where it first hits
     * from then on; -1: no impact fuze), flown as NTM flies it
     * (EntityThrowableNT, EntityGrenadeUniversal): each tick it goes its speed; a block on the way (any block a mob
     * bumps into, as a whole block) puts it a little off that face, its speed off the face turned back times bounce and
     * along it 0.8; then NTM moves it by the speed it had: into the face it stops, its speed off the face gone (unless
     * that was too slow to reach it), along it it goes on. Then the air takes 1% of its speed and gravity 0.03. Null:
     * it fell out of the world.
     */
    public static <T> @Nullable T burst(VoxelRay.Source src, double x, double y, double z, double vx, double vy, double vz, int ticks, int armedAfter,
            double bounce, int minY, Vec3Maker<T> maker) {
        return burst(src, x, y, z, vx, vy, vz, ticks, armedAfter, bounce, minY, GRAVITY, DRAG, maker);
    }

    /** the same with other gravity and air (a tick's speed taken off, a tick's speed left) */
    public static <T> @Nullable T burst(VoxelRay.Source src, double x, double y, double z, double vx, double vy, double vz, int ticks, int armedAfter,
            double bounce, int minY, double gravity, double drag, Vec3Maker<T> maker) {
        return burst(src, x, y, z, vx, vy, vz, ticks, armedAfter, bounce, minY, gravity, drag, null, null, maker);
    }

    /**
     * The same, each tick's start put down in path (x y z, its speed: the stands), where it first hits a block in land
     * (x y z; NaN if it never does)
     */
    public static <T> @Nullable T burst(VoxelRay.Source src, double x, double y, double z, double vx, double vy, double vz, int ticks, int armedAfter,
            double bounce, int minY, double gravity, double drag, @Nullable List<double[]> path, double @Nullable [] land, Vec3Maker<T> maker) {
        double[] hit = new double[2];
        boolean landed = false;
        if (land != null) java.util.Arrays.fill(land, Double.NaN);
        for (int t = 0; t < ticks; t++) {
            if (path != null) path.add(new double[] {x, y, z, vx, vy, vz});
            if (y < minY) return null;
            if (first(src, x, y, z, x + vx, y + vy, z + vz, hit)) {
                double s = hit[0];
                int axis = (int) hit[1];
                double hx = x + vx * s, hy = y + vy * s, hz = z + vz * s;
                if (land != null && !landed) {
                    land[0] = hx;
                    land[1] = hy;
                    land[2] = hz;
                }
                if (armedAfter >= 0 && t + 1 >= armedAfter) return maker.make(hx, hy, hz);
                double[] p = {hx, hy, hz}, v = {vx, vy, vz};
                for (int a = 0; a < 3; a++) {
                    if (a == axis) {
                        double n = v[a] > 0 ? -1 : 1;
                        if (Math.abs(v[a]) > OFF_FACE) {
                            // NTM's box against the face: a hair off it here, so the next tick starts out of the block
                            p[a] = Math.round(p[a]) + n * 1e-4;
                            v[a] = 0;
                        } else {
                            p[a] += n * OFF_FACE + v[a];
                            v[a] = -v[a] * bounce;
                        }
                    } else {
                        p[a] += v[a];
                        v[a] *= ALONG;
                    }
                }
                x = p[0];
                y = p[1];
                z = p[2];
                vx = v[0];
                vy = v[1];
                vz = v[2];
                landed = true;
            } else {
                x += vx;
                y += vy;
                z += vz;
            }
            // come to rest (it only hops a hair on the ground): where it lies is where it bursts
            if (landed && vx * vx + vz * vz < 1e-5 && Math.abs(vy) < 2 * gravity) return maker.make(x, y, z);
            vx *= drag;
            vy = vy * drag - gravity;
            vz *= drag;
        }
        return maker.make(x, y, z);
    }

    /**
     * The first block a mob bumps into on the line (the block the line starts in not asked): out[0] how far along
     * (0..1), out[1] the axis of the face it comes in by (0 x, 1 y, 2 z). Whether there is one.
     */
    static boolean first(VoxelRay.Source src, double x0, double y0, double z0, double x1, double y1, double z1, double[] out) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        int x = GameMath.floor(x0), y = GameMath.floor(y0), z = GameMath.floor(z0);
        int ex = GameMath.floor(x1), ey = GameMath.floor(y1), ez = GameMath.floor(z1);
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sy = dy > 0 ? 1 : dy < 0 ? -1 : 0, sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tdx = sx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double tdy = sy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double tdz = sz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double tx = sx == 0 ? Double.POSITIVE_INFINITY : (sx > 0 ? (x + 1 - x0) : (x0 - x)) * tdx;
        double ty = sy == 0 ? Double.POSITIVE_INFINITY : (sy > 0 ? (y + 1 - y0) : (y0 - y)) * tdy;
        double tz = sz == 0 ? Double.POSITIVE_INFINITY : (sz > 0 ? (z + 1 - z0) : (z0 - z)) * tdz;
        int steps = Math.abs(ex - x) + Math.abs(ey - y) + Math.abs(ez - z);
        for (int i = 0; i < steps; i++) {
            double t;
            int axis;
            if (tx <= ty && tx <= tz) {
                t = tx;
                x += sx;
                tx += tdx;
                axis = 0;
            } else if (ty <= tz) {
                t = ty;
                y += sy;
                ty += tdy;
                axis = 1;
            } else {
                t = tz;
                z += sz;
                tz += tdz;
                axis = 2;
            }
            if (t > 1) break;
            if (!Voxel.open(src.voxel(x, y, z))) {
                out[0] = t;
                out[1] = axis;
                return true;
            }
        }
        return false;
    }

    /** whether this cell is out of the burst's reach (clear) or behind a block from it */
    static boolean safe(VoxelRay.Source src, double bx, double by, double bz, long cell, double clear, boolean own) {
        double cx = PosLong.blockX(cell) + 0.5, cy = PosLong.blockY(cell), cz = PosLong.blockZ(cell) + 0.5;
        double dx = cx - bx, dy = cy - by, dz = cz - bz;
        return dx * dx + dy * dy + dz * dz >= clear * clear || cover(src, bx, by, bz, cx, cy, cz, own);
    }

    /** Drops both budgets when the level goes. */
    public static void forgetLevel(Object level) {
        LEVELS.remove(level);
        PLANNED.remove(level);
        REFUGES.remove(level);
        if (journal != null) event("FORGET");
    }
}
