package acup.gunfeel.mixin;

import acup.gunfeel.MeshHitPause;
import com.hbm.client.render.BeamPronter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// port/continuum: the arcs and beams NTM Continuum's mobs draw in their own submit (tesla and taint crabs, RAD beast,
// UFO) are not their body: MeshHitPause keeps NtmMeshHits from reading them as model geometry
@Mixin(value = BeamPronter.class, remap = false)
public abstract class MixinBeamPronterHits {
    @Inject(method = {"prontBeam", "prontBeamGradient"}, at = @At("HEAD"))
    private static void gunfeel$pause(CallbackInfo ci) {
        MeshHitPause.begin();
    }

    @Inject(method = {"prontBeam", "prontBeamGradient"}, at = @At("RETURN"))
    private static void gunfeel$resume(CallbackInfo ci) {
        MeshHitPause.end();
    }
}
