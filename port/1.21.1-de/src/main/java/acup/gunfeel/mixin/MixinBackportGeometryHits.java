package acup.gunfeel.mixin;

import acup.gunfeel.NtmMeshHits;
import com.hbm.backport.client.core.ImmediateSubmitNodeCollector;
import com.hbm.backport.client.core.SubmitNodeCollector;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Port only (notes/ports/de-1.21.1.md): what DE's renderers submit is drawn at once by ImmediateSubmitNodeCollector,
// inside the entity's frame. A custom geometry (and a model, which DE submits as one) is read as 26.2's
// SubmitNodeCollection hands it to NtmMeshHits.wrap.
@Mixin(value = ImmediateSubmitNodeCollector.class, remap = false)
public abstract class MixinBackportGeometryHits {
    @WrapOperation(method = "submitCustomGeometry", at = @At(value = "INVOKE",
            target = "Lcom/hbm/backport/client/core/SubmitNodeCollector$CustomGeometryRenderer;render(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"),
            require = 1)
    private void gunfeel$geometry(SubmitNodeCollector.CustomGeometryRenderer renderer, PoseStack.Pose pose, VertexConsumer buffer,
            Operation<Void> original, @Local(argsOnly = true) RenderType type) {
        original.call(NtmMeshHits.wrap(renderer, type), pose, buffer);
    }
}
