package acup.gunfeel.mixin.mc;

import acup.gunfeel.NtmMeshHits;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public abstract class MixinEntityMeshHits {
    @Inject(method = "submit", at = @At("HEAD"))
    private void gunfeel$begin(EntityRenderState state, CameraRenderState camera, double x, double y, double z,
            PoseStack stack, SubmitNodeCollector collector, CallbackInfo ci) {
        NtmMeshHits.begin(state, camera);
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void gunfeel$end(EntityRenderState state, CameraRenderState camera, double x, double y, double z,
            PoseStack stack, SubmitNodeCollector collector, CallbackInfo ci) {
        NtmMeshHits.end();
    }
}
