package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;

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

import net.minecraft.core.BlockPos;
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
        @Override public @Nullable BurstObserver flightObserver() { return alike; }
        @Override public Object burstRef(Body body, acup.gunfeel.Vec3d burst, boolean position) {
            return position ? EntityBody.entity(body).position() : new Vec3(burst.x(), burst.y(), burst.z());
        }
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
        tossed(level, m, g, t);
        return g;
    }

    static void tossed(ServerLevel level, Mob m, EntityGrenadeUniversal g, Throw t) {
        if (alike != null) alike.before(level);
        AiGrenadesBase.tossed(STAFF, GUNS, level, EntityBody.of(m), EntityBody.of(g), t.yaw(), t.pitch(), t.share());
        if (alike != null) alike.tossed(level, m, g, t);
    }

    static @Nullable String parted(ServerLevel level, net.minecraft.world.entity.@Nullable Entity owner) {
        String answer = AiGrenadesBase.parted(level, owner == null ? null : EntityBody.of(owner));
        return alike == null ? answer : alike.parted(level, owner, answer);
    }

    private static net.minecraft.world.entity.projectile.Projectile entity(Live live) {
        return (net.minecraft.world.entity.projectile.Projectile) EntityBody.entity(live.e);
    }

    private static @Nullable Vec3 vector(Live live) { return (Vec3) live.burstRef; }

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

    static Vec3 @Nullable [] bursting(ServerLevel level, net.minecraft.world.entity.@Nullable Entity owner) {
        Object[] result = AiGrenadesBase.bursting(STAFF, level, owner == null ? null : EntityBody.of(owner));
        Vec3[] answer = result == null ? null : new Vec3[] {(Vec3) result[0], (Vec3) result[1]};
        return alike == null ? answer : alike.bursting(level, owner, answer);
    }

    static @Nullable Vec3 predicted(ServerLevel level, net.minecraft.world.entity.Entity e) {
        Vec3 answer = (Vec3) AiGrenadesBase.predicted(level, EntityBody.of(e));
        return alike == null ? answer : alike.predicted(level, e, answer);
    }

    static void joined(ServerLevel level, net.minecraft.world.entity.projectile.Projectile g) {
        if (alike != null) alike.before(level);
        AiGrenadesBase.joined(level, EntityBody.of(g));
        if (alike != null) alike.joined(level, g);
    }

    /** A forced live entry for the level-unload stand, without spawning. */
    public static void leakProbe(ServerLevel level, net.minecraft.world.entity.projectile.Projectile g) {
        joined(level, g);
    }

    public static void forget(ServerLevel level) {
        if (alike != null) alike.before(level);
        AiGrenadesBase.forgetLevel(level);
        if (alike != null) alike.forgot(level);
    }

    static void tick(ServerLevel level) {
        if (alike != null) alike.before(level);
        AiGrenadesBase.tick(STAFF, GUNS, level);
        if (alike != null) alike.ticked(level);
    }

    /** Pure flight entry for the stands. */
    static @Nullable Vec3 burstOf(ServerLevel level, net.minecraft.world.entity.projectile.Projectile p) {
        Live live = new Live(EntityBody.of(p));
        var burst = AiGrenadesBase.burstOf(STAFF, GUNS, level, live);
        Vec3 answer = burst == null ? null : (Vec3) STAFF.burstRef(live.e, burst, live.position);
        return alike == null ? answer : alike.burstOf(level, p, answer);
    }

    /** API reads stay in the AI's category. */
    public static int liveKind(Body p) {
        var e = EntityBody.entity(p);
        if (e instanceof com.hbm.entity.grenade.EntityGrenadeBouncyGeneric) return NtmGuns.BOUNCY;
        if (e instanceof com.hbm.entity.projectile.EntityBulletBaseMK4) return NtmGuns.MK4;
        return e instanceof EntityGrenadeUniversal ? NtmGuns.UNIVERSAL : NtmGuns.OTHER;
    }

    public static int grenadeMaxTimer(Body p) {
        return ((com.hbm.entity.grenade.EntityGrenadeBouncyGeneric) EntityBody.entity(p)).getGrenade().getMaxTimer();
    }

    public static double grenadeBounce(Body p) {
        var e = EntityBody.entity(p);
        return e instanceof com.hbm.entity.grenade.EntityGrenadeBouncyGeneric b ? b.getGrenade().getBounceMod()
                : ((EntityGrenadeUniversal) e).getShell().getBounce();
    }

    public static int grenadeFuze(Body p) {
        return switch (((EntityGrenadeUniversal) EntityBody.entity(p)).getFuze()) {
            case S3 -> NtmGuns.FUZE_S3;
            case S7 -> NtmGuns.FUZE_S7;
            case S15 -> NtmGuns.FUZE_S15;
            case IMPACT -> NtmGuns.FUZE_IMPACT;
            default -> -1;
        };
    }

    public static int grenadeTimer(Body p) { return ((EntityGrenadeUniversal) EntityBody.entity(p)).getTimer(); }
    public static boolean grenadeGlue(Body p) {
        return ((EntityGrenadeUniversal) EntityBody.entity(p)).getExtra() == com.hbm.items.weapon.grenade.ItemGrenadeExtra.EnumGrenadeExtra.GLUE;
    }
    public static boolean mk4Config(Body p, acup.gunfeel.host.Shell into) {
        var config = ((com.hbm.entity.projectile.EntityBulletBaseMK4) EntityBody.entity(p)).config;
        if (config == null) return false;
        into.velocity = config.velocity;
        into.gravity = config.gravity;
        into.expires = config.expires;
        return true;
    }
    public static double mk4Accel(Body p) { return ((com.hbm.entity.projectile.EntityBulletBaseMK4) EntityBody.entity(p)).accel; }

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
            if (vector(g) == null || g.e.removed()) continue;
            boolean own = ownSide(m, g);
            // the grenade thrown into a room before an entry, it behind the wall beside the way in (or out of its reach): it stays
            if (own && AiEntry.keeps(level, bot, g.e.id(), vector(g), src)) continue;
            // the one thrown in through a bunker's slit, it beside the slit behind the wall (or out of its reach): it stays
            if (own && AiBunker.keeps(level, bot, g.e.id(), vector(g), src)) continue;
            // the one dropped down a shaft, it up out of the shaft: it stays
            if (own && AiHatch.keeps(level, bot, g.e.id())) continue;
            // its own near throw, it down behind its cover till the burst: it stays down while blocks keep all of it
            // off the burst (worked out again as the grenade flies); else it runs as from any of its own side's
            if (own && entity(g).getOwner() == m && bot.brain instanceof AiSoldier s && s.downFor(entity(g)) && s.downShield(bot, level.getGameTime()) > 0
                    && shielded(src, m.getX(), m.getY(), m.getZ(), s.downShield(bot, level.getGameTime()), vector(g))) continue;
            if (trace && own && entity(g).getOwner() == m && bot.brain instanceof AiSoldier s && s.downFor(entity(g)) && !s.throwNote.contains("; runs"))
                s.throwNote += (s.downShield(bot, level.getGameTime()) > 0 ? "; runs: not hidden from the burst" : "; runs: not down") + s.downWhy(bot, level.getGameTime());
            // its own side's: out to SAFE and OWN_MARGIN (as far as a throw keeps it from its own), 2 more once it runs
            // (no back and forth); it runs to a block past that (OWN_CLEAR)
            double r = own ? SAFE + OWN_MARGIN + (within > DANGER ? 2 : 0) : within;
            double d = m.distanceToSqr(vector(g));
            // its own side's: where it is on its way to as well (one went to other cover there after the throw and was
            // hit, too late to turn back: the battles)
            long goal = bot.mover.goal();
            if (own && goal != Long.MIN_VALUE && !bot.mover.arrived())
                d = Math.min(d, vector(g).distanceToSqr(BlockPos.getX(goal) + 0.5, BlockPos.getY(goal), BlockPos.getZ(goal) + 0.5));
            if (d >= r * r || d >= best) continue;
            int id = m.getId();
            if (!own && !g.known.contains(id)) {
                double e2 = entity(g).position().distanceToSqr(eye);
                boolean knows = e2 <= HEAR * HEAR || e2 <= SEE * SEE && VoxelRay.clear(src, eye.x, eye.y, eye.z, g.e.x(), g.e.y(), g.e.z(), Voxel.SIGHT);
                if (!knows) continue;
                g.known.add(id);
            }
            best = d;
            near = new Danger(vector(g), own ? OWN_CLEAR : CLEAR, own);
        }
        return near;
    }

    /** its own side's grenade (it threw it, or one of its side) */
    private static boolean ownSide(Mob m, Live g) {
        return entity(g).getOwner() instanceof net.minecraft.world.entity.LivingEntity o && (o == m || AiTeams.own(m, o));
    }

    /** a place in the reach of one of these bursts: its own side's out to OTHER_OWN, others' out to CLEAR unless behind a block */
    private static boolean inOthers(VoxelRay.Source src, Mob m, List<Live> others, double x, double y, double z) {
        for (Live g : others) {
            boolean own = ownSide(m, g);
            double r = own ? OTHER_OWN : CLEAR;
            if (vector(g).distanceToSqr(x, y, z) < r * r && !cover(src, vector(g), x, y, z, own)) return true;
        }
        return false;
    }

    /** whether any grenade is in the level (cheap: most ticks there is none) */
    static boolean any(ServerLevel level) {
        boolean answer = AiGrenadesBase.any(level);
        return alike == null ? answer : alike.any(level, answer);
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
            if (vector(g) == null || g.e.removed() || AiGrenadesBase.sameBurst(g, burst)) continue;
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
        double e2 = entity(g).position().distanceToSqr(eye);
        if (e2 > HEAR * HEAR && (e2 > SEE * SEE || !VoxelRay.clear(src, eye.x, eye.y, eye.z, g.e.x(), g.e.y(), g.e.z(), Voxel.SIGHT))) return false;
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
