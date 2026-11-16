package acup.gunfeel.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.CasingCull;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;

// NTM's spent casings: their bodies in the frame and near only, at most Config.casingMax, in one call (CasingCull);
// their smoke as NTM draws it. Should NTM change, nothing here applies and the casings are NTM's.
@Mixin(targets = "com.hbm.particle.CasingParticleGroup$State", remap = false)
public abstract class MixinCasingDraw {
    @Shadow
    @Final
    private List<?> instances;

    @Inject(method = "submit", at = @At("HEAD"))
    private void gunfeel$begin(SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        CasingCull.begin(instances, camera);
    }

    // the body (the casing's model); the second such call is its smoke
    @WrapOperation(method = "submit", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitCustomGeometry(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V"))
    private void gunfeel$body(SubmitNodeCollector collector, PoseStack stack, RenderType type, SubmitNodeCollector.CustomGeometryRenderer renderer,
            Operation<Void> original) {
        if (!CasingCull.body(stack, type, renderer)) original.call(collector, stack, type, renderer);
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void gunfeel$end(SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        CasingCull.end(collector);
    }
}
