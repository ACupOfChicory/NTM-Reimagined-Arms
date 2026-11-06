package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.explosion.vanillant.ExplosionVNT;

import acup.gunfeel.water.WaterBlast;

// Server: an NTM explosion at water tells the players near (WaterBlastPayload), for the plume and the bubble
@Mixin(value = ExplosionVNT.class, remap = false)
public abstract class MixinWaterBlast {
    @Inject(method = "explode", at = @At("HEAD"), require = 1)
    private void gunfeel$water(CallbackInfo ci) {
        WaterBlast.announce((ExplosionVNT) (Object) this);
    }
}
