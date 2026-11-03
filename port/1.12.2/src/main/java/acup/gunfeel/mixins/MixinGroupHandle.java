package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.render.loader.GroupHandle;

import acup.gunfeel.CeModels;

// Client: a part of an NTM model drawn from the GPU, with the GL matrix of the draw (CeModels.drawn): what the
// first-person gun is made of (Arms), and an NTM mob's model for its hitboxes (ModelObjParts). WaveFrontObjectVAO's
// renderAll / renderOnly / renderPart / renderAllExcept draw each group through bindAndDraw, a static handle through render.
@Mixin(value = GroupHandle.class, remap = false)
public abstract class MixinGroupHandle {
    @Inject(method = "render", at = @At("HEAD"))
    private void gunfeel$render(CallbackInfo ci) {
        CeModels.drawn((GroupHandle) (Object) this);
    }

    @Inject(method = "bindAndDraw", at = @At("HEAD"))
    private void gunfeel$bindAndDraw(CallbackInfo ci) {
        CeModels.drawn((GroupHandle) (Object) this);
    }
}
