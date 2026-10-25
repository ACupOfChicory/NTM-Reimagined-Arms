package acup.gunfeel.mixin.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import acup.gunfeel.ModelHitState;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import acup.gunfeel.ModelHitClient;

// Countered's render capture hooks, adapted for NTM bullets (MIT; third-party/accurate-hitboxes.txt).
@Mixin(LivingEntityRenderer.class)
public abstract class MixinLivingModelHits<T extends LivingEntity, S extends LivingEntityRenderState, M extends EntityModel<? super S>> {
    @Shadow protected M model;
    @Shadow public abstract Identifier getTextureLocation(S state);

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"))
    private void gunfeel$target(T entity, S state, float partialTick, CallbackInfo ci) {
        ((ModelHitState) state).gunfeel$target(entity);
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"))
    private void gunfeel$primary(S state, PoseStack stack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        // GuiEntityRenderer использует новую, неинициализированную камеру: его
        // повёрнутый портрет не должен заменять боевую позу настоящего игрока.
        ((ModelHitState) state).gunfeel$primary(camera.initialized ? model : null, getTextureLocation(state));
    }
}
