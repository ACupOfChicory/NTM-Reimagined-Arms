package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.ModelHitClient;
import acup.gunfeel.ModelHitDebug;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;

// Client, F3+B: the model's outline instead of the entity's box where a pose is known (26.2: MixinModelHitboxDebug on
// EntityHitboxDebugRenderer.showHitboxes). 1.7.10 draws the box in RenderManager.renderDebugBoundingBox. Either way
// the lines are drawn without the lightmap and fog (ModelHitDebug.brightBegin).
// Also the model of a living entity whose renderer is a plain Render, not RendererLivingEntity (NTM's hunter chopper,
// plastic bag, worm, UFO, drone): taken around its doRender (ModelHitClient.beginRender), as
// MixinRendererLivingEntity takes the others'.
@Mixin(RenderManager.class)
public abstract class MixinRenderManager {
    @Inject(method = "renderDebugBoundingBox(Lnet/minecraft/entity/Entity;DDDFF)V", at = @At("HEAD"), cancellable = true)
    private void gunfeel$modelBox(Entity entity, double x, double y, double z, float yaw, float partial, CallbackInfo ci) {
        ModelHitDebug.brightBegin();
        if (ModelHitDebug.draw(entity)) {
            ModelHitDebug.brightEnd();
            ci.cancel();
        }
    }

    // the box vanilla drew (no pose): its lines at full brightness too
    @Inject(method = "renderDebugBoundingBox(Lnet/minecraft/entity/Entity;DDDFF)V", at = @At("RETURN"))
    private void gunfeel$boxBright(Entity entity, double x, double y, double z, float yaw, float partial, CallbackInfo ci) {
        ModelHitDebug.brightEnd();
    }

    @WrapOperation(method = "func_147939_a(Lnet/minecraft/entity/Entity;DDDFFZ)Z", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/Render;doRender(Lnet/minecraft/entity/Entity;DDDFF)V"))
    private void gunfeel$renderModel(Render render, Entity entity, double x, double y, double z, float yaw, float partial,
            Operation<Void> original) {
        boolean taking = ModelHitClient.beginRender(entity, render, x, y, z, partial);
        try {
            original.call(render, entity, x, y, z, yaw, partial);
        } finally {
            if (taking) ModelHitClient.endRender(entity);
        }
    }
}
