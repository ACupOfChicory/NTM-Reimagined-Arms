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
import net.minecraft.entity.EntityLivingBase;

// Client, F3+B: the model's outline instead of the entity's box where a pose is known (26.2: MixinModelHitboxDebug on
// EntityHitboxDebugRenderer.showHitboxes). 1.7.10 draws the box in RenderManager.renderDebugBoundingBox. Either way
// the lines are drawn without the lightmap and fog (ModelHitDebug.brightBegin).
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

    // port: a mob NTM CE draws by a plain Render (the hunter chopper, the plastic bag): its model taken as it draws
    // (ModelHitClient.beginOther), the bracket ended however doRender ends
    @WrapOperation(method = "renderEntity(Lnet/minecraft/entity/Entity;DDDFFZ)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/Render;doRender(Lnet/minecraft/entity/Entity;DDDFF)V"))
    private void gunfeel$otherModel(Render<Entity> render, Entity entity, double x, double y, double z, float yaw, float partial,
            Operation<Void> original) {
        if (!ModelHitClient.beginOther(entity, render, x, y, z, partial)) {
            original.call(render, entity, x, y, z, yaw, partial);
            return;
        }
        try {
            original.call(render, entity, x, y, z, yaw, partial);
        } finally {
            ModelHitClient.endOther((EntityLivingBase) entity);
        }
    }

    // the box vanilla drew (no pose): its lines at full brightness too
    @Inject(method = "renderDebugBoundingBox(Lnet/minecraft/entity/Entity;DDDFF)V", at = @At("RETURN"))
    private void gunfeel$boxBright(Entity entity, double x, double y, double z, float yaw, float partial, CallbackInfo ci) {
        ModelHitDebug.brightEnd();
    }
}
