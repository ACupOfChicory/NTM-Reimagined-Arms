package acup.gunfeel.water;

import org.jspecify.annotations.Nullable;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import acup.gunfeel.TestLog;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// One tick of an NTM bullet in water, on both sides (MixinThrowableHit): the path is walked through liquid before NTM
// tests it against blocks and mobs, cut where water stops or skips the bullet, and at the end of the tick the bullet
// takes its new f, stops or turns. The bullet's damage stays in the units of the tick's starting f while the tick
// runs; a hit at a point takes (f there / f at the start)² of it (k), and the end of the tick moves it to the new f.
public final class WaterTick {
    private WaterTick() {
    }

    // the tick's path: the point to test blocks up to (the stop or the skip, else `to`)
    public static Vec3 path(EntityBulletBaseMK4 bullet, Level level, Vec3 from, Vec3 to) {
        WaterBullet w = (WaterBullet) bullet;
        w.gunfeel$waterTick(null);
        if (w.gunfeel$waterStopped()) return to; // a stopped explosive sinks: the explosives' part moves it
        WaterPath.Settings s = WaterPath.Settings.current();
        if (!s.on()) return to;
        long seed = seed(bullet.getId(), w.gunfeel$waterSkips());
        WaterPath.Result r = WaterPath.trace(level, from, to, w.gunfeel$waterF(), w.gunfeel$waterClass(), w.gunfeel$waterSkips(), seed, s, bullet);
        if (r.events.isEmpty() && r.water <= 0) return to;
        w.gunfeel$waterTick(r);
        return r.end();
    }

    // the skip's turn: by the bullet's id and its skips so far, the same on the server, the client's bullet and the
    // tracer
    public static long seed(int id, int skips) {
        return id * 0x9E3779B97F4A7C15L + skips * 7919L;
    }

    // the block NTM's path test found (or a miss): a hit under the liquid's surface is the bottom of shallow water
    public static void blocks(EntityBulletBaseMK4 bullet, BlockHitResult hit) {
        WaterPath.Result r = ((WaterBullet) bullet).gunfeel$waterTick();
        if (r == null || hit.getType() == HitResult.Type.MISS) return;
        double t = r.t(hit.getLocation());
        r.blockT = t;
        // what lies past the block on the path does not happen
        r.events.removeIf(e -> r.t(e.at()) > t + 1e-7);
        WaterEvents.Event bottom = r.bottom(hit.getLocation(), hit.getDirection());
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
    public static float k(Entity bullet, @Nullable Vec3 at) {
        if (at == null || !(bullet instanceof WaterBullet w)) return 1F;
        WaterPath.Result r = w.gunfeel$waterTick();
        if (r == null || r.unit <= 0) return 1F;
        double q = r.fAt(at) / r.unit;
        return (float) (q * q);
    }

    // the end of the tick (after NTM moved the bullet and hit what it hit)
    public static void end(EntityBulletBaseMK4 bullet) {
        WaterBullet w = (WaterBullet) bullet;
        WaterPath.Result r = w.gunfeel$waterTick();
        if (r == null) return;
        Level level = bullet.level();
        boolean server = !level.isClientSide();
        double f0 = r.startF();
        double hitT = r.blockT;
        boolean blocked = !Double.isNaN(hitT) && hitT < r.t(r.end()) - 1e-7;
        double f1 = blocked ? r.fAt(hitT) : r.f;
        if (server && f0 > 0) bullet.damage *= (float) ((f1 / f0) * (f1 / f0));
        w.gunfeel$waterF(f1);
        r.unit = f1;
        if (server && TestLog.server) for (WaterEvents.Event e : r.events)
            TestLog.s(bullet.getId(), "water", "event", e.type(), "at", e.at(), "f", e.f(), "kind", e.kind(), "liquid", e.liquid(),
                    "class", e.cls(), "depth", e.depth(), "under", e.under(), "path", r.water);
        WaterEvents.fire(level, r.events);
        WaterEvents.tick(level, bullet, r);
        if (blocked || bullet.isRemoved()) return;
        if (r.stop != null) {
            bullet.setPos(r.stop.x, r.stop.y, r.stop.z);
            w.gunfeel$waterStopped(true);
            // a bullet is gone where water stopped it; an explosive sinks and goes off by its fuze (the explosives' part)
            if (!w.gunfeel$waterClass().explosive) {
                if (server && TestLog.server) TestLog.s(bullet.getId(), "end", "by", "water", "at", r.stop, "path", r.water);
                bullet.discard();
            }
        } else if (r.skip != null) {
            WaterPath.Skip k = r.skip;
            bullet.setPos(k.at().x, k.at().y, k.at().z);
            bullet.setDeltaMovement(k.dir().scale(bullet.getDeltaMovement().length()));
            w.gunfeel$waterSkips(w.gunfeel$waterSkips() + 1);
        }
    }
}
