package acup.gunfeel.water;

import java.util.List;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.entity.projectile.EntityThrowableNT;
import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.explosion.vanillant.standard.EntityProcessorCrossSmooth;
import com.hbm.explosion.vanillant.standard.ExplosionEffectTiny;
import com.hbm.explosion.vanillant.standard.PlayerProcessorStandard;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.TestLog;
import acup.gunfeel.phys.Vec3;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;

// The explosives' part on the server, in NTM's own impact (MixinWaterImpact, MixinExplosiveBullet):
// - b75_inc in water gives a small blast, about a quarter of the explosive bolt's, and no fire;
// - what sets alight (phosphorus, flares) does not under water: it hits as a plain bullet;
// - in water an explosive bullet goes off at the first touch of another bullet or explosive, a sinking one too;
//   pellets of one shot do not set each other off, and in the air bullets pass each other as in NTM;
// - one stopped by water sinks (WaterTick) and goes off where it touches, if NTM's life of the round lasts that long.
// port: 26.2's WaterImpact (a1f618b) on NTM 1.7.10: a hit is a MovingObjectPosition, the box test AxisAlignedBB's
// isVecInside and calculateIntercept.
public final class WaterImpact {
    private WaterImpact() {
    }

    // the bullet NTM's onImpact can be called on from here
    public interface Hits {
        void gunfeel$impact(MovingObjectPosition hit);
    }

    // b75_inc's blast under water: of b75_exp's (Lego.standardExplode, range 5, the bullet's damage) a quarter of the
    // damage and half the range, a weak push
    static final float WEAK_RANGE = 2.5F, WEAK_DAMAGE = 0.25F, WEAK_PUSH = 0.25F;
    // how far apart two bullets touch: NTM's bullets are 0.5 wide, a hit is counted to the boxes' edge
    static final double TOUCH = 0.1;

    // NTM's onImpact (MixinWaterImpact): `original` runs it as it is; what water changes is its onImpact lambda (lambda)
    public static void impact(EntityBulletBaseMK4 bullet, MovingObjectPosition hit, Runnable original) {
        if (bullet.worldObj.isRemote || bullet.config == null || !WaterSettings.ballistics()) {
            original.run();
            return;
        }
        EntityBulletBaseMK4 previous = WaterBlast.BULLET.get();
        WaterBlast.BULLET.set(bullet);
        try {
            original.run();
            if (bullet.isDead && ((WaterBullet) bullet).gunfeel$goneAt() == null && hit.hitVec != null)
                ((WaterBullet) bullet).gunfeel$goneAt(net.minecraft.util.Vec3.createVectorHelper(hit.hitVec.xCoord, hit.hitVec.yCoord, hit.hitVec.zCoord));
        } finally {
            if (previous == null) WaterBlast.BULLET.remove();
            else WaterBlast.BULLET.set(previous);
        }
    }

    // NTM's onImpact calls the config's onImpact lambda (MixinExplosiveBullet): in water b75_inc's goes off as a small
    // blast, and what sets alight does not (the rest of NTM's impact, onRicochet and onEntityHit, goes on as for a
    // plain bullet). The lambda to call instead, or null for NTM's own. Inside NTM's onImpact, so the mod's own steps
    // around it (the damage × f² where the creature is, the piercing, the block rules) count these too.
    public static java.util.function.BiConsumer<Object, Object> lambda(EntityBulletBaseMK4 bullet, MovingObjectPosition hit) {
        if (bullet.worldObj.isRemote || bullet.config == null || !WaterSettings.ballistics()) return null;
        BulletConfig c = bullet.config;
        WaterClass.Fuze fuze = WaterClass.Fuze.ofConfig(c);
        if (fuze == WaterClass.Fuze.WEAK && wet(bullet, hit)) return (x, h) -> {
            if (TestLog.server) TestLog.s(bullet.getEntityId(), "water", "weak", "at", at(hit));
            weakBlast(bullet, hit);
            bullet.setDead();
        };
        if (fuze == WaterClass.Fuze.NONE && WaterClass.incendiaryConfig(c) && wet(bullet, hit)) return (x, h) -> {
            if (TestLog.server) TestLog.s(bullet.getEntityId(), "water", "doused", "at", at(hit));
        };
        return null;
    }

