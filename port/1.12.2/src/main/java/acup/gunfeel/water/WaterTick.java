package acup.gunfeel.water;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import acup.gunfeel.TestLog;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

// 26.2's WaterTick, the ballistics' part: one tick of an NTM bullet in water (MixinThrowableClip). The path is walked
// through liquid before NTM tests it against blocks and mobs, cut where water stops or skips the bullet, and at the
// end of the tick the bullet takes its new f, stops or turns. The bullet's damage stays in the units of the tick's
// starting f while the tick runs; a hit at a point takes (f there / f at the start)² of it (k), and the end of the
// tick moves it to the new f. port: NTM CE moves its bullets only on the server; the client's tracer works out the
// same path itself (Tracers.water).
public final class WaterTick {
    private WaterTick() {
    }

    // the tick's path: the point to test blocks up to (the stop or the skip, else `to`)
    public static Vec3d path(EntityBulletBaseMK4 bullet, World world, Vec3d from, Vec3d to) {
        WaterBullet w = (WaterBullet) bullet;
        w.gunfeel$waterTick(null);
        // a stopped explosive goes on at the stopping speed till it touches something
        if (w.gunfeel$waterStopped()) return to;
        WaterPath.Settings s = WaterPath.Settings.current();
        if (!s.on()) return to;
        long seed = seed(bullet.getEntityId(), w.gunfeel$waterSkips());
        WaterPath.Result r = WaterPath.trace(world, from, to, w.gunfeel$waterF(), w.gunfeel$waterClass(), w.gunfeel$waterExpansion(),
                w.gunfeel$waterOpened(), w.gunfeel$waterSkips(), seed, s, bullet);
        if (r.events.isEmpty() && r.water <= 0) return to;
        w.gunfeel$waterTick(r);
        return r.end();
    }

    // the skip's turn: by the bullet's id and its skips so far, the same on the server and the tracer
    public static long seed(int id, int skips) {
        return id * 0x9E3779B97F4A7C15L + skips * 7919L;
    }

    // the block NTM's path test found (null: a miss) at `at` on `face`: what lies past it this tick does not happen,
    // and a hit under the liquid's surface is the bottom of shallow water
    public static void blocks(EntityBulletBaseMK4 bullet, Vec3d at, EnumFacing face) {
        WaterPath.Result r = ((WaterBullet) bullet).gunfeel$waterTick();
        if (r == null || at == null) return;
        double t = r.t(at);
        r.blockT = t;
        r.events.removeIf(e -> r.t(e.at()) > t + 1e-7);
        WaterEvents.Event bottom = r.bottom(at, face);
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
    public static void end(EntityBulletBaseMK4 bullet) {
        WaterBullet w = (WaterBullet) bullet;
        WaterPath.Result r = w.gunfeel$waterTick();
        if (r == null || r.ended) return;
        r.ended = true;
        World world = bullet.world;
        boolean server = !world.isRemote;
        double f0 = r.startF();
        double hitT = r.blockT;
        boolean blocked = !Double.isNaN(hitT) && hitT < r.t(r.end()) - 1e-7;
        double f1 = blocked ? r.fAt(hitT) : r.f;
        if (server && f0 > 0) bullet.damage *= (float) ((f1 / f0) * (f1 / f0));
        w.gunfeel$waterF(f1);
        w.gunfeel$waterOpened(blocked ? r.openedAt(hitT) : r.opened);
        r.unit = f1;
        if (server && TestLog.server) for (WaterEvents.Event e : r.events)
            TestLog.s(bullet.getEntityId(), "water", "event", e.type(), "at", v(e.at()), "f", e.f(), "kind", e.kind(), "liquid", e.liquid(),
                    "class", e.cls(), "depth", e.depth(), "under", e.under(), "path", r.water);
        WaterEvents.fire(world, r.events);
        WaterEvents.tick(world, bullet, r);
        if (blocked || bullet.isDead) return;
        if (r.stop != null) {
            bullet.setPosition(r.stop.x, r.stop.y, r.stop.z);
            w.gunfeel$waterStopped(true);
            // a bullet is gone where water stopped it; an explosive goes on slowly and goes off where it touches
            if (!w.gunfeel$waterClass().explosive) {
                if (server && TestLog.server) TestLog.s(bullet.getEntityId(), "end", "by", "water", "at", v(r.stop), "path", r.water);
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

    private static String v(Vec3d p) {
        return String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.4f", p.x, p.y, p.z);
    }
}
