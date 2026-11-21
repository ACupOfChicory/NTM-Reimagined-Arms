package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import com.hbm.entity.grenade.EntityGrenadeUniversal;
import com.hbm.items.weapon.grenade.ItemGrenadeFilling.EnumGrenadeFilling;
import com.hbm.items.weapon.grenade.ItemGrenadeFuze.EnumGrenadeFuze;
import com.hbm.items.weapon.grenade.ItemGrenadeShell.EnumGrenadeShell;
import com.hbm.items.weapon.grenade.ItemGrenadeUniversal;

import acup.gunfeel.CoreServices;
import acup.gunfeel.EntityBody;
import acup.gunfeel.Vecs;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.NtmGuns;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Grenades, NTM's own: a frag with HE and a three seconds' fuze. An AI mob carries a few (server setting aiGrenades,
 * 2 by default, 0 to 8, kept on the mob) and throws one at a target it lost behind cover, or one that sits behind cover
 * with only its head out: over the cover, to burst within {@link #LAND} blocks of where the target is or went out of
 * sight, never within {@link #SAFE} of one of its own, nor of itself unless blocks keep all of it off the burst once it
 * is down ({@link #shielded}: a near throw, {@link #CLOSE} blocks on). Where it bursts is worked out here as NTM flies it ({@link #burst}), bounces and all, on the AI's
 * block bytes. And an AI mob that sees a grenade come (anyone's: NTM's grenades that a fuze sets off), or hears one
 * land near, runs from where it will burst: out of its reach, or behind a block. Server thread.
 */
public final class AiGrenades extends AiGrenadesBase {
    public static final String KEY = "aiGrenades";
    /** where its grenades left are kept on the mob */
    static final String STOCK_KEY = "armsaiGrenades";
    /** throws (off only for the stands' "before") */
    static boolean on = true;
    /** runs from grenades (off only for the stands' "before") */
    static boolean dodgeOn = true;

    static @Nullable AiGrenadesAlike alike;
    private static final Vectors<Vec3> VECTORS = new Vectors<>() {
        @Override public Vec3 make(double x, double y, double z) { return new Vec3(x, y, z); }
        @Override public double x(Vec3 v) { return v.x; }
        @Override public double y(Vec3 v) { return v.y; }
        @Override public double z(Vec3 v) { return v.z; }
        @Override public double distanceTo(Vec3 a, Vec3 b) { return a.distanceTo(b); }
        @Override public double distanceToSqr(Vec3 a, Vec3 b) { return a.distanceToSqr(b); }
    };
    private static final NtmGuns GUNS = CoreServices.load(NtmGuns.class, NtmGuns.NONE);
    private static final Staff STAFF = new Staff() {
        @Override public VoxelRay.Source voxels(Object level) { return AiBots.voxels((ServerLevel) level); }
        @Override public boolean ownNear(Object level, VoxelRay.Source src, Body mob, double bx, double by, double bz, double down) {
            return AiGrenades.ownNear((ServerLevel) level, src, (Mob) EntityBody.entity(mob), bx, by, bz, down);
        }
    };

    /** The original API read site, kept for the AI's compatibility probe. */
    public static double fragForce() { return EnumGrenadeShell.FRAG.getYeetForce(); }

    /** The original API read site, kept for the AI's compatibility probe. */
    public static double fragBounce() { return EnumGrenadeShell.FRAG.getBounce(); }

    static boolean planFree(ServerLevel level, long tick) { return AiGrenadesBase.planFree(level, tick); }
    static boolean mayPlan(ServerLevel level, long tick) { return AiGrenadesBase.mayPlan(level, tick); }
    static boolean mayRefuge(ServerLevel level, long tick) { return AiGrenadesBase.mayRefuge(level, tick); }

    static boolean behind(VoxelRay.Source src, Vec3 burst, double x, double y, double z) {
        return AiGrenadesBase.behind(src, Vecs.of(burst), x, y, z);
    }

    static boolean cover(VoxelRay.Source src, @Nullable Vec3 burst, double x, double y, double z, boolean own) {
        boolean answer = !own && AiGrenadesBase.cover(src, burst.x, burst.y, burst.z, x, y, z, false);
        return alike == null ? answer : alike.cover(src, burst, x, y, z, own, answer);
    }

    static boolean shielded(VoxelRay.Source src, double x, double y, double z, double height, Vec3 burst) {
        return shieldedAt(src, x, y, z, height, burst.x, burst.y, burst.z);
    }

    private static boolean shieldedAt(VoxelRay.Source src, double x, double y, double z, double height, double bx, double by, double bz) {
        boolean answer = AiGrenadesBase.shielded(src, x, y, z, height, bx, by, bz);
        return alike == null ? answer : alike.shielded(src, x, y, z, height, bx, by, bz, true, answer, false);
    }

    static boolean shielded(VoxelRay.Source src, double x, double y, double z, double height, Vec3 burst, boolean lying) {
        boolean answer = AiGrenadesBase.shielded(src, x, y, z, height, burst.x, burst.y, burst.z, lying);
        return alike == null ? answer : alike.shielded(src, x, y, z, height, burst.x, burst.y, burst.z, lying, answer, true);
    }

    static double share(double far) {
        double answer = AiGrenadesBase.share(far);
        return alike == null ? answer : alike.share(far, answer);
    }

    private static @Nullable Throw wrap(AiGrenadesBase.@Nullable Throw<Vec3> t) {
        return t == null ? null : new Throw(t.yaw(), t.pitch(), t.burst(), t.share());
    }

    private static @Nullable Throw planNow(ServerLevel level, Mob m, Vec3 at, boolean smoke, double down, double smokeForce) {
        Throw answer = wrap(AiGrenadesBase.planNow(STAFF, GUNS, level, EntityBody.of(m), at, smoke, down,
                Double.isNaN(smokeForce) ? AiSmoke.FORCE : smokeForce, VECTORS));
        return alike == null ? answer : alike.planNow(level, m, at, smoke, down, smokeForce, answer);
    }

    static @Nullable Throw planStick(ServerLevel level, double x, double eye, double z, Vec3 at, java.util.function.Predicate<Vec3> safe) {
        long t0 = System.nanoTime();
        try {
            AiGrenadesAlike.Trials trials = alike == null ? null : new AiGrenadesAlike.Trials(safe);
            Throw answer = wrap(AiGrenadesBase.planStick(STAFF, GUNS, level, x, eye, z, at, trials == null ? safe : trials, VECTORS));
            return alike == null ? answer : alike.planStick(level, x, eye, z, at, trials, answer);
        } finally {
            planNanos += System.nanoTime() - t0;
        }
    }

    private static @Nullable Vec3 from(VoxelRay.Source src, Mob m, float yaw, float pitch, double force, double bounce, int minY,
            double ox, double oy, double oz, @Nullable List<double[]> path, double @Nullable [] land, int armed) {
        return AiGrenadesBase.fromAt(src, m.getX(), m.getY() + m.getEyeHeight(), m.getZ(), yaw, pitch, force, bounce, minY,
                ox, oy, oz, path, land, armed, VECTORS);
    }

    private static boolean near(long cell, @Nullable Vec3 b, double d2) {
        return cell != Long.MIN_VALUE && AiGrenadesBase.near(cell, b.x, b.y, b.z, d2);
    }

    static @Nullable Vec3 burst(VoxelRay.Source src, double x, double y, double z, double vx, double vy, double vz, int ticks, int armedAfter,
            double bounce, int minY) {
        Vec3 answer = AiGrenadesBase.burst(src, x, y, z, vx, vy, vz, ticks, armedAfter, bounce, minY, VECTORS);
        return alike == null ? answer : alike.burst(src, x, y, z, vx, vy, vz, ticks, armedAfter, bounce, minY, GRAVITY, DRAG,
                null, null, null, answer, 0);
    }

    static @Nullable Vec3 burst(VoxelRay.Source src, double x, double y, double z, double vx, double vy, double vz, int ticks, int armedAfter,
            double bounce, int minY, double gravity, double drag) {
        Vec3 answer = AiGrenadesBase.burst(src, x, y, z, vx, vy, vz, ticks, armedAfter, bounce, minY, gravity, drag, VECTORS);
        return alike == null ? answer : alike.burst(src, x, y, z, vx, vy, vz, ticks, armedAfter, bounce, minY, gravity, drag,
                null, null, null, answer, 1);
    }

    static @Nullable Vec3 burst(VoxelRay.Source src, double x, double y, double z, double vx, double vy, double vz, int ticks, int armedAfter,
            double bounce, int minY, double gravity, double drag, @Nullable List<double[]> path, double @Nullable [] land) {
        List<double[]> before = alike == null || path == null ? null : AiGrenadesAlike.copy(path);
        Vec3 answer = AiGrenadesBase.burst(src, x, y, z, vx, vy, vz, ticks, armedAfter, bounce, minY, gravity, drag, path, land, VECTORS);
        return alike == null ? answer : alike.burst(src, x, y, z, vx, vy, vz, ticks, armedAfter, bounce, minY, gravity, drag,
                path, land, before, answer, 2);
    }

    static boolean safe(VoxelRay.Source src, Vec3 burst, long cell, double clear, boolean own) {
        boolean answer = AiGrenadesBase.safe(src, burst.x, burst.y, burst.z, cell, clear, own);
        return alike == null ? answer : alike.safe(src, burst, cell, clear, own, answer);
    }

    private AiGrenades() {
    }

    /** the bench: time spent working out throws, and on grenades in the air (bursts, dangers, refuges), ns */
    public static long planNanos, liveNanos;

        /** grenades a mob new to the AI carries */
    static int stock() {
        return Math.max(0, Math.min(8, Math.round(acup.gunfeel.ReaimTimes.number(KEY, 2F))));
    }

    /** a throw: its turn, and where it bursts */
    record Throw(float yaw, float pitch, Vec3 burst, double share) {}

    /**
     * How to throw at this place from where the mob stands: the pitch (turned to the place) at which NTM's frag bursts
     * nearest it, within LAND of it; null if none does, or one of its own (or it) would be within SAFE of the burst. A
     * smoke grenade (AiSmoke) bursts near its own as it will. {@code down} more than 0: a near throw, the mob down this
     * high behind its cover till the burst (a burst nearer it than SAFE, CLOSE_BURST on, only shielded).
     */
    static @Nullable Throw plan(ServerLevel level, Mob m, Vec3 at, boolean smoke, double down) {
        return plan(level, m, at, smoke, down, Double.NaN);
    }

    /** the same, a smoke grenade thrown this hard (NaN: AiSmoke.FORCE) */
    static @Nullable Throw plan(ServerLevel level, Mob m, Vec3 at, boolean smoke, double down, double smokeForce) {
        long t0 = System.nanoTime();
        try {
            return planNow(level, m, at, smoke, down, smokeForce);
        } finally {
            planNanos += System.nanoTime() - t0;
        }
    }

    /** NTM's frag with glue and a three seconds' fuze, of this filling, thrown by the mob with this turn */
    static EntityGrenadeUniversal tossStick(ServerLevel level, Mob m, Throw t, EnumGrenadeFilling filling) {
        return launch(level, m, t, ItemGrenadeUniversal.make(EnumGrenadeShell.FRAG, filling, EnumGrenadeFuze.S3, com.hbm.items.weapon.grenade.ItemGrenadeExtra.EnumGrenadeExtra.GLUE), t.share());
    }

    /**
     * It within SAFE and OWN_MARGIN of the burst (out of the reach it runs from its own grenade in), or one of its own
     * (an AI mob, a player of its team) within that too: where it is, where it is on its way to, the peek it looks out
     * from. Down more than 0: it, down that high where it is till the burst, may be nearer, CLOSE_BURST on, shielded.
     */
    static boolean ownNear(ServerLevel level, VoxelRay.Source src, Mob m, double bx, double by, double bz, double down) {
        double s2 = (SAFE + OWN_MARGIN) * (SAFE + OWN_MARGIN), d2 = m.distanceToSqr(bx, by, bz);
        if (d2 < s2 && !(down > 0 && d2 >= CLOSE_BURST * CLOSE_BURST)) return true;
        for (AiBot o : AiBots.bots(level)) {
            if (o.mob == m || !o.mob.isAlive() || !AiTeams.own(m, o.mob)) continue;
            if (o.mob.distanceToSqr(bx, by, bz) < s2 || AiGrenadesBase.near(o.mover.goal(), bx, by, bz, s2)) return true;
            if (o.brain instanceof AiSoldier s && s.spot() != null && (AiGrenadesBase.near(s.spot().cell(), bx, by, bz, s2) || s.spot().hasPeek() && AiGrenadesBase.near(s.spot().peek(), bx, by, bz, s2))) return true;
        }
        for (Player p : level.players())
            if (!p.isSpectator() && p.isAlive() && AiTeams.own(m, p) && p.distanceToSqr(bx, by, bz) < s2) return true;
        // last, the dearest: a near throw's shield
        if (d2 < s2 && !shieldedAt(src, m.getX(), m.getY(), m.getZ(), down, bx, by, bz)) return true;
        return false;
    }

    /** NTM's frag with HE and a three seconds' fuze, thrown by the mob with this turn */
    static EntityGrenadeUniversal toss(ServerLevel level, Mob m, Throw t) {
        EntityGrenadeUniversal g = launch(level, m, t, ItemGrenadeUniversal.make(EnumGrenadeShell.FRAG, EnumGrenadeFilling.HE, EnumGrenadeFuze.S3), t.share());
        if (!trace) return g;
        // the stands: the flight it was planned to take, to set against the one it takes
        traced++;
        joined(level, g);
        List<double[]> path = new ArrayList<>();
        from(AiBots.voxels(level), m, t.yaw(), t.pitch(), EnumGrenadeShell.FRAG.getYeetForce() * t.share(), EnumGrenadeShell.FRAG.getBounce(), level.getMinY(), 0, 0, 0, path, null, -1);
        for (Live l : LEVELS.get(level))
            if (l.e == g) {
                l.path = path;
                l.start = String.format(java.util.Locale.ROOT, "thrown from %.2f %.2f %.2f (%s, eye %.2f), grenade at %.2f %.2f %.2f moving %.3f %.3f %.3f, planned %.2f %.2f %.2f"
                        + " moving %.3f %.3f %.3f", m.getX(), m.getY(), m.getZ(), m.getPose(), m.getEyeHeight(), g.getX(), g.getY(), g.getZ(), g.getDeltaMovement().x,
                        g.getDeltaMovement().y, g.getDeltaMovement().z, path.get(0)[0], path.get(0)[1], path.get(0)[2], path.get(0)[3], path.get(0)[4], path.get(0)[5]);
            }
        return g;
    }

    /** the stands: thrown grenades' planned flights set against the ones they take */
    static boolean trace;
    /** the stands: grenades thrown so far, and how each one that left its planned flight was thrown and left it */
    static int traced;
    static final List<String> partings = new ArrayList<>();

    /** the stands: how the thrower's grenade going off left its planned flight; null if it is not known */
    static @Nullable String parted(ServerLevel level, net.minecraft.world.entity.Entity owner) {
        List<Live> l = LEVELS.get(level);
        Live best = null;
        if (l != null) for (Live g : l) if (g.e.getOwner() == owner && (best == null || g.e.tickCount > best.e.tickCount)) best = g;
        return best == null || best.start == null ? null : best.start + "; " + (best.parted == null ? "kept to it" : best.parted);
    }

    /** the stands: the first tick the grenade is half a block off its planned flight, and what is there */
    private static void follow(ServerLevel level, Live g) {
        if (g.path == null || g.parted != null) return;
        int k = g.e.tickCount;
        if (k <= 0 || k >= g.path.size()) return;
        double[] p = g.path.get(k), q = g.path.get(k - 1);
        Vec3 at = g.e.position(), v = g.e.getDeltaMovement();
        // a tick either way: when in the server's tick its own ticks are counted is not asked
        for (int j = Math.max(0, k - 1); j <= Math.min(g.path.size() - 1, k + 1); j++) {
            double[] o = g.path.get(j);
            if (at.distanceToSqr(o[0], o[1], o[2]) < 0.25) return;
        }
        StringBuilder near = new StringBuilder();
        BlockPos c = BlockPos.containing(at);
        for (BlockPos b : BlockPos.betweenClosed(c.offset(-1, -1, -1), c.offset(1, 1, 1))) {
            var st = level.getBlockState(b);
            if (st.isAir()) continue;
            near.append(String.format(java.util.Locale.ROOT, " %d %d %d %s%s", b.getX(), b.getY(), b.getZ(), BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath(),
                    st.getCollisionShape(level, b).isEmpty() ? "(no box)" : Voxel.open(AiBots.voxels(level).voxel(b.getX(), b.getY(), b.getZ())) ? "(open to us)" : ""));
        }
        g.parted = String.format(java.util.Locale.ROOT, "tick %d it is at %.2f %.2f %.2f moving %.3f %.3f %.3f, planned at %.2f %.2f %.2f moving %.3f %.3f %.3f"
                + " (a tick before %.2f %.2f %.2f); blocks round it:%s", k, at.x, at.y, at.z, v.x, v.y, v.z, p[0], p[1], p[2], p[3], p[4], p[5], q[0], q[1], q[2],
                near.length() == 0 ? " none" : near);
        partings.add(g.start + "; " + g.parted);
    }

    // ------------------------------------------------------------------ grenades in the air

    /**
     * A grenade in the level (NTM's grenades, its bouncing ones: dynamite and the like, 40 mm grenades and rockets fired
     * from a gun): where it will burst (worked out again every few ticks, a fired one's every tick), the AI mobs that know
     * of it.
     */
    static final class Live {
        final net.minecraft.world.entity.projectile.Projectile e;
        @Nullable Vec3 burst;
        long at = Long.MIN_VALUE;
        final IntOpenHashSet known = new IntOpenHashSet();
        /** the stands: its planned flight (each tick's start), how it was thrown, where it first left that flight */
        @Nullable List<double[]> path;
        @Nullable String start, parted;

        Live(net.minecraft.world.entity.projectile.Projectile e) {
            this.e = e;
        }
    }

    /** this grenade (a frag's shell: its flight) thrown by the mob with this turn, this share of a frag's force */
    static EntityGrenadeUniversal launch(ServerLevel level, Mob m, Throw t, net.minecraft.world.item.ItemStack item, double force) {
        m.setYRot(t.yaw());
        m.setYHeadRot(t.yaw());
        m.setYBodyRot(t.yaw());
        m.setXRot(t.pitch());
        EntityGrenadeUniversal g = new EntityGrenadeUniversal(level, m, item);
        if (force != 1) g.setDeltaMovement(g.getDeltaMovement().scale(force));
        level.addFreshEntity(g);
        return g;
    }

    /**
     * Whether this is a grenade to run from: NTM's grenade (any shell and filling), its bouncing ones (dynamite, the
     * old grenades), a 40 mm grenade or a rocket fired from a gun (the GL-54, Congo Lake, MK108, rocket launchers). Not
     * a nuke's: no running from that; not the AI's smoke grenade (AiSmoke).
     */
    public static boolean threat(net.minecraft.world.entity.Entity e) {
        return e instanceof EntityGrenadeUniversal && !AiSmoke.smoke(e) || e instanceof com.hbm.entity.grenade.EntityGrenadeBouncyGeneric
                || e instanceof com.hbm.entity.projectile.EntityBulletBaseMK4 b
                        && (b.config instanceof acup.gunfeel.gl54.GrenadeType || acup.gunfeel.ExplosiveAmmo.heavy(b.config));
    }

    /** its burst worked out again this often, ticks */
    static final int AGAIN = 4;

    private static final Map<ServerLevel, List<Live>> LEVELS = new WeakHashMap<>();

    /** the stands: the thrower's grenade on its way the longest (the one going off): where it is, where it was to burst */
    static Vec3 @Nullable [] bursting(ServerLevel level, net.minecraft.world.entity.Entity owner) {
        List<Live> l = LEVELS.get(level);
        Live best = null;
        if (l != null) for (Live g : l) if (g.e.getOwner() == owner && (best == null || g.e.tickCount > best.e.tickCount)) best = g;
        return best == null ? null : new Vec3[] {best.e.position(), best.burst};
    }

    /** the stands: where this grenade is to burst as last worked out; null if not known */
    static @Nullable Vec3 predicted(ServerLevel level, net.minecraft.world.entity.Entity e) {
        List<Live> l = LEVELS.get(level);
        if (l != null) for (Live g : l) if (g.e == e) return g.burst;
        return null;
    }

    /** a grenade comes into a level (thrown or fired by anyone; threat) */
    static void joined(ServerLevel level, net.minecraft.world.entity.projectile.Projectile g) {
        List<Live> l = LEVELS.computeIfAbsent(level, k -> new ArrayList<>());
        for (Live o : l) if (o.e == g) return;
        l.add(new Live(g));
    }

    public static void forget(ServerLevel level) {
        LEVELS.remove(level);
        AiGrenadesBase.forgetLevel(level);
    }

    /** every server tick, from AiBots.tick: grenades gone let go, bursts worked out again */
    static void tick(ServerLevel level) {
        List<Live> l = LEVELS.get(level);
        if (l == null) return;
        l.removeIf(g -> g.e.isRemoved());
        if (l.isEmpty()) {
            LEVELS.remove(level);
            return;
        }
        long now = level.getGameTime();
        if (trace) for (Live g : l) follow(level, g);
        for (Live g : l) if (g.at == Long.MIN_VALUE || now - g.at >= (g.e instanceof com.hbm.entity.projectile.EntityBulletBaseMK4 ? 1 : AGAIN)) {
            g.at = now;
            g.burst = burstOf(level, g.e);
        }
    }

    /** where this grenade will burst; null with a fuze other than a timer or impact (or out of the world) */
    private static @Nullable Vec3 burstOf(ServerLevel level, net.minecraft.world.entity.projectile.Projectile p) {
        Vec3 v = p.getDeltaMovement();
        if (p instanceof com.hbm.entity.grenade.EntityGrenadeBouncyGeneric b) {
            // its own timer, as many ticks as it has been in the level (saved with it, but near enough); gravity 0.03
            int left = b.getGrenade().getMaxTimer() - b.tickCount;
            if (left <= 0) return b.position();
            return burst(AiBots.voxels(level), b.getX(), b.getY(), b.getZ(), v.x, v.y, v.z, left, -1, b.getGrenade().getBounceMod(), level.getMinY());
        }
        if (p instanceof com.hbm.entity.projectile.EntityBulletBaseMK4 b) {
            // fired: bursts where it first hits; a tick it goes its speed times the round's, gravity on the speed (no air)
            if (b.config == null) return null;
            double m = b.config.velocity + b.accel;
            int left = b.config.expires > 0 ? Math.min(200, b.config.expires - b.tickCount) : 200;
            if (left <= 0) return b.position();
            return burst(AiBots.voxels(level), b.getX(), b.getY(), b.getZ(), v.x * m, v.y * m, v.z * m, left, 0, 0, level.getMinY(), b.config.gravity * m, 1);
        }
        if (!(p instanceof EntityGrenadeUniversal e)) return null;
        EnumGrenadeFuze f = e.getFuze();
        int left = switch (f) {
            case S3 -> 60;
            case S7 -> 140;
            case S15 -> 300;
            case IMPACT -> 200;
            default -> -1;
        };
        if (left < 0) return null;
        if (f != EnumGrenadeFuze.IMPACT) left -= e.getTimer();
        if (left <= 0) return e.position();
        if (e.getExtra() == com.hbm.items.weapon.grenade.ItemGrenadeExtra.EnumGrenadeExtra.GLUE) {
            // glue: it sticks where it first hits, and bursts there
            double[] land = new double[3];
            Vec3 b = burst(AiBots.voxels(level), e.getX(), e.getY(), e.getZ(), v.x, v.y, v.z, left, -1, e.getShell().getBounce(), level.getMinY(), GRAVITY, DRAG, null, land);
            return Double.isNaN(land[0]) ? b : new Vec3(land[0], land[1], land[2]);
        }
        return burst(AiBots.voxels(level), e.getX(), e.getY(), e.getZ(), v.x, v.y, v.z, left,
                f == EnumGrenadeFuze.IMPACT ? Math.max(0, IMPACT_ARMED - e.getTimer()) : -1, e.getShell().getBounce(), level.getMinY());
    }

    /** a burst to run from, how far off it is out of its reach, and whether it is its own side's (no harm at all) */
    record Danger(Vec3 burst, double clear, boolean own) {
    }

    /**
     * The burst nearest this mob within so many blocks of it, of a grenade it knows of: one it has seen (in plain sight
     * within SEE of its eye) or heard land (within HEAR), or one of its own side's (it saw it thrown; its own come
     * near the place it was thrown at, so it keeps out of all its reach: SAFE and OWN_MARGIN); null if none.
     */
    static @Nullable Danger danger(ServerLevel level, AiBot bot, double within) {
        long t0 = System.nanoTime();
        try {
            return dangerNow(level, bot, within);
        } finally {
            liveNanos += System.nanoTime() - t0;
        }
    }

    private static @Nullable Danger dangerNow(ServerLevel level, AiBot bot, double within) {
        if (!dodgeOn) return null;
        List<Live> l = LEVELS.get(level);
        if (l == null) return null;
        Mob m = bot.mob;
        Vec3 eye = m.getEyePosition();
        Danger near = null;
        double best = Double.MAX_VALUE;
        VoxelRay.Source src = AiBots.voxels(level);
        for (Live g : l) {
            if (g.burst == null || g.e.isRemoved()) continue;
            boolean own = ownSide(m, g);
            // the grenade thrown into a room before an entry, it behind the wall beside the way in (or out of its reach): it stays
            if (own && AiEntry.keeps(level, bot, g.e.getId(), g.burst, src)) continue;
            // the one thrown in through a bunker's slit, it beside the slit behind the wall (or out of its reach): it stays
            if (own && AiBunker.keeps(level, bot, g.e.getId(), g.burst, src)) continue;
            // the one dropped down a shaft, it up out of the shaft: it stays
            if (own && AiHatch.keeps(level, bot, g.e.getId())) continue;
            // its own near throw, it down behind its cover till the burst: it stays down while blocks keep all of it
            // off the burst (worked out again as the grenade flies); else it runs as from any of its own side's
            if (own && g.e.getOwner() == m && bot.brain instanceof AiSoldier s && s.downFor(g.e) && s.downShield(bot, level.getGameTime()) > 0
                    && shielded(src, m.getX(), m.getY(), m.getZ(), s.downShield(bot, level.getGameTime()), g.burst)) continue;
            if (trace && own && g.e.getOwner() == m && bot.brain instanceof AiSoldier s && s.downFor(g.e) && !s.throwNote.contains("; runs"))
                s.throwNote += (s.downShield(bot, level.getGameTime()) > 0 ? "; runs: not hidden from the burst" : "; runs: not down") + s.downWhy(bot, level.getGameTime());
            // its own side's: out to SAFE and OWN_MARGIN (as far as a throw keeps it from its own), 2 more once it runs
            // (no back and forth); it runs to a block past that (OWN_CLEAR)
            double r = own ? SAFE + OWN_MARGIN + (within > DANGER ? 2 : 0) : within;
            double d = m.distanceToSqr(g.burst);
            // its own side's: where it is on its way to as well (one went to other cover there after the throw and was
            // hit, too late to turn back: the battles)
            long goal = bot.mover.goal();
            if (own && goal != Long.MIN_VALUE && !bot.mover.arrived())
                d = Math.min(d, g.burst.distanceToSqr(BlockPos.getX(goal) + 0.5, BlockPos.getY(goal), BlockPos.getZ(goal) + 0.5));
            if (d >= r * r || d >= best) continue;
            int id = m.getId();
            if (!own && !g.known.contains(id)) {
                double e2 = g.e.position().distanceToSqr(eye);
                boolean knows = e2 <= HEAR * HEAR || e2 <= SEE * SEE && VoxelRay.clear(src, eye.x, eye.y, eye.z, g.e.getX(), g.e.getY(), g.e.getZ(), Voxel.SIGHT);
                if (!knows) continue;
                g.known.add(id);
            }
            best = d;
            near = new Danger(g.burst, own ? OWN_CLEAR : CLEAR, own);
        }
        return near;
    }

    /** its own side's grenade (it threw it, or one of its side) */
    private static boolean ownSide(Mob m, Live g) {
        return g.e.getOwner() instanceof net.minecraft.world.entity.LivingEntity o && (o == m || AiTeams.own(m, o));
    }

    /** a place in the reach of one of these bursts: its own side's out to OTHER_OWN, others' out to CLEAR unless behind a block */
    private static boolean inOthers(VoxelRay.Source src, Mob m, List<Live> others, double x, double y, double z) {
        for (Live g : others) {
            boolean own = ownSide(m, g);
            double r = own ? OTHER_OWN : CLEAR;
            if (g.burst.distanceToSqr(x, y, z) < r * r && !cover(src, g.burst, x, y, z, own)) return true;
        }
        return false;
    }

    /** whether any grenade is in the level (cheap: most ticks there is none) */
    static boolean any(ServerLevel level) {
        List<Live> l = LEVELS.get(level);
        return l != null && !l.isEmpty();
    }

    /**
     * Where to run from a burst here: the nearest cell (up to 17 blocks off, round it; 20 from its own side's) clear
     * off it or behind a block from it (cover), none tried already, none nearer the burst unless behind a block, none in
     * the reach of another burst it knows of (its own side's: OTHER_OWN; others': CLEAR, unless behind a block from it)
     * if there is one such; Long.MIN_VALUE if none is.
     */
    static long refuge(ServerLevel level, Mob m, Vec3 burst, double clear, boolean own, it.unimi.dsi.fastutil.longs.LongSet tried) {
        long t0 = System.nanoTime();
        try {
            return refugeNow(level, m, burst, clear, own, tried);
        } finally {
            liveNanos += System.nanoTime() - t0;
        }
    }

    private static final double[] RINGS = {2.5, 4.5, 6.5, 9, 11.5, 14, 17}, OWN_RINGS = {2.5, 4.5, 6.5, 9, 11.5, 14, 17, 20};

    private static long refugeNow(ServerLevel level, Mob m, Vec3 burst, double clear, boolean own, it.unimi.dsi.fastutil.longs.LongSet tried) {
        TacticalMap map = TacticalMap.of(level);
        VoxelRay.Source src = AiBots.voxels(level);
        List<Live> others = others(level, src, m, burst);
        long cell = rings(map, src, m, burst, clear, own, tried, others);
        // none out of the others' reach as well: out of this one's, as if there were no others
        return cell == Long.MIN_VALUE && !others.isEmpty() ? rings(map, src, m, burst, clear, own, tried, List.of()) : cell;
    }

    /** the bursts other than this one it knows of: its own side's (all of them), others' it sees or hears now or did */
    private static List<Live> others(ServerLevel level, VoxelRay.Source src, Mob m, Vec3 burst) {
        List<Live> others = new ArrayList<>();
        List<Live> l = LEVELS.get(level);
        if (l != null) for (Live g : l) {
            if (g.burst == null || g.e.isRemoved() || g.burst == burst) continue;
            if (ownSide(m, g) || knows(src, m, g)) others.add(g);
        }
        return others;
    }

    /** whether the refuge it runs to from this burst is in the reach of another it knows of now (one thrown since) */
    static boolean othersReach(ServerLevel level, Mob m, Vec3 burst, long cell) {
        VoxelRay.Source src = AiBots.voxels(level);
        List<Live> others = others(level, src, m, burst);
        return !others.isEmpty() && inOthers(src, m, others, BlockPos.getX(cell) + 0.5, BlockPos.getY(cell), BlockPos.getZ(cell) + 0.5);
    }

    /** whether the mob knows of this grenade: seen (in plain sight within SEE) or heard (within HEAR) now or before */
    private static boolean knows(VoxelRay.Source src, Mob m, Live g) {
        int id = m.getId();
        if (g.known.contains(id)) return true;
        Vec3 eye = m.getEyePosition();
        double e2 = g.e.position().distanceToSqr(eye);
        if (e2 > HEAR * HEAR && (e2 > SEE * SEE || !VoxelRay.clear(src, eye.x, eye.y, eye.z, g.e.getX(), g.e.getY(), g.e.getZ(), Voxel.SIGHT))) return false;
        g.known.add(id);
        return true;
    }

    private static long rings(TacticalMap map, VoxelRay.Source src, Mob m, Vec3 burst, double clear, boolean own, it.unimi.dsi.fastutil.longs.LongSet tried,
            List<Live> others) {
        long here = m.blockPosition().asLong(), best = Long.MIN_VALUE;
        double bestScore = Double.MAX_VALUE;
        double away = Math.atan2(m.getZ() - burst.z, m.getX() - burst.x);
        // its own side's: one more ring, OWN_CLEAR off the burst being past the last from a place by it
        for (double far : own ? OWN_RINGS : RINGS) {
            for (int k = 0; k < 16; k++) {
                double a = away + Math.PI * 2 * k / 16;
                long cell = PathSearch.snap(map, m.getX() + Math.cos(a) * far, m.getY(), m.getZ() + Math.sin(a) * far);
                if (cell == Long.MIN_VALUE || cell == here || tried.contains(cell)) continue;
                double cx = BlockPos.getX(cell) + 0.5, cy = BlockPos.getY(cell), cz = BlockPos.getZ(cell) + 0.5;
                double dx = cx - burst.x, dy = cy - burst.y, dz = cz - burst.z;
                double d2 = dx * dx + dy * dy + dz * dz;
                boolean out = d2 >= clear * clear;
                // behind a block: in the burst's reach all of it (cover); out of it only for the score, one ray will do
                boolean behind = out ? !own && behind(src, burst, cx, cy, cz) : cover(src, burst, cx, cy, cz, own);
                if (!out && !behind) continue;
                if (inOthers(src, m, others, cx, cy, cz)) continue;
                // not past the burst to get there: no nearer to it than it is now unless behind a block from it
                if (!behind && d2 < m.distanceToSqr(burst)) continue;
                // the way there: the shorter the better; behind a block a little better still
                double score = Math.hypot(cx - m.getX(), cz - m.getZ()) - (behind ? 1.5 : 0);
                if (score < bestScore) {
                    bestScore = score;
                    best = cell;
                }
            }
            if (best != Long.MIN_VALUE) return best;
        }
        return best;
    }
}
