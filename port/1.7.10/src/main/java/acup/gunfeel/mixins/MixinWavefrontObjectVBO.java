package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.render.loader.HFRWavefrontObject;
import com.hbm.render.loader.HFRWavefrontObjectVBO;

import acup.gunfeel.Arms;

// Which parts of an NTM model are drawn, and with what GL matrix, while the first-person gun is drawn (Arms, 26.2: submitPart).
@Mixin(value = HFRWavefrontObjectVBO.class, remap = false)
public abstract class MixinWavefrontObjectVBO {
    @Inject(method = "renderAll", at = @At("HEAD"))
    private void gunfeel$all(CallbackInfo ci) {
        if (Arms.capturing()) Arms.parts(HFRWavefrontObject.allVBOs.get((Object) this), 0);
    }

    @Inject(method = "renderOnly", at = @At("HEAD"))
    private void gunfeel$only(String[] names, CallbackInfo ci) {
        if (Arms.capturing()) Arms.parts(HFRWavefrontObject.allVBOs.get((Object) this), 1, names);
    }

    @Inject(method = "renderPart", at = @At("HEAD"))
    private void gunfeel$part(String name, CallbackInfo ci) {
        if (Arms.capturing()) Arms.parts(HFRWavefrontObject.allVBOs.get((Object) this), 1, name);
    }

    @Inject(method = "renderAllExcept", at = @At("HEAD"))
    private void gunfeel$except(String[] names, CallbackInfo ci) {
        if (Arms.capturing()) Arms.parts(HFRWavefrontObject.allVBOs.get((Object) this), 2, names);
    }
}
