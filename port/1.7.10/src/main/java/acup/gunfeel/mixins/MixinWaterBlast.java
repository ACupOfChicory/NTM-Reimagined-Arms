package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.explosion.vanillant.ExplosionVNT;

import acup.gunfeel.water.WaterBlast;

// Server: an NTM explosion as it starts (WaterBlast.announce): the stand's watch now, the players near with water 2.
// port: 26.2's MixinWaterBlast (7e2382c) on NTM 1.7.10's ExplosionVNT.explode
@Mixin(value = ExplosionVNT.class, remap = false)
public abstract class MixinWaterBlast {
    @Inject(method = "explode", at = @At("HEAD"), require = 1)
    private void gunfeel$water(CallbackInfo ci) {
        WaterBlast.announce((ExplosionVNT) (Object) this);
    }
}
