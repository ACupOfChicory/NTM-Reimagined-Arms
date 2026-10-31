package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.ModelHitDebug;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;

// Client, F3+B: the model's outline instead of the entity's box where a pose is known (26.2: MixinModelHitboxDebug on
// EntityHitboxDebugRenderer.showHitboxes). 1.7.10 draws the box in RenderManager.renderDebugBoundingBox.
@Mixin(RenderManager.class)
public abstract class MixinRenderManager {
    @Inject(method = "renderDebugBoundingBox(Lnet/minecraft/entity/Entity;DDDFF)V", at = @At("HEAD"), cancellable = true)
    private void gunfeel$modelBox(Entity entity, double x, double y, double z, float yaw, float partial, CallbackInfo ci) {
        if (ModelHitDebug.draw(entity)) ci.cancel();
    }
}
