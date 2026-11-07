package acup.gunfeel.water;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import acup.gunfeel.TestLog;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

// 26.2's WaterTick, the ballistics' part: one tick of an NTM bullet in water (MixinThrowableClip). The path is walked
// through liquid before NTM tests it against blocks and mobs, cut where water stops or skips the bullet, and at the
// end of the tick the bullet takes its new f, stops or turns. The bullet's damage stays in the units of the tick's
// starting f while the tick runs; a hit at a point takes (f there / f at the start)² of it (k), and the end of the
// tick moves it to the new f. port: NTM CE moves its bullets only on the server; the client's tracer works out the
// same path itself (Tracers.water). Also NTM CE's old bullet with a charge (OldBullets, MixinOldBulletWater), on both
// sides: it flies on the client too.
public final class WaterTick {
    private WaterTick() {
    }

    // an impact fuze: NTM's bullet by its config, an old one by its list (OldBullets)
    static boolean fuzed(Entity bullet) {
        if (bullet instanceof EntityBulletBaseMK4) return WaterClass.Fuze.of(((EntityBulletBaseMK4) bullet).config).any();
        return OldBullets.fuzed(bullet);
    }

    // the tick's path: the point to test blocks up to (the stop or the skip, else `to`)
    public static Vec3d path(Entity bullet, World world, Vec3d from, Vec3d to) {
        WaterBullet w = (WaterBullet) bullet;
        w.gunfeel$waterTick(null);
        // a stopped explosive sinks (sink) and goes off where it touches
        if (w.gunfeel$waterStopped()) return to;
        WaterPath.Settings s = WaterPath.Settings.current();
        if (!s.on()) return to;
        long seed = seed(bullet.getEntityId(), w.gunfeel$waterSkips());
        // an impact fuze skips by the fuze's angle (waterFuzeSkipAngle), an explosive bullet of a rifle too
        if (fuzed(bullet)) s = s.fuzed();
        WaterPath.Result r = WaterPath.trace(world, from, to, w.gunfeel$waterF(), w.gunfeel$waterClass(), w.gunfeel$waterExpansion(),
                w.gunfeel$waterOpened(), w.gunfeel$waterSkips(), seed, s, bullet);
        if (r.events.isEmpty() && r.water <= 0) return to;
        w.gunfeel$waterTick(r);
        return r.end();
    }

    // a stopped bullet that sinks rather than goes: one with an impact fuze
    public static boolean sinks(Entity bullet) {
        return fuzed(bullet);
    }

    // sinking: straight down by NTM's gravity (at least MIN_G a tick), no faster than SINK (1 block a second); its
    // motionMult is 1 meanwhile (MixinWaterBullet), so the motion is the path a tick
    static final double SINK = 0.05, MIN_G = 0.01;

    static void sink(Entity bullet) {
        double vy = bullet.motionY;
        if (bullet instanceof EntityBulletBaseMK4) {
            EntityBulletBaseMK4 b = (EntityBulletBaseMK4) bullet;
            double g = b.config == null ? 0 : b.getGravityVelocity();
            if (g < MIN_G) vy -= MIN_G - g;
        } else {
            // the old bullet's gravity is held off while it sinks (MixinOldBulletWater): it is taken here
            vy -= Math.max(OldBullets.gravity(bullet), MIN_G);
        }
        bullet.motionX = 0;
        bullet.motionY = sinkSpeed(vy);
        bullet.motionZ = 0;
    }

    public static double sinkSpeed(double vy) {
        return Math.clamp(vy, -SINK, 0);
    }

    // the skip's turn: by the bullet's id and its skips so far, the same on the server and the tracer
    public static long seed(int id, int skips) {
        return id * 0x9E3779B97F4A7C15L + skips * 7919L;
    }

    // the block NTM's path test found (null: a miss): what lies past it this tick does not happen, and a hit under the
    // liquid's surface is the bottom of shallow water. An impact fuze coming from the air goes off on the surface
    // (waterFuze): the hit NTM gets is the surface's
    public static RayTraceResult blocks(Entity bullet, RayTraceResult hit) {
        WaterPath.Result r = ((WaterBullet) bullet).gunfeel$waterTick();
        if (r == null) return hit;
        RayTraceResult fuze = fuze(bullet, r, hit);
        if (fuze != null) {
            cut(r, fuze, false);
            r.fuzeHit = fuze;
            return fuze;
        }
        if (hit == null) return null;
        cut(r, hit, true);
        return hit;
    }

    // the surface an impact fuze meets before the block NTM found: the first entry from the air into liquid (where
    // lava, or water for the Daybreaker's flame, stops it at once, the stop there)
    static RayTraceResult fuze(Entity bullet, WaterPath.Result r, RayTraceResult hit) {
        if (!WaterSettings.fuze() || !fuzed(bullet)) return null;
        double block = hit == null ? Double.POSITIVE_INFINITY : r.t(hit.hitVec);
        for (WaterEvents.Event e : r.events) {
            boolean entry = e.type() == WaterEvents.Type.ENTER || e.type() == WaterEvents.Type.STOP
                    && (e.liquid() == WaterEvents.Liquid.LAVA || r.cls().length <= 0) && r.events.indexOf(e) == 0 && !r.startsIn();
            if (!entry) continue;
            if (r.t(e.at()) > block + 1e-7) return null;
            return acup.gunfeel.Mc.blockHit(e.pos().getX(), e.pos().getY(), e.pos().getZ(), e.face().getIndex(), e.at());
        }
        return null;
    }

    // a hit is this tick's surface fuze (WaterImpact, ExplosiveDamage: no hole on water)
    public static boolean surfaceHit(Entity bullet, RayTraceResult hit) {
        if (!(bullet instanceof WaterBullet)) return false;
        WaterPath.Result r = ((WaterBullet) bullet).gunfeel$waterTick();
        return r != null && r.fuzeHit != null && r.fuzeHit == hit;
    }

