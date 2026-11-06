package acup.gunfeel.water;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.explosion.vanillant.standard.EntityProcessorCrossSmooth;
import com.hbm.explosion.vanillant.standard.ExplosionEffectTiny;
import com.hbm.explosion.vanillant.standard.PlayerProcessorStandard;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.TestLog;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// The explosives' part on the server, in NTM's own impact (MixinWaterImpact, MixinExplosiveBullet):
// - b75_inc in water gives a small blast, about a quarter of the explosive bolt's, and no fire;
// - what sets alight (phosphorus, flares) does not under water: it hits as a plain bullet;
// - in water an explosive bullet goes off at the first touch of another bullet or explosive, a sinking one too;
//   pellets of one shot do not set each other off, and in the air bullets pass each other as in NTM;
// - one stopped by water sinks (WaterTick) and goes off where it touches, if NTM's life of the round lasts that long.
public final class WaterImpact {
    private WaterImpact() {
    }

    // the bullet NTM's onImpact can be called on from here
    public interface Hits {
        void gunfeel$impact(HitResult hit);
    }

    // b75_inc's blast under water: of b75_exp's (Lego.standardExplode, range 5, the bullet's damage) a quarter of the
    // damage and half the range, a weak push
    static final float WEAK_RANGE = 2.5F, WEAK_DAMAGE = 0.25F, WEAK_PUSH = 0.25F;
    // how far apart two bullets touch: NTM's bullets are 0.5 wide, a hit is counted to the boxes' edge
    static final double TOUCH = 0.1;

    // NTM's onImpact (MixinWaterImpact): `original` runs it as it is; what water changes is its onImpact lambda (lambda)
    public static void impact(EntityBulletBaseMK4 bullet, HitResult hit, Runnable original) {
        if (bullet.level().isClientSide() || bullet.config == null || !WaterSettings.ballistics()) {
            original.run();
            return;
        }
        EntityBulletBaseMK4 previous = WaterBlast.BULLET.get();
        WaterBlast.BULLET.set(bullet);
        try {
            original.run();
        } finally {
            if (previous == null) WaterBlast.BULLET.remove();
            else WaterBlast.BULLET.set(previous);
        }
    }

    // NTM's onImpact calls the config's onImpact lambda (MixinExplosiveBullet): in water b75_inc's goes off as a small
    // blast, and what sets alight does not (the rest of NTM's impact, onRicochet and onEntityHit, goes on as for a
    // plain bullet). The lambda to call instead, or null for NTM's own. Inside NTM's onImpact, so the mod's own steps
    // around it (the damage × f² where the creature is, the piercing, the block rules) count these too.
    public static java.util.function.@org.jspecify.annotations.Nullable BiConsumer<Object, Object> lambda(EntityBulletBaseMK4 bullet, HitResult hit) {
        if (bullet.level().isClientSide() || bullet.config == null || !WaterSettings.ballistics()) return null;
        BulletConfig c = bullet.config;
        WaterClass.Fuze fuze = WaterClass.Fuze.of(c);
        if (fuze == WaterClass.Fuze.WEAK && wet(bullet, hit)) return (x, h) -> {
            if (TestLog.server) TestLog.s(bullet.getId(), "water", "weak", "at", hit.getLocation());
            weakBlast(bullet, hit);
            bullet.discard();
        };
        if (fuze == WaterClass.Fuze.NONE && WaterClass.incendiary(c) && wet(bullet, hit)) return (x, h) -> {
            if (TestLog.server) TestLog.s(bullet.getId(), "water", "doused", "at", hit.getLocation());
        };
        return null;
    }

    // the point hit is in water or on its surface (or the surface fuze's own hit)
    public static boolean wet(EntityBulletBaseMK4 bullet, HitResult hit) {
        if (WaterTick.surfaceHit(bullet, hit)) return true;
        return wet(bullet, hit.getLocation());
    }

    public static boolean wet(Entity e, Vec3 at) {
        WaterBlast.Place p = WaterBlast.place(e.level(), at);
        return p != null && p.inWater() && p.liquid() != WaterEvents.Liquid.LAVA;
    }

