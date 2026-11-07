package acup.gunfeel.water;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.explosion.vanillant.standard.EntityProcessorCrossSmooth;
import com.hbm.explosion.vanillant.standard.ExplosionEffectTiny;
import com.hbm.explosion.vanillant.standard.PlayerProcessorStandard;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.TestLog;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;

// 26.2's WaterImpact: the explosives' part on the server, in NTM's own impact (MixinWaterImpact, MixinExplosiveBullet):
// - b75_inc in water gives a small blast, about a quarter of the explosive bolt's, and no fire;
// - what sets alight (phosphorus, flares) does not under water: it hits as a plain bullet;
// - in water an explosive bullet goes off at the first touch of another bullet or explosive, a sinking one too;
//   pellets of one shot do not set each other off, and in the air bullets pass each other as in NTM;
// - one stopped by water sinks (WaterTick) and goes off where it touches, if NTM's life of the round lasts that long.
public final class WaterImpact {
    private WaterImpact() {
    }

    // the bullet NTM's onImpact can be called on from here (MixinWaterBullet)
    public interface Hits {
        void gunfeel$impact(RayTraceResult hit);
    }

    // b75_inc's blast under water: of b75_exp's (Lego.standardExplode, range 5, the bullet's damage) a quarter of the
    // damage and half the range, a weak push
    static final float WEAK_RANGE = 2.5F, WEAK_DAMAGE = 0.25F, WEAK_PUSH = 0.25F;
    // how far apart two bullets touch: NTM's bullets are 0.5 wide, a hit is counted to the boxes' edge
    static final double TOUCH = 0.1;

    // NTM's onImpact (MixinWaterImpact): `original` runs it as it is; what water changes is its onImpact lambda (lambda)
    public static void impact(EntityBulletBaseMK4 bullet, RayTraceResult hit, Runnable original) {
        if (bullet.world.isRemote || bullet.config == null || hit == null || !WaterSettings.ballistics()) {
            original.run();
            return;
        }
        EntityBulletBaseMK4 previous = WaterBlast.BULLET.get();
        WaterBlast.BULLET.set(bullet);
        try {
            original.run();
            if (bullet.isDead && ((WaterBullet) bullet).gunfeel$goneAt() == null) ((WaterBullet) bullet).gunfeel$goneAt(hit.hitVec);
        } finally {
            if (previous == null) WaterBlast.BULLET.remove();
            else WaterBlast.BULLET.set(previous);
        }
    }

    // NTM's onImpact calls the config's onImpact lambda (MixinExplosiveBullet): in water b75_inc's goes off as a small
    // blast, and what sets alight does not (the rest of NTM's impact, onRicochet and onEntityHit, goes on as for a
    // plain bullet). The lambda to call instead, or null for NTM's own. Inside NTM's onImpact, so the mod's own steps
    // around it (the damage x f^2 where the creature is, the piercing, the block rules) count these too.
    public static java.util.function.BiConsumer<Object, Object> lambda(final EntityBulletBaseMK4 bullet, final RayTraceResult hit) {
        if (bullet.world.isRemote || bullet.config == null || hit == null || !WaterSettings.ballistics()) return null;
        BulletConfig c = bullet.config;
        WaterClass.Fuze fuze = WaterClass.Fuze.of(c);
        if (fuze == WaterClass.Fuze.WEAK && wet(bullet, hit)) return (x, h) -> {
            if (TestLog.server) TestLog.s(bullet.getEntityId(), "water", "weak", "at", v(hit.hitVec));
            weakBlast(bullet, hit);
            bullet.setDead();
        };
        if (fuze == WaterClass.Fuze.NONE && WaterClass.incendiary(c) && wet(bullet, hit)) return (x, h) -> {
            if (TestLog.server) TestLog.s(bullet.getEntityId(), "water", "doused", "at", v(hit.hitVec));
        };
        return null;
    }

    // the point hit is in water or on its surface (or the surface fuze's own hit)
    public static boolean wet(EntityBulletBaseMK4 bullet, RayTraceResult hit) {
        if (WaterTick.surfaceHit(bullet, hit)) return true;
        return hit.hitVec != null && wet(bullet, hit.hitVec);
    }

    public static boolean wet(Entity e, Vec3d at) {
        WaterBlast.Place p = WaterBlast.place(e.world, at);
        return p != null && p.inWater() && p.liquid() != WaterEvents.Liquid.LAVA;
    }

    static void weakBlast(EntityBulletBaseMK4 bullet, RayTraceResult hit) {
        Vec3d at = hit.hitVec;
        ExplosionVNT vnt = new ExplosionVNT(bullet.world, at.x, at.y, at.z, WEAK_RANGE, bullet.getThrower());
        vnt.setEntityProcessor(new EntityProcessorCrossSmooth(1.0, bullet.damage * WEAK_DAMAGE)
                .setupPiercing(bullet.config.armorThresholdNegation, bullet.config.armorPiercingPercent).setKnockback(WEAK_PUSH));
        vnt.setPlayerProcessor(new PlayerProcessorStandard());
        vnt.setSFX(new ExplosionEffectTiny());
        vnt.explode();
    }

