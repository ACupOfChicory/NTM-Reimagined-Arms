package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;

import acup.gunfeel.GunHit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

// Server: a player hit by an NTM gun is not thrown back nor has their motion resent (GunHit), and their client is told
// the hit was a gun's (the view's tilt)
@Mixin(LivingEntity.class)
public abstract class MixinGunHitServer {
    @WrapWithCondition(method = "hurtServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;markHurt()V"))
    private boolean gunfeel$mark(LivingEntity self, @Local(argsOnly = true) DamageSource source) {
        return !GunHit.still(self, source);
    }

    @WrapWithCondition(method = "dealDefaultKnockback", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;knockback(DDDLnet/minecraft/world/damagesource/DamageSource;F)V"))
    private boolean gunfeel$knockback(LivingEntity self, double power, double xd, double zd, DamageSource source, float damage) {
        return !GunHit.still(self, source);
    }

    @Inject(method = "hurtServer", at = @At("RETURN"))
    private void gunfeel$told(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) GunHit.hurt((LivingEntity) (Object) this, source);
    }
}
