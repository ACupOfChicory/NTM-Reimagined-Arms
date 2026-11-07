package acup.gunfeel.water;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import acup.gunfeel.BlockRay;
import acup.gunfeel.TestLog;
import acup.gunfeel.phys.Direction;
import acup.gunfeel.phys.Vec3;
import net.minecraft.entity.Entity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

// One tick of an NTM bullet in water, on the server (MixinThrowableClip): the path is walked through liquid before NTM
// tests it against blocks and mobs, cut where water stops or skips the bullet, and at the end of the tick the bullet
// takes its new f, stops or turns. The bullet's damage stays in the units of the tick's starting f while the tick
// runs; a hit at a point takes (f there / f at the start)² of it (k), and the end of the tick moves it to the new f.
// port: 26.2's WaterTick (df28728). NTM 1.7.10's bullet ticks on the server only (its client copy is moved by the
// server's updates, EntityThrowableInterp), so the client's events come from the tracers (Tracers.water). Blocks are
// the port's BlockRay.Hit (null: a miss); a point is the port's Vec3 or x, y, z. The explosives' part (main 7e2382c
// to a1f618b): an impact fuze goes off on the surface (blocks gives NTM the surface's hit), a stopped one sinks.
public final class WaterTick {
    private WaterTick() {
    }

    // the tick's path: the point to test blocks up to (the stop or the skip, else `to`)
    public static Vec3 path(EntityBulletBaseMK4 bullet, World level, Vec3 from, Vec3 to) {
        WaterBullet w = (WaterBullet) bullet;
        w.gunfeel$waterTick(null);
        if (w.gunfeel$waterStopped()) return to; // a stopped explosive sinks (sink) and goes off where it touches
        WaterPath.Settings s = WaterPath.Settings.current();
        if (!s.on()) return to;
        long seed = seed(bullet.getEntityId(), w.gunfeel$waterSkips());
        WaterClass cls = w.gunfeel$waterClass();
        // an impact fuze skips by the fuze's angle (waterFuzeSkipAngle), an explosive bullet of a rifle too
        if (WaterClass.Fuze.ofConfig(bullet.config).any()) s = s.fuzed();
        WaterPath.Result r = WaterPath.trace(level, from, to, w.gunfeel$waterF(), cls, w.gunfeel$waterExpansion(), w.gunfeel$waterOpened(),
                w.gunfeel$waterSkips(), seed, s, bullet);
        if (r.events.isEmpty() && r.water <= 0) return to;
        w.gunfeel$waterTick(r);
        return r.end();
    }

    // a stopped bullet that sinks rather than goes: one with an impact fuze
    public static boolean sinks(EntityBulletBaseMK4 bullet) {
        return WaterClass.Fuze.ofConfig(bullet.config).any();
    }

    // sinking: straight down by NTM's gravity (at least MIN_G a tick), no faster than SINK (1 block a second); its
    // motionMult is 1 meanwhile (MixinWaterBullet), so the motion is the path a tick
    static final double SINK = 0.05, MIN_G = 0.01;

    static void sink(EntityBulletBaseMK4 bullet) {
        double g = bullet.config == null ? 0 : bullet.getGravityVelocity();
        double vy = bullet.motionY;
        if (g < MIN_G) vy -= MIN_G - g;
        bullet.motionX = 0;
        bullet.motionY = sinkSpeed(vy);
        bullet.motionZ = 0;
    }

    public static double sinkSpeed(double vy) {
        return Math.max(-SINK, Math.min(0, vy));
    }

    // the skip's turn: by the bullet's id and its skips so far, the same on the server and the tracer
    public static long seed(int id, int skips) {
        return id * 0x9E3779B97F4A7C15L + skips * 7919L;
    }

    // the block NTM's path test found (null: a miss): a hit under the liquid's surface is the bottom of shallow water.
    // An impact fuze coming from the air goes off on the surface (waterFuze): the hit NTM gets is the surface's
    public static BlockRay.Hit blocks(EntityBulletBaseMK4 bullet, BlockRay.Hit hit) {
        WaterPath.Result r = ((WaterBullet) bullet).gunfeel$waterTick();
        if (r == null) return hit;
        BlockRay.Hit fuze = fuze(bullet, r, hit);
        if (fuze != null) {
            cut(r, fuze, false);
            r.fuzeHit = fuze;
            return fuze;
        }
        if (hit == null) return null;
        cut(r, hit, true);
        return hit;
    }

    // the surface an impact fuze meets before the block NTM found: the first entry from the air into liquid
    static BlockRay.Hit fuze(EntityBulletBaseMK4 bullet, WaterPath.Result r, BlockRay.Hit hit) {
        if (!WaterSettings.fuze() || !WaterClass.Fuze.ofConfig(bullet.config).any()) return null;
        double block = hit == null ? Double.POSITIVE_INFINITY : r.t(new Vec3(hit.ax, hit.ay, hit.az));
        for (WaterEvents.Event e : r.events) {
            boolean entry = e.type() == WaterEvents.Type.ENTER || e.type() == WaterEvents.Type.STOP && e.liquid() == WaterEvents.Liquid.LAVA
                    && r.events.indexOf(e) == 0 && !r.startsIn();
            if (!entry) continue;
            if (r.t(e.at()) > block + 1e-7) return null;
            // (1.7.10's side numbers are Direction's order)
            return new BlockRay.Hit(e.pos().getX(), e.pos().getY(), e.pos().getZ(), e.face().ordinal(), e.at().x, e.at().y, e.at().z, false);
        }
        return null;
    }

