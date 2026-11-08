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

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Grenades, NTM's own: a frag with HE and a three seconds' fuze. An AI mob carries a few (server setting aiGrenades,
 * 2 by default, 0 to 8, kept on the mob) and throws one at a target it lost behind cover a moment ago: over the cover,
 * to burst within {@link #LAND} blocks of where the target went out of sight, never within {@link #SAFE} of itself or
 * one of its own. Where it bursts is worked out here as NTM flies it ({@link #burst}), bounces and all, on the AI's
 * block bytes. And an AI mob that sees a grenade come (anyone's: NTM's grenades that a fuze sets off), or hears one
 * land near, runs from where it will burst: out of its reach, or behind a block. Server thread.
 */
public final class AiGrenades {
    public static final String KEY = "aiGrenades";
    /** where its grenades left are kept on the mob */
    static final String STOCK_KEY = "armsaiGrenades";
    /** throws (off only for the stands' "before") */
    static boolean on = true;
    /** runs from grenades (off only for the stands' "before") */
    static boolean dodgeOn = true;
    /** thrown at a place this far off, blocks */
    static final double NEAR = 15, FAR = 26;
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

    private AiGrenades() {
    }

    /** the bench: time spent working out throws, and on grenades in the air (bursts, dangers, refuges), ns */
    public static long planNanos, liveNanos;

    /** the game tick a throw was last worked out on in each level: one a tick at most there, whoever asks */
    private static final Map<ServerLevel, Long> PLANNED = new WeakHashMap<>();

    /** whether a throw may be worked out this tick in this level (one a tick, for all its AI mobs) */
    static boolean mayPlan(ServerLevel level, long tick) {
        Long last = PLANNED.put(level, tick);
        return last == null || last != tick;
    }

    /** refuges looked for in a level this tick, and the tick: a few a tick at most (a grenade in a crowd) */
    private static final Map<ServerLevel, long[]> REFUGES = new WeakHashMap<>();
    static final int REFUGES_A_TICK = 6;

    /** whether one more refuge may be looked for this tick in this level */
    static boolean mayRefuge(ServerLevel level, long tick) {
        long[] r = REFUGES.computeIfAbsent(level, k -> new long[] {Long.MIN_VALUE, 0});
        if (r[0] != tick) {
            r[0] = tick;
            r[1] = 0;
        }
        return r[1]++ < REFUGES_A_TICK;
    }

    /** a block between the burst and this place (a mob's chest there): the blast is stopped */
    static boolean behind(VoxelRay.Source src, Vec3 burst, double x, double y, double z) {
        return !VoxelRay.clear(src, burst.x, burst.y + 0.3, burst.z, x, y + 0.9, z, Voxel.MOVE_MASK);
    }

        /** grenades a mob new to the AI carries */
    static int stock() {
        return Math.max(0, Math.min(8, Math.round(acup.gunfeel.ReaimTimes.number(KEY, 2F))));
    }

    /** a throw: its turn, and where it bursts */
    record Throw(float yaw, float pitch, Vec3 burst) {
    }

    /**
     * How to throw at this place from where the mob stands: the pitch (turned to the place) at which NTM's frag bursts
     * nearest it, within LAND of it; null if none does, or one of its own (or it) would be within SAFE of the burst.
     */
    static @Nullable Throw plan(ServerLevel level, Mob m, Vec3 at) {
        long t0 = System.nanoTime();
        try {
            return planNow(level, m, at);
        } finally {
            planNanos += System.nanoTime() - t0;
        }
    }

    private static @Nullable Throw planNow(ServerLevel level, Mob m, Vec3 at) {
        double dx = at.x - m.getX(), dz = at.z - m.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90F;
        double force = EnumGrenadeShell.FRAG.getYeetForce(), bounce = EnumGrenadeShell.FRAG.getBounce();
        VoxelRay.Source src = AiBots.voxels(level);
        int minY = level.getMinY();
        float best = Float.NaN;
        double bestD = Double.MAX_VALUE;
        List<Throw> near = new ArrayList<>();
        for (float p = PITCH_UP; p <= PITCH_DOWN; p += PITCH_STEP) {
            Vec3 b = from(src, m, yaw, p, force, bounce, minY);
            if (b == null) continue;
            near.add(new Throw(yaw, p, b));
            if (b.distanceTo(at) < bestD) {
                bestD = b.distanceTo(at);
                best = p;
            }
        }
        if (Float.isNaN(best)) return null;
        for (float step = PITCH_STEP / 2; step >= PITCH_STEP / 4; step /= 2) {
            float mid = best;
            for (float p : new float[] {mid - step, mid + step}) {
                Vec3 b = from(src, m, yaw, p, force, bounce, minY);
                if (b == null) continue;
                near.add(new Throw(yaw, p, b));
                if (b.distanceTo(at) < bestD) {
                    bestD = b.distanceTo(at);
                    best = p;
                }
            }
        }
        // the nearest to the place of those within LAND that burst far from its own
        near.sort(java.util.Comparator.comparingDouble(t -> t.burst().distanceToSqr(at)));
        for (Throw t : near) {
            if (t.burst().distanceTo(at) > LAND) break;
            if (!ownNear(level, m, t.burst()) && steady(level, src, m, t, at, force, bounce, minY)) return t;
        }
        return null;
    }

    /** a start this far off NTM's each way: the grenade's box, not its middle, bumps into a block's edge */
    static final double BOX = 0.3;

    /**
     * Whether the throw holds started a box's width off each way (the grenade's box clips a corner its middle passes:
     * the battles, one burst 22 blocks short, among the thrower's own): each such flight bursts near the place too
     * (LAND and 2 more), none near its own.
     */
    private static boolean steady(ServerLevel level, VoxelRay.Source src, Mob m, Throw t, Vec3 at, double force, double bounce, int minY) {
        double[][] off = {{BOX, 0, 0}, {-BOX, 0, 0}, {0, BOX, 0}, {0, -BOX, 0}, {0, 0, BOX}, {0, 0, -BOX}};
        for (double[] o : off) {
            Vec3 b = from(src, m, t.yaw(), t.pitch(), force, bounce, minY, o[0], o[1], o[2]);
            if (b == null || b.distanceTo(at) > LAND + 2 || ownNear(level, m, b)) return false;
        }
        return true;
    }

    private static @Nullable Vec3 from(VoxelRay.Source src, Mob m, float yaw, float pitch, double force, double bounce, int minY) {
        return from(src, m, yaw, pitch, force, bounce, minY, 0, 0, 0);
    }

    /** where a grenade thrown by the mob with this turn bursts (NTM's start: by its eye, a quarter block off; moved by o) */
    private static @Nullable Vec3 from(VoxelRay.Source src, Mob m, float yaw, float pitch, double force, double bounce, int minY, double ox, double oy,
            double oz) {
        double r = Math.toRadians(180F - yaw);
        double x = m.getX() + 0.25 * Math.cos(r) + ox, y = m.getY() + m.getEyeHeight() - 0.25 + oy, z = m.getZ() - 0.25 * Math.sin(r) + oz;
        double f = Math.toRadians(pitch), g = Math.toRadians(-yaw);
        return burst(src, x, y, z, Math.sin(g) * Math.cos(f) * force, -Math.sin(f) * force, Math.cos(g) * Math.cos(f) * force, FUSE, -1, bounce, minY);
    }

    /**
     * It within SAFE and OWN_MARGIN of the burst (out of the reach it runs from its own grenade in), or one of its own
     * (an AI mob, a player of its team) within that too: where it is, where it is on its way to, the peek it looks out
     * from.
     */
    private static boolean ownNear(ServerLevel level, Mob m, Vec3 b) {
        if (m.distanceToSqr(b) < (SAFE + OWN_MARGIN) * (SAFE + OWN_MARGIN)) return true;
        double s2 = (SAFE + OWN_MARGIN) * (SAFE + OWN_MARGIN);
        for (AiBot o : AiBots.bots(level)) {
            if (o.mob == m || !o.mob.isAlive() || !AiTeams.own(m, o.mob)) continue;
            if (o.mob.distanceToSqr(b) < s2 || near(o.mover.goal(), b, s2)) return true;
            if (o.brain instanceof AiSoldier s && s.spot() != null && (near(s.spot().cell(), b, s2) || s.spot().hasPeek() && near(s.spot().peek(), b, s2))) return true;
        }
        for (Player p : level.players())
            if (!p.isSpectator() && p.isAlive() && AiTeams.own(m, p) && p.distanceToSqr(b) < s2) return true;
        return false;
    }

    private static boolean near(long cell, Vec3 b, double d2) {
        return cell != Long.MIN_VALUE && b.distanceToSqr(BlockPos.getX(cell) + 0.5, BlockPos.getY(cell), BlockPos.getZ(cell) + 0.5) < d2;
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
    static @Nullable Vec3 burst(VoxelRay.Source src, double x, double y, double z, double vx, double vy, double vz, int ticks, int armedAfter,
            double bounce, int minY) {
        return burst(src, x, y, z, vx, vy, vz, ticks, armedAfter, bounce, minY, GRAVITY, DRAG);
    }

    /** the same with other gravity and air (a tick's speed taken off, a tick's speed left) */
    static @Nullable Vec3 burst(VoxelRay.Source src, double x, double y, double z, double vx, double vy, double vz, int ticks, int armedAfter,
            double bounce, int minY, double gravity, double drag) {
        double[] hit = new double[2];
        boolean landed = false;
        for (int t = 0; t < ticks; t++) {
            if (y < minY) return null;
            if (first(src, x, y, z, x + vx, y + vy, z + vz, hit)) {
                double s = hit[0];
                int axis = (int) hit[1];
                double hx = x + vx * s, hy = y + vy * s, hz = z + vz * s;
                if (armedAfter >= 0 && t + 1 >= armedAfter) return new Vec3(hx, hy, hz);
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
            if (landed && vx * vx + vz * vz < 1e-5 && Math.abs(vy) < 2 * gravity) return new Vec3(x, y, z);
            vx *= drag;
            vy = vy * drag - gravity;
            vz *= drag;
        }
        return new Vec3(x, y, z);
    }

    /**
     * The first block a mob bumps into on the line (the block the line starts in not asked): out[0] how far along
     * (0..1), out[1] the axis of the face it comes in by (0 x, 1 y, 2 z). Whether there is one.
     */
    private static boolean first(VoxelRay.Source src, double x0, double y0, double z0, double x1, double y1, double z1, double[] out) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        int x = net.minecraft.util.Mth.floor(x0), y = net.minecraft.util.Mth.floor(y0), z = net.minecraft.util.Mth.floor(z0);
        int ex = net.minecraft.util.Mth.floor(x1), ey = net.minecraft.util.Mth.floor(y1), ez = net.minecraft.util.Mth.floor(z1);
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

    /** NTM's frag with HE and a three seconds' fuze, thrown by the mob with this turn */
    static void toss(ServerLevel level, Mob m, Throw t) {
        m.setYRot(t.yaw());
        m.setYHeadRot(t.yaw());
        m.setYBodyRot(t.yaw());
        m.setXRot(t.pitch());
        EntityGrenadeUniversal g = new EntityGrenadeUniversal(level, m, ItemGrenadeUniversal.make(EnumGrenadeShell.FRAG, EnumGrenadeFilling.HE, EnumGrenadeFuze.S3));
        level.addFreshEntity(g);
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

        Live(net.minecraft.world.entity.projectile.Projectile e) {
            this.e = e;
        }
    }

    /**
     * Whether this is a grenade to run from: NTM's grenade (any shell and filling), its bouncing ones (dynamite, the
     * old grenades), a 40 mm grenade or a rocket fired from a gun (the GL-54, Congo Lake, MK108, rocket launchers). Not
     * a nuke's: no running from that.
     */
    public static boolean threat(net.minecraft.world.entity.Entity e) {
        return e instanceof EntityGrenadeUniversal || e instanceof com.hbm.entity.grenade.EntityGrenadeBouncyGeneric
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
        PLANNED.remove(level);
        REFUGES.remove(level);
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
        return burst(AiBots.voxels(level), e.getX(), e.getY(), e.getZ(), v.x, v.y, v.z, left,
                f == EnumGrenadeFuze.IMPACT ? Math.max(0, IMPACT_ARMED - e.getTimer()) : -1, e.getShell().getBounce(), level.getMinY());
    }

    /** a burst to run from, and how far off it is out of its reach */
    record Danger(Vec3 burst, double clear) {
    }

    /**
     * The burst nearest this mob within so many blocks of it, of a grenade it knows of: one it has seen (in plain sight
     * within SEE of its eye) or heard land (within HEAR), or one of its own side's (it saw it thrown; its own come
     * near the place it was thrown at, so it keeps out of all its reach: SAFE); null if none.
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
            boolean own = g.e.getOwner() instanceof net.minecraft.world.entity.LivingEntity o && (o == m || AiTeams.own(m, o));
            // its own side's: out to a block past SAFE (the blast's edge), 2 more once it runs (no back and forth)
            double r = own ? SAFE + 1 + (within > DANGER ? 2 : 0) : within;
            double d = m.distanceToSqr(g.burst);
            if (d >= r * r || d >= best) continue;
            int id = m.getId();
            if (!own && !g.known.contains(id)) {
                double e2 = g.e.position().distanceToSqr(eye);
                boolean knows = e2 <= HEAR * HEAR || e2 <= SEE * SEE && VoxelRay.clear(src, eye.x, eye.y, eye.z, g.e.getX(), g.e.getY(), g.e.getZ(), Voxel.SIGHT);
                if (!knows) continue;
                g.known.add(id);
            }
            best = d;
            near = new Danger(g.burst, own ? SAFE + 2 : CLEAR);
        }
        return near;
    }

    /** whether any grenade is in the level (cheap: most ticks there is none) */
    static boolean any(ServerLevel level) {
        List<Live> l = LEVELS.get(level);
        return l != null && !l.isEmpty();
    }

    /**
     * Where to run from a burst here: the nearest cell (up to 17 blocks off, round it) clear off it or behind a
     * block from it, none tried already (out of reach); Long.MIN_VALUE if none is.
     */
    /** whether this cell is out of the burst's reach (clear) or behind a block from it */
    static boolean safe(VoxelRay.Source src, Vec3 burst, long cell, double clear) {
        double cx = BlockPos.getX(cell) + 0.5, cy = BlockPos.getY(cell), cz = BlockPos.getZ(cell) + 0.5;
        return burst.distanceToSqr(cx, cy, cz) >= clear * clear || behind(src, burst, cx, cy, cz);
    }

    static long refuge(ServerLevel level, Mob m, Vec3 burst, double clear, it.unimi.dsi.fastutil.longs.LongSet tried) {
        long t0 = System.nanoTime();
        try {
            return refugeNow(level, m, burst, clear, tried);
        } finally {
            liveNanos += System.nanoTime() - t0;
        }
    }

    private static long refugeNow(ServerLevel level, Mob m, Vec3 burst, double clear, it.unimi.dsi.fastutil.longs.LongSet tried) {
        TacticalMap map = TacticalMap.of(level);
        VoxelRay.Source src = AiBots.voxels(level);
        long here = m.blockPosition().asLong(), best = Long.MIN_VALUE;
        double bestScore = Double.MAX_VALUE;
        double away = Math.atan2(m.getZ() - burst.z, m.getX() - burst.x);
        for (double far : new double[] {2.5, 4.5, 6.5, 9, 11.5, 14, 17}) {
            for (int k = 0; k < 16; k++) {
                double a = away + Math.PI * 2 * k / 16;
                long cell = PathSearch.snap(map, m.getX() + Math.cos(a) * far, m.getY(), m.getZ() + Math.sin(a) * far);
                if (cell == Long.MIN_VALUE || cell == here || tried.contains(cell)) continue;
                double cx = BlockPos.getX(cell) + 0.5, cy = BlockPos.getY(cell), cz = BlockPos.getZ(cell) + 0.5;
                double dx = cx - burst.x, dy = cy - burst.y, dz = cz - burst.z;
                double d2 = dx * dx + dy * dy + dz * dz;
                boolean out = d2 >= clear * clear;
                boolean behind = behind(src, burst, cx, cy, cz);
                if (!out && !behind) continue;
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
