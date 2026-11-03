package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


import acup.gunfeel.ModelHitClient;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.entity.EntityLivingBase;

// Client: a living entity's model is taken as it draws (26.2: MixinLivingModelHits on LivingEntityRenderer.submit and
// MixinPreparedModelHit on ModelFeatureRenderer.prepareModel). doRender's start gives the view and the origin, the
// applyRotations call (1.7.10: rotateCorpse) the body's yaw, and the renderModel call brackets the main model's pass,
// so overlays and the hurt tint drawn after it are not taken. Of the layers only those that add to the body are
// (MixinLayerShell: the sheep's wool, the slime's outer body; 1.7.10: renderPassModel's passes), not armour or eyes.
// The bracket is at doRender's call site, so a renderer that overrides renderModel (RenderDragon) is bracketed too.
// The parts come from MixinModelRenderer.
@Mixin(RenderLivingBase.class)
public abstract class MixinRendererLivingEntity {
    @Shadow
    protected ModelBase mainModel;

    private static final String DO_RENDER = "doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V";

    @Inject(method = DO_RENDER, at = @At("HEAD"))
    private void gunfeel$begin(EntityLivingBase entity, double x, double y, double z, float yaw, float partial, CallbackInfo ci) {
        ModelHitClient.begin(entity, mainModel, x, y, z);
    }

    @ModifyArg(method = DO_RENDER, index = 2, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/RenderLivingBase;applyRotations(Lnet/minecraft/entity/EntityLivingBase;FFF)V"))
    private float gunfeel$yaw(float bodyYaw) {
        return ModelHitClient.yaw(bodyYaw);
    }

    @Inject(method = DO_RENDER, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/RenderLivingBase;renderModel(Lnet/minecraft/entity/EntityLivingBase;FFFFFF)V"))
    private void gunfeel$modelStart(EntityLivingBase entity, double x, double y, double z, float yaw, float partial, CallbackInfo ci) {
        ModelHitClient.inModel(true);
    }

    @Inject(method = DO_RENDER, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/entity/RenderLivingBase;renderModel(Lnet/minecraft/entity/EntityLivingBase;FFFFFF)V"))
    private void gunfeel$modelEnd(EntityLivingBase entity, double x, double y, double z, float yaw, float partial, CallbackInfo ci) {
        ModelHitClient.inModel(false);
    }

    @Inject(method = DO_RENDER, at = @At("RETURN"))
    private void gunfeel$end(EntityLivingBase entity, double x, double y, double z, float yaw, float partial, CallbackInfo ci) {
        ModelHitClient.end(entity);
    }
}
