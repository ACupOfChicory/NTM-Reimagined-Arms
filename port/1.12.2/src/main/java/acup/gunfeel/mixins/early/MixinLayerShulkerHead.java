package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.ModelHitClient;
import net.minecraft.client.model.ModelRenderer;

// port: 1.12.2 draws the shulker's head in a layer of its own (26.2: a part of the model), the main model's head part:
// taken as a part of the shape, so the head markup (ntmgunfeel-heads.json) finds it
@Mixin(targets = "net.minecraft.client.renderer.entity.RenderShulker$HeadLayer")
public abstract class MixinLayerShulkerHead {
    @WrapOperation(method = "doRenderLayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/model/ModelRenderer;render(F)V"))
    private void gunfeel$head(ModelRenderer head, float scale, Operation<Void> original) {
        ModelHitClient.passMain();
        try {
            original.call(head, scale);
        } finally {
            ModelHitClient.passEnd();
        }
    }
}
