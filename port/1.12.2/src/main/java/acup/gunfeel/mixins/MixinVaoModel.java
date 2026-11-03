package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.render.loader.HFRWavefrontObject;
import com.hbm.render.loader.WaveFrontObjectVAO;

import acup.gunfeel.CeModels;

// Client: which model an NTM VAO was loaded from, and the handle of each of its groups (CeModels).
@Mixin(value = WaveFrontObjectVAO.class, remap = false)
public abstract class MixinVaoModel {
    @Inject(method = "load", at = @At("RETURN"))
    private void gunfeel$load(HFRWavefrontObject obj, CallbackInfo ci) {
        CeModels.loaded((WaveFrontObjectVAO) (Object) this, obj);
    }
}