    // this tick's block hit is the surface's fuze (MixinOldBulletWater: it goes off, NTM's ricochet does not bounce it)
    public static boolean fuzedOnSurface(Entity bullet) {
        if (!(bullet instanceof WaterBullet)) return false;
        WaterPath.Result r = ((WaterBullet) bullet).gunfeel$waterTick();
        return r != null && r.fuzeHit != null;
    }

    private static void cut(WaterPath.Result r, RayTraceResult hit, boolean block) {
        double t = r.t(hit.hitVec);
        r.blockT = t;
        // what lies past the block on the path does not happen
        r.events.removeIf(e -> r.t(e.at()) > t + 1e-7);
        if (!block) return;
        WaterEvents.Event bottom = r.bottom(hit.hitVec, hit.sideHit);
        if (bottom != null) r.events.add(bottom);
    }

    // NTM's test of a mob's hitbox: not past where water stopped or turned the bullet
    public static Vec3d mobsTo(Entity bullet, Vec3d to) {
        if (!(bullet instanceof WaterBullet)) return to;
        WaterPath.Result r = ((WaterBullet) bullet).gunfeel$waterTick();
        if (r == null || !r.cut()) return to;
        Vec3d end = r.end();
        return end.squareDistanceTo(r.from) < to.squareDistanceTo(r.from) ? end : to;
    }

    // what is left of the bullet's damage at a point of its path: (f there / f the damage is counted at)²
    public static float k(Entity bullet, Vec3d at) {
        if (at == null || !(bullet instanceof WaterBullet)) return 1F;
        WaterPath.Result r = ((WaterBullet) bullet).gunfeel$waterTick();
        if (r == null || r.unit <= 0) return 1F;
        double q = r.fAt(at) / r.unit;
        return (float) (q * q);
    }

    // the end of the tick (after NTM moved the bullet and hit what it hit)
    public static void end(Entity bullet) {
        WaterBullet w = (WaterBullet) bullet;
        WaterPath.Result r = w.gunfeel$waterTick();
        if (r == null) {
            if (w.gunfeel$waterStopped() && !bullet.isDead) sink(bullet);
            return;
        }
        if (r.ended) return;
        r.ended = true;
        w.gunfeel$waterMet(true);
        World world = bullet.world;
        boolean server = !world.isRemote;
        double f0 = r.startF();
        double hitT = r.blockT;
        boolean blocked = !Double.isNaN(hitT) && hitT < r.t(r.end()) - 1e-7;
        double f1 = blocked ? r.fAt(hitT) : r.f;
        // NTM's bullet carries its damage; the old one rolls it at each hit (OldBullets.damage)
        if (server && f0 > 0 && bullet instanceof EntityBulletBaseMK4) ((EntityBulletBaseMK4) bullet).damage *= (float) ((f1 / f0) * (f1 / f0));
        w.gunfeel$waterF(f1);
        w.gunfeel$waterOpened(blocked ? r.openedAt(hitT) : r.opened);
        r.unit = f1;
        if (server && TestLog.server) for (WaterEvents.Event e : r.events)
            TestLog.s(bullet.getEntityId(), "water", "event", e.type(), "at", v(e.at()), "f", e.f(), "kind", e.kind(), "liquid", e.liquid(),
                    "class", e.cls(), "depth", e.depth(), "under", e.under(), "path", r.water);
        // the client's effects: NTM's bullet's own come from its tracer; the old bullet's from MixinOldBulletWater
        // (OldBullets.effects)
        if (bullet instanceof EntityBulletBaseMK4) {
            WaterEvents.fire(world, r.events);
            WaterEvents.tick(world, bullet, r);
        }
        if (blocked || bullet.isDead) return;
        if (r.stop != null) {
            bullet.setPosition(r.stop.x, r.stop.y, r.stop.z);
            w.gunfeel$waterStopped(true);
            // a bullet is gone where water stopped it; one with an impact fuze sinks and goes off where it touches,
            // or is gone without a blast when NTM's life of the round (BulletConfig.expires) ends first, as in the air
            if (!sinks(bullet)) {
                if (server && TestLog.server) TestLog.s(bullet.getEntityId(), "end", "by", "water", "at", v(r.stop), "path", r.water);
                bullet.setDead();
            } else {
                if (server && TestLog.server) TestLog.s(bullet.getEntityId(), "water", "sinks", "at", v(r.stop), "path", r.water);
                bullet.motionX = bullet.motionY = bullet.motionZ = 0;
            }
        } else if (r.skip != null) {
            WaterPath.Skip k = r.skip;
            bullet.setPosition(k.at().x, k.at().y, k.at().z);
            double speed = Math.sqrt(bullet.motionX * bullet.motionX + bullet.motionY * bullet.motionY + bullet.motionZ * bullet.motionZ);
            bullet.motionX = k.dir().x * speed;
            bullet.motionY = k.dir().y * speed;
            bullet.motionZ = k.dir().z * speed;
            w.gunfeel$waterSkips(w.gunfeel$waterSkips() + 1);
        } else if (!(bullet instanceof EntityBulletBaseMK4) && (bullet.posX != r.from.x || bullet.posY != r.from.y || bullet.posZ != r.from.z)) {
            // the old bullet takes its gravity off its motion between the path test and the move: in water it moves
            // along the path tested, the gravity bends the next tick's (MixinOldBulletWater)
            Vec3d to = r.end();
            bullet.setPosition(to.x, to.y, to.z);
        }
    }

    private static String v(Vec3d p) {
        return String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.4f", p.x, p.y, p.z);
    }
}
