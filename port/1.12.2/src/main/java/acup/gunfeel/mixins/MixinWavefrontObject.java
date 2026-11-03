package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.render.loader.HFRWavefrontObject;
import com.hbm.render.loader.HFRWavefrontObject;

import acup.gunfeel.Arms;

// Which parts of an NTM model are drawn, and with what GL matrix, while the first-person gun is drawn (Arms, 26.2: submitPart),
// a big door for its cells (DoorCapture, dev), a machine or door a bullet hit (MachineMesh), or an NTM mob for its model hitboxes (ModelObjParts).
@Mixin(value = HFRWavefrontObject.class, remap = false)
public abstract class MixinWavefrontObject {
    @Inject(method = "renderAll", at = @At("HEAD"), cancellable = true)
    private void gunfeel$all(CallbackInfo ci) {
        acup.gunfeel.MachineMesh.objBegin();
        if (Arms.capturing()) Arms.parts((HFRWavefrontObject) (Object) this, 0);
        if (acup.gunfeel.DoorCapture.active()) acup.gunfeel.DoorCapture.parts((HFRWavefrontObject) (Object) this, 0);
        if (acup.gunfeel.MachineMesh.active()) acup.gunfeel.MachineMesh.parts((HFRWavefrontObject) (Object) this, 0);
        acup.gunfeel.ModelObjParts.hfr((HFRWavefrontObject) (Object) this, 0);
        if (acup.gunfeel.MachineMesh.skipDraw()) {
            acup.gunfeel.MachineMesh.objEnd();
            ci.cancel();
        }
    }

    @Inject(method = "renderOnly", at = @At("HEAD"), cancellable = true)
    private void gunfeel$only(String[] names, CallbackInfo ci) {
        acup.gunfeel.MachineMesh.objBegin();
        if (Arms.capturing()) Arms.parts((HFRWavefrontObject) (Object) this, 1, names);
        if (acup.gunfeel.DoorCapture.active()) acup.gunfeel.DoorCapture.parts((HFRWavefrontObject) (Object) this, 1, names);
        if (acup.gunfeel.MachineMesh.active()) acup.gunfeel.MachineMesh.parts((HFRWavefrontObject) (Object) this, 1, names);
        acup.gunfeel.ModelObjParts.hfr((HFRWavefrontObject) (Object) this, 1, names);
        if (acup.gunfeel.MachineMesh.skipDraw()) {
            acup.gunfeel.MachineMesh.objEnd();
            ci.cancel();
        }
    }

    @Inject(method = "renderPart", at = @At("HEAD"), cancellable = true)
    private void gunfeel$part(String name, CallbackInfo ci) {
        acup.gunfeel.MachineMesh.objBegin();
        if (Arms.capturing()) Arms.parts((HFRWavefrontObject) (Object) this, 1, name);
        if (acup.gunfeel.DoorCapture.active()) acup.gunfeel.DoorCapture.parts((HFRWavefrontObject) (Object) this, 1, name);
        if (acup.gunfeel.MachineMesh.active()) acup.gunfeel.MachineMesh.parts((HFRWavefrontObject) (Object) this, 1, name);
        acup.gunfeel.ModelObjParts.hfr((HFRWavefrontObject) (Object) this, 1, name);
        if (acup.gunfeel.MachineMesh.skipDraw()) {
            acup.gunfeel.MachineMesh.objEnd();
            ci.cancel();
        }
    }

    @Inject(method = "renderAllExcept", at = @At("HEAD"), cancellable = true)
    private void gunfeel$except(String[] names, CallbackInfo ci) {
        acup.gunfeel.MachineMesh.objBegin();
        if (Arms.capturing()) Arms.parts((HFRWavefrontObject) (Object) this, 2, names);
        if (acup.gunfeel.DoorCapture.active()) acup.gunfeel.DoorCapture.parts((HFRWavefrontObject) (Object) this, 2, names);
        if (acup.gunfeel.MachineMesh.active()) acup.gunfeel.MachineMesh.parts((HFRWavefrontObject) (Object) this, 2, names);
        acup.gunfeel.ModelObjParts.hfr((HFRWavefrontObject) (Object) this, 2, names);
        if (acup.gunfeel.MachineMesh.skipDraw()) {
            acup.gunfeel.MachineMesh.objEnd();
            ci.cancel();
        }
    }

    // its own Tessellator draw is the parts taken above (MachineMesh)
    @Inject(method = "renderAll", at = @At("RETURN"))
    private void gunfeel$renderAllEnd(CallbackInfo ci) {
        acup.gunfeel.MachineMesh.objEnd();
    }

    @Inject(method = "renderOnly", at = @At("RETURN"))
    private void gunfeel$renderOnlyEnd(CallbackInfo ci) {
        acup.gunfeel.MachineMesh.objEnd();
    }

    @Inject(method = "renderPart", at = @At("RETURN"))
    private void gunfeel$renderPartEnd(CallbackInfo ci) {
        acup.gunfeel.MachineMesh.objEnd();
    }

    @Inject(method = "renderAllExcept", at = @At("RETURN"))
    private void gunfeel$renderAllExceptEnd(CallbackInfo ci) {
        acup.gunfeel.MachineMesh.objEnd();
    }
}
