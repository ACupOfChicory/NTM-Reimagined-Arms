package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.explosion.vanillant.ExplosionVNT;

import acup.gunfeel.water.WaterBlast;

// 26.2's MixinWaterBlast. Server: every NTM explosion as it starts (WaterBlast.announce: the players near are told of
// one at water)
@Mixin(value = ExplosionVNT.class, remap = false)
public abstract class MixinWaterBlast {
    @Inject(method = "explode", at = @At("HEAD"), require = 1)
    private void gunfeel$water(CallbackInfo ci) {
        WaterBlast.announce((ExplosionVNT) (Object) this);
    }
}
