package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.ModelObjParts;
import net.minecraftforge.client.model.obj.WavefrontObject;

// Client: the OBJ groups Forge's loader draws, for the model hitboxes of the NTM mobs it draws (ModelObjParts: the
// crabs, the block spider, the mask man), and for the holes on NTM's machines it draws (MachineMesh: 131 of NTM's
// models). Only while a mob's model or a machine is being read; otherwise nothing.
@Mixin(value = WavefrontObject.class, remap = false)
public abstract class MixinForgeWavefrontObject {
    @Inject(method = "renderAll", at = @At("HEAD"))
    private void gunfeel$all(CallbackInfo ci) {
        acup.gunfeel.MachineMesh.objBegin();
        ModelObjParts.forge((WavefrontObject) (Object) this, 0);
        if (acup.gunfeel.MachineMesh.active()) acup.gunfeel.MachineMesh.forge((WavefrontObject) (Object) this, 0);
    }

    @Inject(method = "renderOnly", at = @At("HEAD"))
    private void gunfeel$only(String[] names, CallbackInfo ci) {
        acup.gunfeel.MachineMesh.objBegin();
        ModelObjParts.forge((WavefrontObject) (Object) this, 1, names);
        if (acup.gunfeel.MachineMesh.active()) acup.gunfeel.MachineMesh.forge((WavefrontObject) (Object) this, 1, names);
    }

    @Inject(method = "renderPart", at = @At("HEAD"))
    private void gunfeel$part(String name, CallbackInfo ci) {
        acup.gunfeel.MachineMesh.objBegin();
        ModelObjParts.forge((WavefrontObject) (Object) this, 1, name);
        if (acup.gunfeel.MachineMesh.active()) acup.gunfeel.MachineMesh.forge((WavefrontObject) (Object) this, 1, name);
    }

    @Inject(method = "renderAllExcept", at = @At("HEAD"))
    private void gunfeel$except(String[] names, CallbackInfo ci) {
        acup.gunfeel.MachineMesh.objBegin();
        ModelObjParts.forge((WavefrontObject) (Object) this, 2, names);
        if (acup.gunfeel.MachineMesh.active()) acup.gunfeel.MachineMesh.forge((WavefrontObject) (Object) this, 2, names);
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
