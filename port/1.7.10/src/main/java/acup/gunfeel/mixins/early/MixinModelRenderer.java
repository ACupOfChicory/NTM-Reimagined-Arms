package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.ModelHitClient;
import net.minecraft.client.model.ModelRenderer;

// Client: each part of the model being taken (MixinRendererLivingEntity), at the start of its draw. The part's own
// transform that render applies next is computed from its fields (ModelCapture.local), so nothing depends on the GL
// calls inside render, which Angelica rewrites. Also the parts a machine's renderer draws (MachineMesh).
@Mixin(ModelRenderer.class)
public abstract class MixinModelRenderer {
    @Inject(method = "render(F)V", at = @At("HEAD"))
    private void gunfeel$part(float scale, CallbackInfo ci) {
        if (ModelHitClient.capturing()) ModelHitClient.part((ModelRenderer) (Object) this, scale, false);
        if (acup.gunfeel.MachineMesh.active()) acup.gunfeel.MachineMesh.modelPart((ModelRenderer) (Object) this, scale, false);
    }

    @Inject(method = "renderWithRotation(F)V", at = @At("HEAD"))
    private void gunfeel$turnedPart(float scale, CallbackInfo ci) {
        if (ModelHitClient.capturing()) ModelHitClient.part((ModelRenderer) (Object) this, scale, true);
        if (acup.gunfeel.MachineMesh.active()) acup.gunfeel.MachineMesh.modelPart((ModelRenderer) (Object) this, scale, true);
    }

    // the display list build draws the cubes with the Tessellator: not a part of a machine (MachineMesh takes the cubes)
    @Inject(method = "compileDisplayList(F)V", at = @At("HEAD"))
    private void gunfeel$compile(float scale, CallbackInfo ci) {
        acup.gunfeel.MachineMesh.compile(true);
    }

    @Inject(method = "compileDisplayList(F)V", at = @At("RETURN"))
    private void gunfeel$compiled(float scale, CallbackInfo ci) {
        acup.gunfeel.MachineMesh.compile(false);
    }
}
