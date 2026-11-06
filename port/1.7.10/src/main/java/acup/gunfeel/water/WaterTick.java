package acup.gunfeel.water;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import acup.gunfeel.BlockRay;
import acup.gunfeel.TestLog;
import acup.gunfeel.phys.Direction;
import acup.gunfeel.phys.Vec3;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;

// One tick of an NTM bullet in water, on the server (MixinThrowableClip): the path is walked through liquid before NTM
// tests it against blocks and mobs, cut where water stops or skips the bullet, and at the end of the tick the bullet
// takes its new f, stops or turns. The bullet's damage stays in the units of the tick's starting f while the tick
// runs; a hit at a point takes (f there / f at the start)² of it (k), and the end of the tick moves it to the new f.
// port: 26.2's WaterTick (df28728). NTM 1.7.10's bullet ticks on the server only (its client copy is moved by the
// server's updates, EntityThrowableInterp), so the client's events come from the tracers (Tracers.water). Blocks are
// the port's BlockRay.Hit (null: a miss); a point is the port's Vec3 or x, y, z.
public final class WaterTick {
    private WaterTick() {
    }

    // the tick's path: the point to test blocks up to (the stop or the skip, else `to`)
    public static Vec3 path(EntityBulletBaseMK4 bullet, World level, Vec3 from, Vec3 to) {
        WaterBullet w = (WaterBullet) bullet;
        w.gunfeel$waterTick(null);
        if (w.gunfeel$waterStopped()) return to; // a stopped explosive sinks: the explosives' part moves it
        WaterPath.Settings s = WaterPath.Settings.current();
        if (!s.on()) return to;
        long seed = seed(bullet.getEntityId(), w.gunfeel$waterSkips());
        WaterClass cls = w.gunfeel$waterClass();
        WaterPath.Result r = WaterPath.trace(level, from, to, w.gunfeel$waterF(), cls, w.gunfeel$waterExpansion(), w.gunfeel$waterOpened(),
                w.gunfeel$waterSkips(), seed, s, bullet);
        if (r.events.isEmpty() && r.water <= 0) return to;
        w.gunfeel$waterTick(r);
        return r.end();
    }

    // the skip's turn: by the bullet's id and its skips so far, the same on the server and the tracer
    public static long seed(int id, int skips) {
        return id * 0x9E3779B97F4A7C15L + skips * 7919L;
    }

    // the block NTM's path test found (null: a miss): a hit under the liquid's surface is the bottom of shallow water
    public static void blocks(EntityBulletBaseMK4 bullet, BlockRay.Hit hit) {
        WaterPath.Result r = ((WaterBullet) bullet).gunfeel$waterTick();
        if (r == null || hit == null) return;
        Vec3 at = new Vec3(hit.ax, hit.ay, hit.az);
        double t = r.t(at);
        r.blockT = t;
        // what lies past the block on the path does not happen
        r.events.removeIf(e -> r.t(e.at()) > t + 1e-7);
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
        if (r == null) return;
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
            // a bullet is gone where water stopped it; an explosive sinks and goes off by its fuze (the explosives' part)
            if (!w.gunfeel$waterClass().explosive) {
                if (server && TestLog.server) TestLog.s(bullet.getEntityId(), "end", "by", "water", "at", r.stop, "path", r.water);
                bullet.setDead();
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