    // does this bullet go off at a touch where it is now: an explosive one always, b75_inc in water
    static boolean goesOff(EntityBulletBaseMK4 b) {
        WaterClass.Fuze f = WaterClass.Fuze.of(b.config);
        return f == WaterClass.Fuze.IMPACT || f == WaterClass.Fuze.WEAK && wet(b, b.getPositionVector());
    }

    // pellets of one shot: the same shooter, the same config, made the same tick (WaterBullet.gunfeel$shot). Not the
    // ticks they lived: one pellet's touch runs after its own tick and before the next one's, a tick apart
    public static boolean sameShot(EntityBulletBaseMK4 a, EntityBulletBaseMK4 b) {
        return a.config == b.config && ((WaterBullet) a).gunfeel$shot() == ((WaterBullet) b).gunfeel$shot() && a.getThrower() == b.getThrower();
    }

    // for the stand (BoomScenario): the searches for other bullets, the pairs of one shot left alone and the touches
    // that set one off, since the game started
    public static int searches, sameShots, touches;

    // after the bullet's tick (MixinThrowableClip), the server: did its path from `from` touch another bullet, and was
    // either an explosive. Only bullets that have met water touch: in the air NTM's bullets pass each other, and the
    // search is not made
    public static void touch(EntityBulletBaseMK4 b, Vec3d from) {
        // a bullet gone this tick (water stopped it, it hit a block) still met what lay on its path
        if (b.world.isRemote || b.config == null || !WaterSettings.ballistics() || !WaterSettings.fuze() || !WaterBullet.met(b)) return;
        // one gone at an impact: up to where it hit (NTM has moved it on past it), not up to where it is
        Vec3d gone = ((WaterBullet) b).gunfeel$goneAt();
        Vec3d to = b.isDead && gone != null ? gone : b.getPositionVector();
        AxisAlignedBB area = new AxisAlignedBB(from, to).grow(1.0);
        boolean mine = !b.isDead && goesOff(b);
        searches++;
        for (EntityBulletBaseMK4 o : b.world.getEntitiesWithinAABB(EntityBulletBaseMK4.class, area,
                o -> o != b && o != null && !o.isDead && o.config != null && WaterBullet.met(o))) {
            boolean theirs = goesOff(o);
            if (!mine && !theirs) continue;
            AxisAlignedBB box = o.getEntityBoundingBox().grow(TOUCH);
            Vec3d at = inside(box, from) ? from : clip(box, from, to);
            if (at == null) continue;
            if (sameShot(b, o)) {
                sameShots++;
                continue;
            }
            touches++;
            if (TestLog.server) TestLog.s(b.getEntityId(), "water", "touch", "other", o.getEntityId(), "at", v(at), "mine", mine, "theirs", theirs);
            if (mine) ((Hits) b).gunfeel$impact(new RayTraceResult(o, at));
            if (theirs && !o.isDead) ((Hits) o).gunfeel$impact(new RayTraceResult(b, at));
            if (b.isDead) return;
        }
    }

    // 26.2's AABB.contains: min inclusive, max exclusive
    private static boolean inside(AxisAlignedBB box, Vec3d p) {
        return p.x >= box.minX && p.x < box.maxX && p.y >= box.minY && p.y < box.maxY && p.z >= box.minZ && p.z < box.maxZ;
    }

    // 26.2's AABB.clip(from, to), or the end when it lies inside
    private static Vec3d clip(AxisAlignedBB box, Vec3d from, Vec3d to) {
        RayTraceResult r = box.calculateIntercept(from, to);
        if (r != null) return r.hitVec;
        return inside(box, to) ? to : null;
    }

    // a fire grenade's or rocket's fire (XFactory40mm / XFactoryRocket.spawnFire, MixinWaterFire): in water only its
    // blast, no lingering fire and no fire blocks; the Daybreaker's (XFactoryFlamer.spawnFire, MixinWaterFireFlamer): no
    // lingering fire
    public static boolean noFire(EntityBulletBaseMK4 bullet, RayTraceResult hit) {
        return !bullet.world.isRemote && hit != null && WaterSettings.ballistics() && wet(bullet, hit);
    }

    public static void fireBlast(EntityBulletBaseMK4 bullet, RayTraceResult hit) {
        // NTM's own guard in the first ticks: XFactory40mm's spares only the shooter, CE's XFactoryRocket any mob (only
        // rockets go through its spawnFire)
        if (hit.typeOfHit == RayTraceResult.Type.ENTITY && bullet.ticksExisted < 3
                && (hit.entityHit == bullet.getThrower() || WaterClass.of(bullet.config) == WaterClass.ROCKET)) return;
        if (TestLog.server) TestLog.s(bullet.getEntityId(), "water", "nofire", "at", v(hit.hitVec));
        com.hbm.items.weapon.sedna.factory.Lego.standardExplode(bullet, hit, 3.0F);
        bullet.setDead();
    }

    static String v(Vec3d p) {
        return p == null ? "-" : String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.4f", p.x, p.y, p.z);
    }
}
