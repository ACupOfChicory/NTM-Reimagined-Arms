package acup.gunfeel.mixin;

import acup.gunfeel.MobObjPose;
import acup.gunfeel.ModelHitClient;
import acup.gunfeel.ModelHitLog;
import acup.gunfeel.NtmMeshHits;
import acup.gunfeel.mixin.mc.VanillateEntityAccess;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.hbm.client.render.flywheel.MobObjVisual", remap = false)
public abstract class MixinMobObjModelHits {
    @Shadow @Final private Matrix4f[] lastPoses;
    @Shadow @Final private boolean[] visible;
    @Shadow @Final private Vector3f interpolatedPosition;
    @Unique private List<MobObjPose.Part> gunfeel$layout;
    @Unique private boolean gunfeel$failed;

    @Inject(method = "writeFrame", at = @At("RETURN"))
    private void gunfeel$pose(float partial, CallbackInfo ci) {
        if (gunfeel$failed || !ModelHitClient.enabled() || !acup.gunfeel.compat.Feature.MODEL_HITBOXES.on()) return;
        var target = ((VanillateEntityAccess) (Object) this).gunfeel$entity();
        if (!(target instanceof LivingEntity living)) return;
        try {
            if (gunfeel$layout == null) {
                gunfeel$layout = MobObjPose.layout(this);
                ModelHitLog.event("client", "obj_layout", living, () -> "parts=" + gunfeel$layout.size());
            }
            var boxes = MobObjPose.boxes(gunfeel$layout, lastPoses, visible, interpolatedPosition);
            if (boxes.isEmpty()) return;
            NtmMeshHits.offer(living, Mth.rotLerp(partial, living.yBodyRotO, living.yBodyRot), boxes);
            ModelHitLog.event("client", "obj_pose", living, () -> "cubes=" + boxes.size());
        } catch (RuntimeException ex) {
            gunfeel$failed = true;
            ModelHitLog.event("client", "obj_failed", living, () -> ex.toString());
        }
    }
}