    // a hit is this tick's surface fuze (WaterImpact, ExplosiveDamage: no hole on water). port: NTM gets a
    // MovingObjectPosition made from the BlockRay.Hit (MixinThrowableClip), so it is matched by its cell and point
    public static boolean surfaceHit(Entity bullet, MovingObjectPosition hit) {
        if (!(bullet instanceof WaterBullet w) || hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || hit.hitVec == null)
            return false;
        WaterPath.Result r = w.gunfeel$waterTick();
        if (r == null || r.fuzeHit == null) return false;
        BlockRay.Hit f = r.fuzeHit;
        return f.x == hit.blockX && f.y == hit.blockY && f.z == hit.blockZ && f.ax == hit.hitVec.xCoord && f.ay == hit.hitVec.yCoord
                && f.az == hit.hitVec.zCoord;
    }

    private static void cut(WaterPath.Result r, BlockRay.Hit hit, boolean block) {
        Vec3 at = new Vec3(hit.ax, hit.ay, hit.az);
        double t = r.t(at);
        r.blockT = t;
        // what lies past the block on the path does not happen
        r.events.removeIf(e -> r.t(e.at()) > t + 1e-7);
        if (!block) return;
        WaterEvents.Event bottom = r.bottom(at, Direction.from3DDataValue(hit.face));
        if (bottom != null) r.events.add(bottom);
    }

    // NTM's test of a mob's hitbox: not past where water stopped or turned the bullet
    public static Vec3 mobsTo(Entity bullet, Vec3 to) {
        if (!(bullet instanceof WaterBullet w)) return to;
        WaterPath.Result r = w.gunfeel$waterTick();
        if (r == null || !r.cut()) return to;
        Vec3 end = r.end();
        return end.distanceToSqr(r.from) < to.distanceToSqr(r.from) ? end : to;
    }

    // what is left of the bullet's damage at a point of its path: (f there / f the damage is counted at)²
    public static float k(Entity bullet, Vec3 at) {
        if (at == null || !(bullet instanceof WaterBullet w)) return 1F;
        WaterPath.Result r = w.gunfeel$waterTick();
        if (r == null || r.unit <= 0) return 1F;
        double q = r.fAt(at) / r.unit;
        return (float) (q * q);
    }

    public static float k(Entity bullet, double x, double y, double z) {
        return k(bullet, new Vec3(x, y, z));
    }

    // the end of the tick (after NTM moved the bullet and hit what it hit)
    public static void end(EntityBulletBaseMK4 bullet) {
        WaterBullet w = (WaterBullet) bullet;
        WaterPath.Result r = w.gunfeel$waterTick();
        if (r == null) {
            if (w.gunfeel$waterStopped() && !bullet.isDead) sink(bullet);
            return;
        }
        w.gunfeel$waterMet(true);
        World level = bullet.worldObj;
        boolean server = !level.isRemote;
        double f0 = r.startF();
        double hitT = r.blockT;
        boolean blocked = !Double.isNaN(hitT) && hitT < r.t(r.end()) - 1e-7;
        double f1 = blocked ? r.fAt(hitT) : r.f;
        if (server && f0 > 0) bullet.damage *= (float) ((f1 / f0) * (f1 / f0));
        w.gunfeel$waterF(f1);
        w.gunfeel$waterOpened(blocked ? r.openedAt(hitT) : r.opened);
        r.unit = f1;
        if (server && TestLog.server) for (WaterEvents.Event e : r.events)
            TestLog.s(bullet.getEntityId(), "water", "event", e.type(), "at", e.at(), "f", e.f(), "kind", e.kind(), "liquid", e.liquid(),
                    "class", e.cls(), "depth", e.depth(), "under", e.under(), "path", r.water);
        WaterEvents.fire(level, r.events);
        WaterEvents.tick(level, bullet, r);
        if (blocked || bullet.isDead) return;
        if (r.stop != null) {
            bullet.setPosition(r.stop.x, r.stop.y, r.stop.z);
            w.gunfeel$waterStopped(true);
            // a bullet is gone where water stopped it; one with an impact fuze sinks and goes off where it touches,
            // or is gone without a blast when NTM's life of the round (BulletConfig.expires) ends first, as in the air
            if (!sinks(bullet)) {
                if (server && TestLog.server) TestLog.s(bullet.getEntityId(), "end", "by", "water", "at", r.stop, "path", r.water);
                bullet.setDead();
            } else {
                if (server && TestLog.server) TestLog.s(bullet.getEntityId(), "water", "sinks", "at", r.stop, "path", r.water);
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
        }
    }
}
