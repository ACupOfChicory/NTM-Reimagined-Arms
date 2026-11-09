package acup.gunfeel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Server: a player's bullet hits the mobs and players its shooter's client saw it hit (clientHits in
 * ntm-gunfeel-server.properties, on by default). The client tests its own bullet's path against the models it draws
 * and reports each hit: the mob, the part, the point, the bullet's age there (HitReportPayload). The server takes the
 * hit there, on that part, and no other mob on the way: no test of its own by the poses it has. Its checks are against
 * mistakes, not cheats: the mob is alive and in the bullet's world, the bullet is this player's and still flies, the
 * point is within the bullet's reach, and a bullet hits a mob once (buckshot: each pellet is its own bullet). The damage,
 * the armour, the part's multiplier, piercing and flying on are NTM's and the mod's as with any hit.
 * <p>
 * The report comes a ping after the bullet is there. A bullet near mobs waits for it, up to the ping and five ticks
 * (at most a second); a bullet whose report has not come by then takes that tick by the server's poses, as before, and so does the rest of its way
 * (and as every bullet does with clientHits off, and every bullet not a player's: the AI bots' among them).
 */
public final class ClientHits {
    private ClientHits() {
    }

    /** the switch; read on both sides (the client follows the server's settings) */
    public static boolean on() {
        return ReaimTimes.flag("clientHits", true);
    }

    /** what a player's bullet has from its client, kept on the bullet (MixinBulletServer) */
    public static final class State {
        /** the client's: it tests the path and reports; false: the server decides as before */
        boolean client;
        /** the client tested the path up to this many ticks from the start */
        float tested = -1;
        /** the hits reported and not taken yet; the mobs already hit */
        final List<HitReportPayload.Claim> claims = new ArrayList<>();
        final Set<Integer> used = new HashSet<>();
        /** ticks it has waited at this tick; this tick goes by the client's hits */
        int waited;
        boolean decides;
        /** the hit the bullet's impact under way is (HeadDamage: its part) */
        HitReportPayload.Claim current;
        Vec3 start;
        double reach;
        ServerPlayer shooter;
        /** the stand: this tick's blocks gone through, by their distance; where it hit a mob */
        final java.util.Map<net.minecraft.core.BlockPos, Double> standPierced = new java.util.HashMap<>();
        double standHit = Double.POSITIVE_INFINITY;
    }

    public interface Holder {
        State gunfeel$hits();
    }

    // ---------------------------------------------------------------- counts (the stand, the test log)

    /** reports' hits taken, refused by the checks, come after the bullet was gone; ticks held, ticks taken by the server's poses */
    public static long taken, refused, late, timedOut, held;

    /** the stand: each hit a player's bullet took, {bullet, mob, the client's part or -1: by the server's poses} */
    static final List<int[]> STAND_LOG = java.util.Collections.synchronizedList(new ArrayList<>());
    public static volatile boolean standLog;

