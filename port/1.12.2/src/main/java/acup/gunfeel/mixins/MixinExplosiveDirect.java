package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.util.EntityDamageUtil;

import acup.gunfeel.ExplosiveDamage;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.DamageSource;

// port: 26.2's MixinExplosiveDirect (e284339): damage an explosive bullet's handler deals straight to the creature it
// hit (besides its blast) takes the zone's multiplier
@Mixin(value = EntityDamageUtil.class, remap = false)
public abstract class MixinExplosiveDirect {
    @Inject(method = "attackEntityFromNT(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/util/DamageSource;FZZDFF)Z", at = @At("HEAD"),
            cancellable = true, require = 1)
    private static void gunfeel$heat(EntityLivingBase target, DamageSource source, float amount, boolean first, boolean second, double knockback,
            float threshold, float piercing, CallbackInfoReturnable<Boolean> ci) {
        float adjusted = ExplosiveDamage.extra(target, amount);
        if (adjusted != amount) ci.setReturnValue(ExplosiveDamage.attack(target, source, adjusted, first, second, knockback, threshold, piercing));
    }
}
