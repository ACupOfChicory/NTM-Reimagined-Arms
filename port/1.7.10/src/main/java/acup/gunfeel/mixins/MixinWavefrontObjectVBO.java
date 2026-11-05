package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.render.loader.HFRWavefrontObject;
import com.hbm.render.loader.HFRWavefrontObjectVBO;

import acup.gunfeel.Arms;

// Which parts of an NTM model are drawn, and with what GL matrix, while the first-person gun is drawn (Arms, 26.2: submitPart),
// a big door for its cells (DoorCapture, dev), a machine or door a bullet hit for its hole (MachineMesh), or a model a mob
// draws or wears (NTM's suits, ModelObjParts) for its model hitboxes.
@Mixin(value = HFRWavefrontObjectVBO.class, remap = false)
public abstract class MixinWavefrontObjectVBO {
    @Inject(method = "renderAll", at = @At("HEAD"), cancellable = true)
    private void gunfeel$all(CallbackInfo ci) {
        if (Arms.capturing()) Arms.parts(HFRWavefrontObject.allVBOs.get((Object) this), 0);
        if (acup.gunfeel.DoorCapture.active()) acup.gunfeel.DoorCapture.parts(HFRWavefrontObject.allVBOs.get((Object) this), 0);
        if (acup.gunfeel.MachineMesh.active()) acup.gunfeel.MachineMesh.parts(HFRWavefrontObject.allVBOs.get((Object) this), 0);
        acup.gunfeel.ModelObjParts.hfr(HFRWavefrontObject.allVBOs.get((Object) this), 0);
        if (acup.gunfeel.MachineMesh.skipDraw()) ci.cancel();
    }

    @Inject(method = "renderOnly", at = @At("HEAD"), cancellable = true)
    private void gunfeel$only(String[] names, CallbackInfo ci) {
        if (Arms.capturing()) Arms.parts(HFRWavefrontObject.allVBOs.get((Object) this), 1, names);
        if (acup.gunfeel.DoorCapture.active()) acup.gunfeel.DoorCapture.parts(HFRWavefrontObject.allVBOs.get((Object) this), 1, names);
        if (acup.gunfeel.MachineMesh.active()) acup.gunfeel.MachineMesh.parts(HFRWavefrontObject.allVBOs.get((Object) this), 1, names);
        acup.gunfeel.ModelObjParts.hfr(HFRWavefrontObject.allVBOs.get((Object) this), 1, names);
        if (acup.gunfeel.MachineMesh.skipDraw()) ci.cancel();
    }

    @Inject(method = "renderPart", at = @At("HEAD"), cancellable = true)
    private void gunfeel$part(String name, CallbackInfo ci) {
        if (Arms.capturing()) Arms.parts(HFRWavefrontObject.allVBOs.get((Object) this), 1, name);
        if (acup.gunfeel.DoorCapture.active()) acup.gunfeel.DoorCapture.parts(HFRWavefrontObject.allVBOs.get((Object) this), 1, name);
        if (acup.gunfeel.MachineMesh.active()) acup.gunfeel.MachineMesh.parts(HFRWavefrontObject.allVBOs.get((Object) this), 1, name);
        acup.gunfeel.ModelObjParts.hfr(HFRWavefrontObject.allVBOs.get((Object) this), 1, name);
        if (acup.gunfeel.MachineMesh.skipDraw()) ci.cancel();
    }

    @Inject(method = "renderAllExcept", at = @At("HEAD"), cancellable = true)
    private void gunfeel$except(String[] names, CallbackInfo ci) {
        if (Arms.capturing()) Arms.parts(HFRWavefrontObject.allVBOs.get((Object) this), 2, names);
        if (acup.gunfeel.DoorCapture.active()) acup.gunfeel.DoorCapture.parts(HFRWavefrontObject.allVBOs.get((Object) this), 2, names);
        if (acup.gunfeel.MachineMesh.active()) acup.gunfeel.MachineMesh.parts(HFRWavefrontObject.allVBOs.get((Object) this), 2, names);
        acup.gunfeel.ModelObjParts.hfr(HFRWavefrontObject.allVBOs.get((Object) this), 2, names);
        if (acup.gunfeel.MachineMesh.skipDraw()) ci.cancel();
    }
}
