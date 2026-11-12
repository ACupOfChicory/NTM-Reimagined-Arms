package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBulletBeamBase;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.BalanceFinal;
import net.minecraft.world.entity.LivingEntity;

// The final scheme's pair of the Tesla Cannon's IR arc (BalanceFinal.beamSub): the beams the IR capacitor splits off
// at the hit (XFactoryEnergy.LAMBDA_LIGHTNING_SPLIT) are made by this constructor and fired after it.
@Mixin(value = EntityBulletBeamBase.class, remap = false)
public abstract class MixinBalanceFinalBeamSub {
    @Inject(method = "<init>(Lnet/minecraft/world/entity/LivingEntity;Lcom/hbm/items/weapon/sedna/BulletConfig;F)V", at = @At("RETURN"), require = 1)
    private void gunfeel$beamSub(LivingEntity thrower, BulletConfig config, float baseDamage, CallbackInfo ci) {
        BalanceFinal.beamSub((EntityBulletBeamBase) (Object) this, thrower, config);
    }
}