    static void weakBlast(EntityBulletBaseMK4 bullet, HitResult hit) {
        Vec3 at = hit.getLocation();
        ExplosionVNT vnt = new ExplosionVNT(bullet.level(), at.x, at.y, at.z, WEAK_RANGE, bullet.getThrower());
        vnt.setEntityProcessor(new EntityProcessorCrossSmooth(1.0, bullet.damage * WEAK_DAMAGE)
                .setupPiercing(bullet.config.armorThresholdNegation, bullet.config.armorPiercingPercent).setKnockback(WEAK_PUSH));
        vnt.setPlayerProcessor(new PlayerProcessorStandard());
        vnt.setSFX(new ExplosionEffectTiny());
        vnt.explode();
    }

    // does this bullet go off at a touch where it is now: an explosive one always, b75_inc in water
    static boolean goesOff(EntityBulletBaseMK4 b) {
        WaterClass.Fuze f = WaterClass.Fuze.of(b.config);
        return f == WaterClass.Fuze.IMPACT || f == WaterClass.Fuze.WEAK && wet(b, b.position());
    }

    // pellets of one shot: the same shooter, the same config, made the same tick (WaterBullet.gunfeel$shot). Not the
    // ticks they lived: one pellet's touch runs after its own tick and before the next one's, a tick apart
    public static boolean sameShot(EntityBulletBaseMK4 a, EntityBulletBaseMK4 b) {
        return a.config == b.config && ((WaterBullet) a).gunfeel$shot() == ((WaterBullet) b).gunfeel$shot() && a.getThrower() == b.getThrower();
    }

    // for ExplosiveCheck: the searches for other bullets, the pairs of one shot left alone and the touches that set one
    // off, since the server started
    public static int searches, sameShots, touches;

    // after the bullet's tick (MixinThrowableHit), the server: did its path from `from` touch another bullet, and was
    // either an explosive. Only bullets that have met water touch: in the air NTM's bullets pass each other, and the
    // search is not made
    public static void touch(EntityBulletBaseMK4 b, Vec3 from) {
        // a bullet gone this tick (water stopped it, it hit a block) still met what lay on its path
        if (b.level().isClientSide() || b.config == null || !WaterSettings.ballistics() || !WaterSettings.fuze() || !WaterBullet.met(b)) return;
        Vec3 to = b.position();
        AABB area = new AABB(from, to).inflate(1.0);
        boolean mine = !b.isRemoved() && goesOff(b);
        searches++;
        for (EntityBulletBaseMK4 o : b.level().getEntitiesOfClass(EntityBulletBaseMK4.class, area,
                o -> o != b && o.isAlive() && o.config != null && WaterBullet.met(o))) {
            boolean theirs = goesOff(o);
            if (!mine && !theirs) continue;
            AABB box = o.getBoundingBox().inflate(TOUCH);
            Vec3 at = box.contains(from) ? from : box.clip(from, to).orElse(box.contains(to) ? to : null);
            if (at == null) continue;
            if (sameShot(b, o)) {
                sameShots++;
                continue;
            }
            touches++;
            if (TestLog.server) TestLog.s(b.getId(), "water", "touch", "other", o.getId(), "at", at, "mine", mine, "theirs", theirs);
            if (mine) ((Hits) b).gunfeel$impact(new EntityHitResult(o, at));
            if (theirs && !o.isRemoved()) ((Hits) o).gunfeel$impact(new EntityHitResult(b, at));
            if (b.isRemoved()) return;
        }
    }

    // a fire grenade's or rocket's fire (XFactory40mm / XFactoryRocket.spawnFire, MixinWaterFire / MixinWaterFireRocket):
    // in water only its blast, no lingering fire and no fire blocks
    public static boolean noFire(EntityBulletBaseMK4 bullet, HitResult hit) {
        return !bullet.level().isClientSide() && WaterSettings.ballistics() && wet(bullet, hit);
    }

    // NTM's own guard in the first 3 ticks: the grenade's (XFactory40mm) spares only its shooter, the rocket's
    // (XFactoryRocket, anyMob) any mob it hits
    public static void fireBlast(EntityBulletBaseMK4 bullet, HitResult hit, boolean anyMob) {
        if (hit instanceof EntityHitResult e && bullet.tickCount < 3 && (anyMob || e.getEntity() == bullet.getThrower())) return;
        if (TestLog.server) TestLog.s(bullet.getId(), "water", "nofire", "at", hit.getLocation());
        com.hbm.items.weapon.sedna.factory.Lego.standardExplode(bullet, hit, 3.0F);
        bullet.discard();
    }
}
