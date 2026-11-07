package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBulletBaseNT;
import com.hbm.handler.BulletConfiguration;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.LegacyBullets;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MovingObjectPosition;

// port only (26.2 has no such bullet), as the 1.12.2 port's MixinOldBulletBase: NTM 1.7.10's old EntityBulletBaseNT,
// fired by the masked man's laser (orbs, rockets, tracers), the death bolt and meteor (GunNPCFactory), the UFO and BOT
// Prime. Server, npcShots: its hit counts where it met the creature's model, head x1.75, limbs x0.5, as
// MixinLegacyBullet does for EntityBullet. Its blocks stay NTM's: its configs bounce, ricochet, explode or pass through
// blocks their own way, and the client draws it with no tracer to leave a hole from.
// It is an EntityThrowableNT: MixinThrowableClip already meets creatures by their models there, and NTM's onImpact gets
// that point (new MovingObjectPosition(entity, hitVec)). onImpact rolls the damage (or overrideDamage), times its
// config's headshotMult when the hit is high enough (1.0 for every NPC's config, so off), and hurts with
// attackEntityFrom: that damage, or (the creature still shielded by its last hit) that plus the last hit. The change
// the head or a limb makes goes to the one of the two NTM's own damage lands with (LegacyBullets.hurt, as 26.2 X577
// does for the old EntityBullet).
@Mixin(value = EntityBulletBaseNT.class, remap = false)
public abstract class MixinOldBulletBase {
    // this hit: where it met the creature; the change the head or a limb makes to it
    @Unique private MovingObjectPosition gunfeel$mop;
    @Unique private float gunfeel$extra;
    @Unique private boolean gunfeel$counted;
    @Unique private LegacyBullets.Hurt gunfeel$hurtWith = LegacyBullets.Hurt.FIRST;

    @Inject(method = "onImpact", at = @At("HEAD"))
    private void gunfeel$begin(MovingObjectPosition mop, CallbackInfo ci) {
        gunfeel$mop = mop;
        gunfeel$counted = false;
        gunfeel$extra = 0F;
        gunfeel$hurtWith = LegacyBullets.Hurt.FIRST;
    }

    // the first hurt carries NTM's rolled damage: the change is worked out from it, and goes to the first or the second
    // hurt by NTM's own damage (LegacyBullets.hurt). NTM's try/catch around the second would turn a throw here into no
    // damage at all: it keeps NTM's damage instead
    @WrapOperation(method = "onImpact", at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/entity/Entity;attackEntityFrom(Lnet/minecraft/util/DamageSource;F)Z"))
    private boolean gunfeel$hurt(Entity target, DamageSource source, float amount, Operation<Boolean> original) {
        if (gunfeel$counted) {
            if (gunfeel$hurtWith == LegacyBullets.Hurt.NONE) return false;
            return original.call(target, source, amount + gunfeel$extra);
        }
        gunfeel$counted = true;
        MovingObjectPosition mop = gunfeel$mop;
        if (mop != null && mop.hitVec != null && !target.worldObj.isRemote && LegacyBullets.on()) {
            try {
                float base = amount / gunfeel$ntmHead(target, mop);
                // no verdict of the model: NTM's damage, its own head rule included
                gunfeel$extra = LegacyBullets.damage(target, mop.hitVec, base, amount) - amount;
                gunfeel$hurtWith = LegacyBullets.hurt(target, amount, gunfeel$extra);
            } catch (RuntimeException e) {
                gunfeel$extra = 0F;
                gunfeel$hurtWith = LegacyBullets.Hurt.FIRST;
                LegacyBullets.zonesFailed("old bullet of the masked man, the UFO or BOT Prime", e);
            }
        }
        return original.call(target, source, gunfeel$hurtWith == LegacyBullets.Hurt.SECOND ? amount : amount + gunfeel$extra);
    }

    // NTM's own head multiplier in this damage (its height rule): taken out, so the head is not counted twice
    @Unique
    private float gunfeel$ntmHead(Entity target, MovingObjectPosition mop) {
        BulletConfiguration config = ((EntityBulletBaseNT) (Object) this).getConfig();
        if (config == null || !(config.headshotMult > 1F) || !(target instanceof EntityLivingBase)) return 1F;
        EntityLivingBase living = (EntityLivingBase) target;
        double head = living.height - living.getEyeHeight();
        return living.isEntityAlive() && mop.hitVec.yCoord > living.posY + living.height - head * 2.0 ? config.headshotMult : 1F;
    }
}
