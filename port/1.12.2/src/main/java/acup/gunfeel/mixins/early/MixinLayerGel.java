package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.ModelCapture;
import acup.gunfeel.ModelHitClient;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.entity.layers.LayerSlimeGel;
import net.minecraft.entity.Entity;

// port: a layer that adds to a mob's shape, the slime's outer body, taken as a pass of the model being drawn (1.7.10: one of
// doRender's renderPassModel passes, MixinRendererLivingEntity)
@Mixin(LayerSlimeGel.class)
public abstract class MixinLayerGel {
    @WrapOperation(method = "doRenderLayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/model/ModelBase;render(Lnet/minecraft/entity/Entity;FFFFFF)V"))
    private void gunfeel$pass(ModelBase model, Entity entity, float a, float b, float c, float d, float e, float f, Operation<Void> original) {
        ModelHitClient.pass(model, ModelCapture.SHELL_SUFFIX);
        try {
            original.call(model, entity, a, b, c, d, e, f);
        } finally {
            ModelHitClient.passEnd();
        }
    }
}
