package acup.gunfeel.mixin.mc;

import acup.gunfeel.ModelHitClient;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class MixinModelPoseExtraction {
    // Публикуем только полностью извлечённый state, включая поля конкретного рендерера и расширения NeoForge.
    @Inject(method = "createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
            at = @At("RETURN"))
    private void gunfeel$completed(Entity entity, float partialTick, CallbackInfoReturnable<EntityRenderState> ci) {
        if (entity instanceof LivingEntity living && ci.getReturnValue() instanceof LivingEntityRenderState state)
            ModelHitClient.enqueue(living, state);
    }
}