    static Vec3 at(MovingObjectPosition hit) {
        return hit.hitVec == null ? Vec3.ZERO : new Vec3(hit.hitVec.xCoord, hit.hitVec.yCoord, hit.hitVec.zCoord);
    }

    // the point hit is in water or on its surface (or the surface fuze's own hit)
    public static boolean wet(EntityBulletBaseMK4 bullet, MovingObjectPosition hit) {
        if (WaterTick.surfaceHit(bullet, hit)) return true;
        return hit.hitVec != null && wet(bullet, at(hit));
    }

    public static boolean wet(Entity e, Vec3 at) {
        WaterBlast.Place p = WaterBlast.place(e.worldObj, at);
        return p != null && p.inWater() && p.liquid() != WaterEvents.Liquid.LAVA;
    }

    static void weakBlast(EntityBulletBaseMK4 bullet, MovingObjectPosition hit) {
        Vec3 at = at(hit);
        ExplosionVNT vnt = new ExplosionVNT(bullet.worldObj, at.x, at.y, at.z, WEAK_RANGE, bullet.getThrower());
        vnt.setEntityProcessor(new EntityProcessorCrossSmooth(1.0, bullet.damage * WEAK_DAMAGE)
                .setupPiercing(bullet.config.armorThresholdNegation, bullet.config.armorPiercingPercent).setKnockback(WEAK_PUSH));
        vnt.setPlayerProcessor(new PlayerProcessorStandard());
        vnt.setSFX(new ExplosionEffectTiny());
        vnt.explode();
    }

    // does this bullet go off at a touch where it is now: an explosive one always, b75_inc in water
    static boolean goesOff(EntityThrowableNT b) {
        WaterClass.Fuze f = ((WaterBullet) b).gunfeel$waterFuze();
        return f == WaterClass.Fuze.IMPACT || f == WaterClass.Fuze.WEAK && wet(b, new Vec3(b.posX, b.posY, b.posZ));
    }

    // pellets of one shot: the same shooter, the same config, made the same tick (WaterBullet.gunfeel$shot). Not the
    // ticks they lived: one pellet's touch runs after its own tick and before the next one's, a tick apart
    public static boolean sameShot(EntityThrowableNT a, EntityThrowableNT b) {
        WaterBullet wa = (WaterBullet) a, wb = (WaterBullet) b;
        return wa.gunfeel$waterConfig() == wb.gunfeel$waterConfig() && wa.gunfeel$shot() == wb.gunfeel$shot() && a.getThrower() == b.getThrower();
    }

    // for ExplosiveCheck: the searches for other bullets, the pairs of one shot left alone and the touches that set one
    // off, since the server started
    public static int searches, sameShots, touches;

