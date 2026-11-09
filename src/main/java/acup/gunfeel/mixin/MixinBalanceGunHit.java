package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.hbm.util.EntityDamageUtil;

import acup.gunfeel.BalanceGuns;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

// The balance's gun damage (BalanceGuns): a balanced shot's hit gets its pair's additions to the ammo's armour threshold
// negation and piercing (NTM's hit lambdas pass the ammo's own; the projectile is the damage source's direct entity).
@Mixin(value = EntityDamageUtil.class, remap = false)
public abstract class MixinBalanceGunHit {
    @ModifyVariable(method = "attackEntityFromNT(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;FZZDFF)Z", at = @At("HEAD"), ordinal = 1, argsOnly = true, require = 1)
    private static float gunfeel$neg(float neg, LivingEntity living, DamageSource source) {
        float[] e = BalanceGuns.extra(source.getDirectEntity());
        return e == null ? neg : neg + e[0];
    }

    @ModifyVariable(method = "attackEntityFromNT(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;FZZDFF)Z", at = @At("HEAD"), ordinal = 2, argsOnly = true, require = 1)
    private static float gunfeel$pen(float pen, LivingEntity living, DamageSource source) {
        float[] e = BalanceGuns.extra(source.getDirectEntity());
        return e == null ? pen : Math.min(1F, pen + e[1]);
    }
}
