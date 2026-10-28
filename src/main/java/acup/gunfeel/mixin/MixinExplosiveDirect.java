package acup.gunfeel.mixin;

import com.hbm.util.EntityDamageUtil;
import acup.gunfeel.ExplosiveDamage;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=EntityDamageUtil.class,remap=false)
public abstract class MixinExplosiveDirect {
    @Inject(method="attackEntityFromNT",at=@At("HEAD"),cancellable=true,require=1)
    private static void gunfeel$heat(LivingEntity target,DamageSource source,float amount,boolean first,boolean second,double knockback,float threshold,float piercing,CallbackInfoReturnable<Boolean> ci){
        float adjusted=ExplosiveDamage.extra(target,amount);
        if(adjusted!=amount)ci.setReturnValue(ExplosiveDamage.attack(target,source,adjusted,first,second,knockback,threshold,piercing));
    }
}
