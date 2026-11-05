package acup.gunfeel.mixin.mc;

import acup.gunfeel.NtmMeshHits;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.layers.SheepFurLayer;
import net.minecraft.client.renderer.entity.layers.SlimeOuterLayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Port copy (notes/ports/de-1.21.1.md, «Копии»). 26.2 marks the renderer's main model on its render state, and names
// the layers' models by what they draw (NtmMeshHits.prepared). 1.21.1's LivingEntityRenderer.render draws them itself,
// so the main model's draw and each layer's are wrapped here: the main one with the renderer's texture, a layer with
// the name its parts end in. The sheep's fur ends in ModelWool.SUFFIX (26.2 finds it by its texture, sheep_wool, which
// 1.21.1 calls sheep_fur), the slime's outer body in ModelBodyGeometry.SHELL_SUFFIX (26.2: the slime model with the
// 8-pixel cube), worn armor in ARMOR_SUFFIX (port/continuum's MixinEquipmentLayerModel).
@Mixin(LivingEntityRenderer.class)
public abstract class MixinLivingModelHits {
    @SuppressWarnings({"rawtypes", "unchecked"})
    @WrapOperation(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
    private void gunfeel$primary(EntityModel<?> model, PoseStack stack, VertexConsumer consumer, int light, int overlay, int color,
            Operation<Void> original, @Local(argsOnly = true) LivingEntity entity) {
        if (!NtmMeshHits.active()) {
            original.call(model, stack, consumer, light, overlay, color);
            return;
        }
        NtmMeshHits.primary(((EntityRenderer) (Object) this).getTextureLocation(entity), model instanceof PlayerModel, true);
        try {
            original.call(model, stack, consumer, light, overlay, color);
        } finally {
            NtmMeshHits.primary(null, false, false);
        }
    }

    @WrapOperation(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V"))
    private void gunfeel$layer(RenderLayer<?, ?> layer, PoseStack stack, MultiBufferSource buffers, int light, Entity entity,
            float limbSwing, float limbAmount, float partial, float age, float headYaw, float headPitch, Operation<Void> original) {
        if (!NtmMeshHits.active()) {
            original.call(layer, stack, buffers, light, entity, limbSwing, limbAmount, partial, age, headYaw, headPitch);
            return;
        }
        String suffix = layer instanceof SheepFurLayer ? acup.gunfeel.ModelWool.SUFFIX
                : layer instanceof SlimeOuterLayer ? acup.gunfeel.ModelBodyGeometry.SHELL_SUFFIX
                : layer instanceof HumanoidArmorLayer ? NtmMeshHits.ARMOR_SUFFIX : "";
        NtmMeshHits.layer(suffix, true);
        try {
            original.call(layer, stack, buffers, light, entity, limbSwing, limbAmount, partial, age, headYaw, headPitch);
        } finally {
            NtmMeshHits.layer(suffix, false);
        }
    }
}
