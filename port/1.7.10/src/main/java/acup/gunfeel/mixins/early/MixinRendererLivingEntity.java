package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.ModelCapture;
import acup.gunfeel.ModelHitClient;
import acup.gunfeel.ModelWool;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.entity.RenderSheep;
import net.minecraft.client.renderer.entity.RenderSlime;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;

// Client: a living entity's model is taken as it draws (26.2: MixinLivingModelHits on LivingEntityRenderer.submit and
// MixinPreparedModelHit on ModelFeatureRenderer.prepareModel). doRender's start gives the view and the origin, the
// rotateCorpse call the body's yaw, and the renderModel call brackets the main model's pass, so overlays and the hurt
// tint drawn after it are not taken. Of the renderPassModel's passes only those that add to the body are (gunfeel$pass:
// the sheep's wool, the slime's outer body), not armour or eyes. The bracket is at doRender's call site, so a renderer
// that overrides renderModel (RenderDragon) is bracketed too. The parts come from MixinModelRenderer.
@Mixin(RendererLivingEntity.class)
public abstract class MixinRendererLivingEntity {
    @Shadow
    protected ModelBase mainModel;

    private static final String DO_RENDER = "doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V";

    @Inject(method = DO_RENDER, at = @At("HEAD"))
    private void gunfeel$begin(EntityLivingBase entity, double x, double y, double z, float yaw, float partial, CallbackInfo ci) {
        ModelHitClient.begin(entity, mainModel, x, y, z);
    }

    @ModifyArg(method = DO_RENDER, index = 2, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/RendererLivingEntity;rotateCorpse(Lnet/minecraft/entity/EntityLivingBase;FFF)V"))
    private float gunfeel$yaw(float bodyYaw) {
        return ModelHitClient.yaw(bodyYaw);
    }

    @Inject(method = DO_RENDER, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/RendererLivingEntity;renderModel(Lnet/minecraft/entity/EntityLivingBase;FFFFFF)V"))
    private void gunfeel$modelStart(EntityLivingBase entity, double x, double y, double z, float yaw, float partial, CallbackInfo ci) {
        ModelHitClient.inModel(true);
    }

    @Inject(method = DO_RENDER, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/entity/RendererLivingEntity;renderModel(Lnet/minecraft/entity/EntityLivingBase;FFFFFF)V"))
    private void gunfeel$modelEnd(EntityLivingBase entity, double x, double y, double z, float yaw, float partial, CallbackInfo ci) {
        ModelHitClient.inModel(false);
    }

    // doRender calls renderPassModel.render itself (renderModel draws the main model): the sheep's wool and the
    // slime's outer body add to the shape, other passes (armour, eyes) do not
    @WrapOperation(method = DO_RENDER, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/model/ModelBase;render(Lnet/minecraft/entity/Entity;FFFFFF)V"))
    private void gunfeel$pass(ModelBase model, Entity entity, float a, float b, float c, float d, float e, float f, Operation<Void> original) {
        Object self = this;
        String suffix = self instanceof RenderSheep ? ModelWool.SUFFIX : self instanceof RenderSlime ? ModelCapture.SHELL_SUFFIX : null;
        ModelHitClient.pass(model, suffix);
        try {
            original.call(model, entity, a, b, c, d, e, f);
        } finally {
            ModelHitClient.passEnd();
        }
    }

    @Inject(method = DO_RENDER, at = @At("RETURN"))
    private void gunfeel$end(EntityLivingBase entity, double x, double y, double z, float yaw, float partial, CallbackInfo ci) {
        ModelHitClient.end(entity);
    }
}
