package acup.gunfeel.mixin.mc;

import acup.gunfeel.ModelHitClient;
import acup.gunfeel.ModelHitLog;
import acup.gunfeel.ModelPoseLayout;
import acup.gunfeel.VanillatePose;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.engine_room.vanillin.visuals.EntityModelVisual", remap = false)
public abstract class MixinVanillateModelHit {
    @Shadow @Final protected EntityRenderState state;
    @Shadow @Final private ModelPart[][] partsByVariant;
    @Shadow @Final private String[][] namesByVariant;
    @Shadow @Final private int[][] parentIndexByVariant;
    @Unique private final java.util.Map<Integer, ModelPoseLayout> gunfeel$layouts = new java.util.concurrent.ConcurrentHashMap<>();

    @Inject(method = "capture(F)V", at = @At("RETURN"))
    private void gunfeel$snapshot(float partialTick, CallbackInfo ci) {
        Entity entity = ((VanillateEntityAccess) (Object) this).gunfeel$entity();
        if (!(entity instanceof LivingEntity living) || !ModelHitClient.enabled()) return;
        try {
            var pose = VanillatePose.read(this);
            if (pose == null) return;
            int variant = pose.variant();
            if (variant < 0 || variant >= partsByVariant.length) return;
            var layout = gunfeel$layouts.computeIfAbsent(variant,
                    k -> new ModelPoseLayout(partsByVariant[k], namesByVariant[k], parentIndexByVariant[k]));
            ModelHitClient.offer(living, layout, pose, pose.x() - state.x, pose.y() - state.y, pose.z() - state.z);
        } catch (RuntimeException ex) {
            ModelHitLog.event("client", "vanillate_failed", entity, () -> ex.toString());
        }
    }
}
