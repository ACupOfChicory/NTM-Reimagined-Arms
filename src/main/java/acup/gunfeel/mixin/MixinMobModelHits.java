package acup.gunfeel.mixin;

import acup.gunfeel.ModelHitClient;
import acup.gunfeel.ModelHitLog;
import acup.gunfeel.ModelPoseCapture;
import acup.gunfeel.NtmMeshHits;
import acup.gunfeel.mixin.mc.VanillateEntityAccess;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// MobModelVisual уже применил setupAnim и обновил GPU-дерево. При скрытой
// GPU-модели эта точка не вызывается: обычный рендерер остаётся владельцем позы.
@Pseudo
@Mixin(targets = "com.hbm.client.render.flywheel.MobModelVisual", remap = false)
public abstract class MixinMobModelHits {
    @Shadow @Final private ModelPart root;
    @Shadow @Final private LivingEntityRenderState state;
    @Shadow @Final private Matrix4f pose;
    @Shadow @Final private Vector3f interpolatedPosition;

    @Inject(method = "writeFrame(F)V", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lorg/joml/Matrix4f;set(Lorg/joml/Matrix4fc;)Lorg/joml/Matrix4f;"), require = 1, allow = 1)
    private void gunfeel$pose(float partial, CallbackInfo ci) {
        if (!ModelHitClient.enabled() || !acup.gunfeel.compat.Feature.MODEL_HITBOXES.on()) return;
        var target = ((VanillateEntityAccess) (Object) this).gunfeel$entity();
        if (!(target instanceof LivingEntity living)) return;
        try {
            var result = ModelPoseCapture.collect(root, pose, interpolatedPosition, ModelPoseCapture.ACCESS);
            if (!result.complete() || result.boxes().isEmpty()) {
                ModelHitLog.event("client", "mob_model_incomplete", living,
                        () -> "complete=" + result.complete() + " nodes=" + result.nodes() + " cubes=" + result.boxes().size());
                return;
            }
            NtmMeshHits.offer(living, state.bodyRot, result.boxes());
            ModelHitLog.event("client", "mob_model_pose", living, () -> "cubes=" + result.boxes().size());
        } catch (RuntimeException ex) {
            ModelHitLog.event("client", "mob_model_failed", living, () -> ex.toString());
        }
    }
}
