package acup.gunfeel.mixin.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Port only (notes/ports/de-1.21.1.md): how deep EntityRenderDispatcher.render calls are inside NtmMeshHits' frame, so
// that an entity a renderer draws within another's render (a mod's) is not taken as the frame's entity.
@Mixin(EntityRenderDispatcher.class)
public abstract class MixinEntityRenderDepth {
    @Inject(method = "render", at = @At("HEAD"))
    private <E extends Entity> void gunfeel$in(E entity, double x, double y, double z, float yaw, float partial, PoseStack pose,
            MultiBufferSource buffers, int light, CallbackInfo ci) {
        acup.gunfeel.NtmMeshHits.dispatch(true);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private <E extends Entity> void gunfeel$out(E entity, double x, double y, double z, float yaw, float partial, PoseStack pose,
            MultiBufferSource buffers, int light, CallbackInfo ci) {
        acup.gunfeel.NtmMeshHits.dispatch(false);
    }
}
