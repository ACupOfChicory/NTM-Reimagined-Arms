package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.VertexConsumer;

import acup.gunfeel.ModelHitClient;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;

// port/continuum: MixinPreparedModelHit on 26.1, where ModelFeatureRenderer poses the model in renderModel. The render
// type is not on 26.1's submit: ModelSubmit261 holds it meanwhile
@Mixin(ModelFeatureRenderer.class)
public abstract class MixinRenderedModelHit {
    @Inject(method = "renderModel", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/model/Model;setupAnim(Ljava/lang/Object;)V"))
    private void gunfeel$posed(SubmitNodeStorage.ModelSubmit<?> submit, RenderType type, VertexConsumer buffer,
            OutlineBufferSource outline, MultiBufferSource.BufferSource crumbling, CallbackInfo ci) {
        acup.gunfeel.ModelSubmit261.begin(type);
        try {
            ModelHitClient.rendered(submit);
        } finally {
            acup.gunfeel.ModelSubmit261.end();
        }
    }
}
