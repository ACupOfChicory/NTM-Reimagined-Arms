package acup.gunfeel.mixin;

import acup.gunfeel.NtmMeshHits;
import com.hbm.backport.client.core.LivingEntityRenderState;
import com.hbm.backport.client.core.LivingEntityRenderer;
import com.hbm.backport.client.core.Model;
import com.hbm.backport.client.core.ModelFeatureRenderer;
import com.hbm.backport.client.core.SubmitNodeCollector;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Port only (notes/ports/de-1.21.1.md): DE's own mob renderers, 26.2's kind backported, draw at once through
// ImmediateSubmitNodeCollector inside the entity's frame. Their main model (26.2: the render state's primary one) is
// drawn with the renderer's texture (MixinBackportGeometryHits reads what it submits).
@Mixin(value = LivingEntityRenderer.class, remap = false)
public abstract class MixinBackportModelHits {
    @SuppressWarnings({"rawtypes", "unchecked"})
    @WrapOperation(method = "submit(Lcom/hbm/backport/client/core/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/hbm/backport/client/core/SubmitNodeCollector;Lcom/hbm/backport/client/core/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lcom/hbm/backport/client/core/SubmitNodeCollector;submitModel(Lcom/hbm/backport/client/core/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILcom/hbm/backport/client/core/ModelFeatureRenderer$CrumblingOverlay;)V"), require = 1)
    private void gunfeel$primary(SubmitNodeCollector collector, Model model, Object state, PoseStack stack, RenderType type,
            int light, int overlay, int tint, TextureAtlasSprite sprite, int outline, ModelFeatureRenderer.CrumblingOverlay crumbling,
            Operation<Void> original) {
        if (!NtmMeshHits.active()) {
            original.call(collector, model, state, stack, type, light, overlay, tint, sprite, outline, crumbling);
            return;
        }
        NtmMeshHits.primary(((LivingEntityRenderer) (Object) this).getTextureLocation((LivingEntityRenderState) state), false, true);
        try {
            original.call(collector, model, state, stack, type, light, overlay, tint, sprite, outline, crumbling);
        } finally {
            NtmMeshHits.primary(null, false, false);
        }
    }
}
