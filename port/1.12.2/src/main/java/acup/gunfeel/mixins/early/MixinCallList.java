package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.GlStateManager;

// Dev (ShapesScenario): a display list called while a big door is being read (DoorCapture). NTM CE's sliding blast
// doors draw their animated (Collada) model as display lists; DoorCapture knows the triangles of those lists.
// Client: the same while a machine's or door's parts are read for the holes on them (MachineMesh). Otherwise nothing.
@Mixin(GlStateManager.class)
public abstract class MixinCallList {
    @Inject(method = "callList", at = @At("HEAD"))
    private static void gunfeel$callList(int list, CallbackInfo ci) {
        if (acup.gunfeel.DoorCapture.active()) acup.gunfeel.DoorCapture.list(list);
        else if (acup.gunfeel.MachineMesh.active()) acup.gunfeel.MachineMesh.list(list);
    }
}