    /** the stand: the blocks each player's bullet stopped in or went through */
    static final java.util.Map<Integer, Set<net.minecraft.core.BlockPos>> STAND_BLOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    /** the stand: a block this tick's path went through, kept if it lies before what the bullet hit this tick */
    public static void standPierced(EntityBulletBaseMK4 b, net.minecraft.core.BlockPos pos, Vec3 from) {
        if (b instanceof Holder && state(b).client) state(b).standPierced.put(pos.immutable(), from.distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(pos)));
    }

    public static void standBlock(EntityBulletBaseMK4 b, net.minecraft.core.BlockPos pos) {
        if (b instanceof Holder && state(b).client) STAND_BLOCKS.computeIfAbsent(b.getId(), k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(pos.immutable());
    }

    static State state(EntityBulletBaseMK4 b) {
        return ((Holder) b).gunfeel$hits();
    }

    /** the bullet was just announced: a player's fast bullet is the client's to decide while the switch is on */
    public static void announced(EntityBulletBaseMK4 b) {
        State s = state(b);
        if (!(b.getOwner() instanceof ServerPlayer p) || p instanceof net.neoforged.neoforge.common.util.FakePlayer || b instanceof CollisionProbe
                || !on()) return;
        s.client = true;
        s.shooter = p;
        s.start = b.position();
        // the farthest it can be: its speed a tick for its life, and a margin
        s.reach = b.getDeltaMovement().length() * motionOf(b) * Math.max(1.0, (double) b.config.expires) * 2 + 16;
    }

    // ---------------------------------------------------------------- the report comes in

    public static void receive(ServerPlayer player, HitReportPayload report) {
        for (var e : report.entries()) {
            Entity found = player.level().getEntity(e.bullet());
            if (!(found instanceof EntityBulletBaseMK4 b) || b.getOwner() != player || !(b instanceof Holder)) {
                late += e.claims().size();
                continue;
            }
            State s = state(b);
            if (!s.client) continue;
            if (Float.isNaN(e.tested())) {
                // the client does not follow it (no tracers, holes or effects): the server decides
                s.client = false;
                continue;
            }
            if (e.tested() > s.tested) s.tested = e.tested();
            for (var c : e.claims()) {
                if (check(b, s, c)) s.claims.add(c);
                else {
                    // a hit it cannot take: the rest of this bullet's way by the server's poses, not through the mob
                    refused++;
                    s.client = false;
                    s.claims.clear();
                    break;
                }
            }
        }
    }

    /** a hit's checks against mistakes */
    static boolean check(EntityBulletBaseMK4 b, State s, HitReportPayload.Claim c) {
        if (!Float.isFinite(c.age()) || c.age() < 0 || c.age() > b.config.expires + 1) return why("age");
        Vec3 p = c.point();
        if (!Double.isFinite(p.x) || !Double.isFinite(p.y) || !Double.isFinite(p.z)) return why("point");
        if (s.used.contains(c.entity())) return why("used");
        Entity e = entity(b, c.entity());
        if (e == null || !e.isAlive() || e.level() != b.level()) {
            if (standLog) DebugLog.LOGGER.info("[client-hits] refused target {} on {}: {}", c, b.getId(), e == null ? "none" : e + " alive " + e.isAlive());
            return why("target");
        }
        // its shooter only once it is out of the gun's guard, as NTM has it
        if (e == s.shooter && c.age() < b.config.selfDamageDelay) return why("shooter");
        if (s.start.distanceTo(p) > s.reach) return why("reach");
        // the right mob: the point within its reach on the server (a running mob is a ping further on)
        // a plain box: a mob's own (ModelAABB) tests points against its model
        AABB box = e.getBoundingBox();
        return new AABB(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ).inflate(ModelHits.radius(e) + 8).contains(p) || why("far");
    }

    /** a mob, a player or a part of one (the dragon's) by its id */
    private static Entity entity(EntityBulletBaseMK4 b, int id) {
        return b.level() instanceof net.minecraft.server.level.ServerLevel level ? level.getEntityOrPart(id) : b.level().getEntity(id);
    }

    /** the stand: why hits were refused */
    static final java.util.Map<String, Integer> WHY = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean why(String reason) {
        WHY.merge(reason, 1, Integer::sum);
        return false;
    }

    // ---------------------------------------------------------------- the bullet's tick

    /** at the start of the bullet's tick: true holds it there this tick, its report to come */
    public static boolean hold(EntityBulletBaseMK4 b) {
        State s = state(b);
        s.decides = false;
        if (!s.client) return false;
        if (s.shooter.isRemoved() || s.shooter.level() != b.level()) {
            s.client = false;
            return false;
        }
        // this tick's path: from the bullet's age to one tick on
        if (s.tested >= b.ticksInAir + 1 - 1e-3 || !near(b)) {
            s.waited = 0;
            s.decides = true;
            return false;
        }
        if (s.waited < patience(s.shooter)) {
            s.waited++;
            held++;
            return true;
        }
        // no report by then: this tick and the rest of its way by the server's poses
        if (standLog && timedOut % 50 == 0)
            DebugLog.LOGGER.info("[client-hits] timeout bullet {} tick {} tested {} game {}", b.getId(), b.ticksInAir, s.tested, b.level().getGameTime());
        s.waited = 0;
        s.client = false;
        s.claims.clear();
        timedOut++;
        return false;
    }

    /** ticks a bullet waits for its report: the ping and five, at most a second */
    static int patience(ServerPlayer p) {
        return Math.clamp((int) Math.ceil(p.connection.latency() / 50.0) + 5, 5, 20);
    }

    /** a mob near this tick's path (NTM's area and a lag's margin) */
    private static boolean near(EntityBulletBaseMK4 b) {
        AABB area = b.getBoundingBox().expandTowards(b.getDeltaMovement().scale(motionOf(b))).inflate(4);
        LivingEntity thrower = b.getThrower();
        return !b.level().getEntities(b, area, e -> e.isPickable() && e.isAlive() && e != thrower).isEmpty();
    }

    private static double motionOf(EntityBulletBaseMK4 b) {
        return ((MotionMult) b).gunfeel$motionMult();
    }

    /** MixinBulletServer: NTM's motionMult with the mod's speed */
    public interface MotionMult {
        double gunfeel$motionMult();
    }

    /** this tick goes by the client's hits */
    public static boolean decides(Object bullet) {
        return bullet instanceof EntityBulletBaseMK4 b && bullet instanceof Holder && state(b).decides;
    }

    /** the mobs the client hit by this tick and not taken yet, added to NTM's list (a mob a ping on from where it was seen) */
    public static List<Entity> candidates(EntityBulletBaseMK4 b, List<Entity> list) {
        State s = state(b);
        if (s.claims.isEmpty()) return list;
        List<Entity> out = null;
        for (var c : s.claims) {
            if (c.age() > b.ticksInAir + 1e-3 || s.used.contains(c.entity())) continue;
            Entity e = entity(b, c.entity());
            if (e == null || !e.isAlive() || list.contains(e) || (out != null && out.contains(e))) continue;
            if (out == null) out = new ArrayList<>(list);
            out.add(e);
        }
        return out == null ? list : out;
    }

    /** where the client saw this tick's path meet {@code target}: its reported point; none: no hit */
    public static java.util.Optional<Vec3> clip(EntityBulletBaseMK4 b, Entity target) {
        var c = claim(state(b), target, b.ticksInAir);
        return c == null ? java.util.Optional.empty() : java.util.Optional.of(c.point());
    }

    private static HitReportPayload.Claim claim(State s, Entity target, int ticks) {
        if (s.used.contains(target.getId())) return null;
        for (var c : s.claims) if (c.entity() == target.getId() && c.age() <= ticks + 1e-3) return c;
        return null;
    }

    /** the impact on {@code target} begins: the hit it is (its part), and the mob is hit */
    public static void impact(EntityBulletBaseMK4 b, Entity target) {
        if (!(b instanceof Holder)) return;
        State s = state(b);
        s.current = null;
        if (s.decides) {
            var c = claim(s, target, b.ticksInAir);
            if (c != null) {
                s.current = c;
                s.claims.remove(c);
                taken++;
            }
        }
        if (standLog && s.client) s.standHit = Math.min(s.standHit, (s.current != null ? s.current.point() : target.position()).distanceTo(b.position()));
        if (standLog && s.client) STAND_LOG.add(new int[] { b.getId(), target.getId(), s.current == null ? -1 : s.current.zone() });
        s.used.add(target.getId());
    }

    /** the tick is over: an impact from outside it is not the client's */
    public static void tickEnd(EntityBulletBaseMK4 b) {
        if (!(b instanceof Holder)) return;
        State s = state(b);
        s.decides = false;
        if (standLog) for (var e : s.standPierced.entrySet()) if (e.getValue() < s.standHit + 0.5) standBlock(b, e.getKey());
        s.standPierced.clear();
        s.standHit = Double.POSITIVE_INFINITY;
    }

    public static void impactEnd(EntityBulletBaseMK4 b) {
        if (b instanceof Holder) state(b).current = null;
    }

    /** the part the client saw the bullet under way hit on {@code target}, or null: the server's poses decide */
    public static ModelHeads.Hit part(EntityBulletBaseMK4 b, Entity target) {
        if (!(b instanceof Holder) || !ModelHits.enabled()) return null;
        var c = state(b).current;
        if (c == null || c.entity() != target.getId()) return null;
        return switch (c.zone()) {
            case HitReportPayload.HEAD -> new ModelHeads.Hit(true, true, "client", "клиент стрелка", 1F);
            case HitReportPayload.LIMB -> new ModelHeads.Hit(true, false, "limbs", "клиент стрелка", 1F);
            default -> new ModelHeads.Hit(true, false, "client", "клиент стрелка", 1F);
        };
    }
}
