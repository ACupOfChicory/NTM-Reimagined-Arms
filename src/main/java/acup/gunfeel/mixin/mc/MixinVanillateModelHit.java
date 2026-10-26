package acup.gunfeel.mixin.mc;

import acup.gunfeel.ModelHitClient;
import acup.gunfeel.ModelHitLog;
import acup.gunfeel.ModelPoseLayout;
import acup.gunfeel.VanillatePose;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.engine_room.vanillin.visuals.EntityModelVisual", remap = false)
public abstract class MixinVanillateModelHit {
    @Shadow @Final protected EntityRenderState state;
    @Shadow @Final private ModelPart[][] partsByVariant;
    @Shadow @Final private String[][] namesByVariant;
    @Shadow @Final private int[][] parentIndexByVariant;
    @Unique private final java.util.Map<Integer, ModelPoseLayout> gunfeel$layouts = new java.util.concurrent.ConcurrentHashMap<>();
    @Unique private final java.util.Map<Object,double[]> gunfeel$origins = new java.util.concurrent.ConcurrentHashMap<>();
    @Unique private volatile Object gunfeel$lastApplied;

    // Метаданные публикуются раньше volatile-снимка: apply другого потока
    // не должен увидеть новую модель без соответствующего начала координат.
    @Redirect(method = "capture(F)V", at = @At(value="FIELD",opcode=181,
            target="Ldev/engine_room/vanillin/visuals/EntityModelVisual;published:Ldev/engine_room/vanillin/visuals/EntityModelVisual$Snapshot;"))
    private void gunfeel$snapshot(@Coerce Object visual,@Coerce Object snapshot) {
        var target=((VanillateEntityAccess)(Object)this).gunfeel$entity();
        if(snapshot!=null && target instanceof LivingEntity living) {
            if(gunfeel$origins.size()>4) {
                Object current=VanillatePose.published(this);
                gunfeel$origins.keySet().removeIf(key -> key!=current && key!=gunfeel$lastApplied);
            }
            float yaw=state instanceof LivingEntityRenderState livingState?livingState.bodyRot:
                    net.minecraft.util.Mth.rotLerp(state.partialTick,living.yBodyRotO,living.yBodyRot);
            gunfeel$origins.put(snapshot,new double[]{state.x,state.y,state.z,yaw});
        }
        VanillatePose.publish(visual,snapshot);
    }

    @Inject(method = "apply", at = @At("RETURN"))
    private void gunfeel$applied(@Coerce Object snapshot, CallbackInfo ci) {
        gunfeel$lastApplied=snapshot;
        Entity entity = ((VanillateEntityAccess) (Object) this).gunfeel$entity();
        if (!(entity instanceof LivingEntity living) || !ModelHitClient.enabled()) return;
        try {
            var pose = VanillatePose.snapshot(snapshot);
            if (pose == null) return;
            var origin=gunfeel$origins.get(snapshot);
            if(origin==null) return;
            int variant = pose.variant();
            if (variant < 0 || variant >= partsByVariant.length) return;
            var layout = gunfeel$layouts.computeIfAbsent(variant, k -> {
                var captured=new ModelPoseLayout(partsByVariant[k], namesByVariant[k], parentIndexByVariant[k]);
                acup.gunfeel.ModelBodyGeometry.supplement(living,captured);
                return captured;
            });
            ModelHitClient.offer(living, layout, pose, pose.x()-origin[0],pose.y()-origin[1],pose.z()-origin[2],
                    (float)origin[3],new net.minecraft.world.phys.Vec3(origin[0],origin[1],origin[2]));
        } catch (RuntimeException ex) {
            ModelHitLog.event("client", "vanillate_failed", entity, () -> ex.toString());
        }
    }
}
