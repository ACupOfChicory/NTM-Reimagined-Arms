package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Port copy (notes/ports/de-1.21.1.md, «Копии»). 26.2's keeps a CPU copy of a mob part's pose, which CrankShaft writes
// only to GPU memory; Flywheel 1.0.7 keeps it on the instance (TransformedInstance.pose) and GpuModelHits reads it
// there. What stays: a new instance may change a mob's parts, so GpuModelHits walks its visual again.
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.lib.instance.TransformedInstance", remap = false)
public abstract class MixinGpuPoseShadow {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void gunfeel$created(CallbackInfo ci) {
        acup.gunfeel.GpuModelHits.created();
    }
}
