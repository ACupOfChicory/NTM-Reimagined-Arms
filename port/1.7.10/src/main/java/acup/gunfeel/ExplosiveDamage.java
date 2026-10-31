package acup.gunfeel;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.util.EntityDamageUtil;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MovingObjectPosition;

// port: 26.2's ExplosiveDamage (e284339). An explosive bullet's damage: at its contact 60 % of the bullet's damage,
// times the zone it hit (HeadDamage.contactDamage), and 40 % of NTM's blast around. The context lives only inside the
// bullet's own handler (onImpact, onUpdate), not around every explosion of the world. A heavy round (40 mm, rocket)
// that went off on a block tells the players around (TracerNet.Impact) for its scorch (Tracers.impact).
public final class ExplosiveDamage {
    static final float CONTACT = .6F, BLAST = .4F;

    public static final class Context {
        final EntityBulletBaseMK4 bullet;
        final MovingObjectPosition impact;
        boolean exploded;
        final int depth;

        Context(EntityBulletBaseMK4 bullet, MovingObjectPosition impact) {
            this.bullet = bullet;
            this.impact = impact;
            this.depth = DEPTH.get();
        }
    }

    public static final class Blast {
        final Context context;
        final float base;
        final ExplosionVNT explosion;
        boolean contactPaid;

        Blast(Context context, float base, ExplosionVNT explosion) {
            this.context = context;
            this.base = base;
            this.explosion = explosion;
        }
    }

    public interface Processor {
        void gunfeel$bind(ExplosionVNT explosion);

        Blast gunfeel$blast();
    }

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<Context>();
    private static final ThreadLocal<Boolean> SUPPRESS = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };
    private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 0;
        }
    };

    // the scorches announced (BulletCheck counts them)
    static int announced;

    private ExplosiveDamage() {
    }

    public static boolean suppressed() {
        return SUPPRESS.get();
    }

    public static Context current() {
        return CURRENT.get();
    }

    // EntityBulletBaseMK4.onImpact: its config's onImpact, inside the context; true when the scorch was announced
    public static boolean impact(BiConsumer<Object, Object> callback, EntityBulletBaseMK4 bullet, MovingObjectPosition hit, boolean announce) {
        if (bullet.worldObj.isRemote) {
            callback.accept(bullet, hit);
            return false;
        }
        int previousDepth = DEPTH.get();
        Context previous = CURRENT.get();
        CURRENT.set(new Context(bullet, hit));
        try {
            callback.accept(bullet, hit);
            // only a fuze that went off: no mark for a bounce or the shooter's own cover
            if (announce && bullet.isDead && ExplosiveAmmo.heavy(bullet.config) && hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                    && hit.hitVec != null) {
                double dx = bullet.motionX, dy = bullet.motionY, dz = bullet.motionZ;
                if (dx * dx + dy * dy + dz * dz < 1e-12) {
                    dx = -BlockRay.stepX(hit.sideHit);
                    dy = -BlockRay.stepY(hit.sideHit);
                    dz = -BlockRay.stepZ(hit.sideHit);
                }
                double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
                announced++;
                TracerNet.send(bullet, new TracerNet.Impact(bullet.getEntityId(), bullet.config.id, hit.blockX, hit.blockY, hit.blockZ, hit.sideHit,
                        hit.hitVec.xCoord, hit.hitVec.yCoord, hit.hitVec.zCoord, dx / l, dy / l, dz / l));
                return true;
            }
            return false;
        } finally {
            DEPTH.set(previousDepth);
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    // EntityBulletBaseMK4.onUpdate: its config's onUpdate (a proximity fuze), inside the context
    public static void update(Consumer<Object> callback, EntityBulletBaseMK4 bullet) {
        if (bullet.worldObj.isRemote) {
            callback.accept(bullet);
            return;
        }
        Context previous = CURRENT.get();
        CURRENT.set(new Context(bullet, null));
        int previousDepth = DEPTH.get();
        try {
            callback.accept(bullet);
        } finally {
            DEPTH.set(previousDepth);
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static void enter() {
        if (CURRENT.get() != null) DEPTH.set(DEPTH.get() + 1);
    }

    public static void exit() {
        if (CURRENT.get() != null) DEPTH.set(Math.max(0, DEPTH.get() - 1));
    }

    public static Blast bind(ExplosionVNT explosion, float base) {
        Context context = CURRENT.get();
        if (context == null || suppressed() || DEPTH.get() != context.depth || context.bullet.config == null
                || context.bullet.worldObj != explosion.world || context.bullet.getThrower() != explosion.exploder) return null;
        context.exploded = true;
        return new Blast(context, base, explosion);
    }

    static float total(float area, float base, float multiplier, boolean direct) {
        return area * BLAST + (direct ? base * CONTACT * multiplier : 0);
    }

    public static float damage(Blast blast, Entity target, float original) {
        if (blast == null) return original;
        MovingObjectPosition impact = blast.context.impact;
        boolean direct = impact != null && impact.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY && impact.entityHit == target
                && !blast.contactPaid;
        float multiplier = 1;
        if (direct) {
            blast.contactPaid = true;
            if (target instanceof EntityLivingBase) multiplier = HeadDamage.contactDamage(1, blast.context.bullet, (EntityLivingBase) target, impact);
        }
        float base = blast.base * (target == blast.explosion.exploder ? .5F : 1);
        return total(original, base, multiplier, direct);
    }

    public static boolean attack(EntityLivingBase target, DamageSource source, float damage, boolean first, boolean second, double knockback,
            float threshold, float piercing) {
        boolean previous = SUPPRESS.get();
        SUPPRESS.set(true);
        try {
            return EntityDamageUtil.attackEntityFromNT(target, source, damage, first, second, knockback, threshold, piercing);
        } finally {
            SUPPRESS.set(previous);
        }
    }

    public static float extra(EntityLivingBase target, float damage) {
        Context context = CURRENT.get();
        if (suppressed() || context == null || DEPTH.get() != context.depth || !context.exploded || context.impact == null
                || context.impact.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY || context.impact.entityHit != target) return damage;
        return HeadDamage.contactDamage(damage, context.bullet, target, context.impact);
    }

    public static void finish(Blast blast) {
        if (blast == null || blast.contactPaid) return;
        MovingObjectPosition hit = blast.context.impact;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY || !(hit.entityHit instanceof EntityLivingBase)) return;
        EntityLivingBase target = (EntityLivingBase) hit.entityHit;
        if (!target.isEntityAlive()) return;
        // the visible part of a big model may be outside the explosion's vanilla box
        blast.contactPaid = true;
        EntityBulletBaseMK4 bullet = blast.context.bullet;
        float damage = HeadDamage.contactDamage(blast.base * CONTACT, bullet, target, hit);
        attack(target, BulletConfig.getDamage(bullet, bullet.getThrower(), bullet.config.dmgClass), damage, true, true, 0,
                bullet.config.armorThresholdNegation, bullet.config.armorPiercingPercent);
    }
}
