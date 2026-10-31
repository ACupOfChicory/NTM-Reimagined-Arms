package acup.gunfeel;

import java.lang.ref.WeakReference;
import java.util.UUID;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import acup.gunfeel.phys.Vec3;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MovingObjectPosition;

// port: from 26.2's HeadDamage (e284339). A bullet's or beam's hit on a creature: the head zone of its model (ModelHits)
// gives x1.75, a checked limb x0.5, the rest x1, in place of NTM's height rule (BulletConfig.headshotMult). Without a
// pose of the model (no client has sent one, or model hitboxes off) ModelHits keeps NTM 1.7.10's height rule. The
// last decision is kept for the bullet's log line, also after a killing hit. contactDamage: an explosive bullet's
// share at its contact (ExplosiveDamage).
public final class HeadDamage {
    private static final class Last {
        final WeakReference<EntityBulletBaseMK4> bullet;
        final UUID target;
        final ModelHeads.Hit hit;

        Last(EntityBulletBaseMK4 bullet, UUID target, ModelHeads.Hit hit) {
            this.bullet = new WeakReference<EntityBulletBaseMK4>(bullet);
            this.target = target;
            this.hit = hit;
        }
    }

    private static final ThreadLocal<Last> LAST = new ThreadLocal<Last>();

    private HeadDamage() {
    }

    // an explosive bullet's damage where it touched the creature, times the zone's multiplier
    public static float contactDamage(float damage, EntityBulletBaseMK4 bullet, EntityLivingBase target, MovingObjectPosition impact) {
        ModelHeads.Hit hit = hit(target, impact);
        LAST.set(new Last(bullet, target.getUniqueID(), hit));
        return hit.applied() ? damage * ModelHeads.multiplier(hit.head(), hit.source().equals("limbs")) : damage;
    }

    public static float beamDamage(float damage, EntityLivingBase target, MovingObjectPosition impact) {
        ModelHeads.Hit hit = hit(target, impact);
        return hit.applied() ? damage * ModelHeads.multiplier(hit.head(), hit.source().equals("limbs")) : damage;
    }

    // NTM's damage was the bullet's, times headshotMult when its height rule said head: the bullet's, times ours
    public static float damage(EntityBulletBaseMK4 bullet, EntityLivingBase target, MovingObjectPosition impact, float original) {
        ModelHeads.Hit hit = hit(target, impact);
        LAST.set(new Last(bullet, target.getUniqueID(), hit));
        if (!hit.applied()) return original;
        return bullet.damage * ModelHeads.multiplier(hit.head(), hit.source().equals("limbs"));
    }

    private static ModelHeads.Hit hit(EntityLivingBase target, MovingObjectPosition impact) {
        Vec3 at = impact == null || impact.hitVec == null ? null : new Vec3(impact.hitVec.xCoord, impact.hitVec.yCoord, impact.hitVec.zCoord);
        ModelHeads.Hit hit = ModelHits.head(target, at);
        if (hit.applied() && (!hit.head() || hit.source().equals("height")) && ModelHits.limb(target, at))
            return new ModelHeads.Hit(true, false, "limbs", "проверенная конечность выше высотной догадки головы", 1F);
        return hit;
    }

    public static ModelHeads.Hit last(EntityBulletBaseMK4 bullet, Entity target, Vec3 point) {
        Last last = LAST.get();
        if (last != null && last.bullet.get() == bullet && last.target.equals(target.getUniqueID())) return last.hit;
        return ModelHits.head(target, point);
    }
}
