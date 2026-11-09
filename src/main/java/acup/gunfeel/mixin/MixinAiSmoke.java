package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.grenade.EntityGrenadeUniversal;

import acup.gunfeel.ai.AiSmoke;

// The mob AI's smoke grenade (AiSmoke): NTM's grenade with its item marked. Where it would go off it lets the GL-54's
// smoke cloud out instead and is gone; on the client it goes with no blast.
@Mixin(value = EntityGrenadeUniversal.class, remap = false)
public abstract class MixinAiSmoke implements AiSmoke.Burst {
    @Inject(method = "explode", at = @At("HEAD"), cancellable = true)
    private void gunfeel$smoke(CallbackInfo ci) {
        if (AiSmoke.burst((EntityGrenadeUniversal) (Object) this)) ci.cancel();
    }
}
