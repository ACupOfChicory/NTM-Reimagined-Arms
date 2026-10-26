package acup.gunfeel.mixin;

import acup.gunfeel.mixin.mc.VanillateEntityAccess;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Один адаптер для всех NTM visual, в том числе ещё неизвестных нам моделей.
@Pseudo
@Mixin(targets="com.hbm.client.render.flywheel.HbmDynamicEntityVisual",remap=false)
public abstract class MixinGpuEntityGeometry {
    @Inject(method="beginFrame",at=@At(value="INVOKE",shift=At.Shift.AFTER,
            target="Lcom/hbm/client/render/flywheel/HbmDynamicEntityVisual;frame(Ldev/engine_room/flywheel/api/visual/DynamicVisual$Context;)V"))
    private void gunfeel$capture(@Coerce Object context,CallbackInfo ci) {
        var target=((VanillateEntityAccess)(Object)this).gunfeel$entity();
        if(!(target instanceof LivingEntity living)) return;
        float partial=((dev.engine_room.flywheel.api.visual.DynamicVisual.Context)context).partialTick();
        // Положение visual определяется той же интерполяцией, что использует GPU.
        var position=living.getPosition(partial);
        acup.gunfeel.GpuModelHits.offer(this,living,Mth.rotLerp(partial,living.yBodyRotO,living.yBodyRot),position);
    }
}