    // after the bullet's tick (MixinThrowableClip), the server: did its path from (fx, fy, fz) touch another bullet, and
    // was either an explosive. Only bullets that have met water touch: in the air NTM's bullets pass each other, and
    // the search is not made. port: NTM's bullets and the old ones with an impact fuze (WaterTick.covers), each with
    // the other
    @SuppressWarnings("unchecked")
    public static void touch(EntityThrowableNT b, double fx, double fy, double fz) {
        // a bullet gone this tick (water stopped it, it hit a block) still met what lay on its path
        if (b.worldObj.isRemote || ((WaterBullet) b).gunfeel$waterConfig() == null || !WaterSettings.ballistics() || !WaterSettings.fuze()
                || !WaterBullet.met(b)) return;
        net.minecraft.util.Vec3 from = net.minecraft.util.Vec3.createVectorHelper(fx, fy, fz);
        // one dead at an impact: up to where it hit (NTM has moved it on past it), not up to where it is
        net.minecraft.util.Vec3 gone = ((WaterBullet) b).gunfeel$goneAt();
        net.minecraft.util.Vec3 to = b.isDead && gone != null ? gone
                : net.minecraft.util.Vec3.createVectorHelper(b.posX, b.posY, b.posZ);
        AxisAlignedBB area = AxisAlignedBB.getBoundingBox(Math.min(fx, to.xCoord), Math.min(fy, to.yCoord), Math.min(fz, to.zCoord),
                Math.max(fx, to.xCoord), Math.max(fy, to.yCoord), Math.max(fz, to.zCoord)).expand(1.0, 1.0, 1.0);
        boolean mine = !b.isDead && goesOff(b);
        searches++;
        for (EntityThrowableNT o : (List<EntityThrowableNT>) b.worldObj.getEntitiesWithinAABB(EntityThrowableNT.class, area)) {
            if (o == b || o.isDead || !WaterTick.covers(o) || ((WaterBullet) o).gunfeel$waterConfig() == null || !WaterBullet.met(o)) continue;
            boolean theirs = goesOff(o);
            if (!mine && !theirs) continue;
            AxisAlignedBB box = o.boundingBox.expand(TOUCH, TOUCH, TOUCH);
            net.minecraft.util.Vec3 at = inside(box, from) ? from : null;
            if (at == null) {
                MovingObjectPosition clip = box.calculateIntercept(from, to);
                at = clip != null ? clip.hitVec : inside(box, to) ? to : null;
            }
            if (at == null) continue;
            if (sameShot(b, o)) {
                sameShots++;
                continue;
            }
            touches++;
            if (TestLog.server) TestLog.s(b.getEntityId(), "water", "touch", "other", o.getEntityId(), "at", new Vec3(at.xCoord, at.yCoord, at.zCoord),
                    "mine", mine, "theirs", theirs);
            if (mine) ((Hits) b).gunfeel$impact(new MovingObjectPosition(o, at));
            if (theirs && !o.isDead) ((Hits) o).gunfeel$impact(new MovingObjectPosition(b, at));
            if (b.isDead) return;
        }
    }

    // 26.2's AABB.contains: min inclusive, max exclusive (1.7.10's isVecInside leaves both out)
    private static boolean inside(AxisAlignedBB box, net.minecraft.util.Vec3 p) {
        return p.xCoord >= box.minX && p.xCoord < box.maxX && p.yCoord >= box.minY && p.yCoord < box.maxY && p.zCoord >= box.minZ
                && p.zCoord < box.maxZ;
    }

    // a fire grenade's or rocket's fire (XFactory40mm / XFactoryRocket.spawnFire, MixinWaterFire / MixinWaterFireRocket): in water only its
    // blast, no lingering fire and no fire blocks; a flame's, the Daybreaker's too (XFactoryFlamer.spawnFire,
    // MixinWaterFireFlamer): no lingering fire
    public static boolean noFire(EntityBulletBaseMK4 bullet, MovingObjectPosition hit) {
        return !bullet.worldObj.isRemote && WaterSettings.ballistics() && wet(bullet, hit);
    }

    // NTM's own guard, in the first 3 ticks: the grenade's (XFactory40mm) spares only its shooter, the rocket's
    // (XFactoryRocket, anyMob) any mob it hits
    // port: 26.2's fireBlast spares only the shooter for both
    public static void fireBlast(EntityBulletBaseMK4 bullet, MovingObjectPosition hit, boolean anyMob) {
        if (hit.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY && bullet.ticksExisted < 3
                && (anyMob || hit.entityHit == bullet.getThrower())) return;
        if (TestLog.server) TestLog.s(bullet.getEntityId(), "water", "nofire", "at", at(hit));
        com.hbm.items.weapon.sedna.factory.Lego.standardExplode(bullet, hit, 3.0F);
        bullet.setDead();
    }
}
