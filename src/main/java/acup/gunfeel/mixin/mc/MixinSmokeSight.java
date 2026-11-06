package acup.gunfeel.mixin.mc;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// a mob's line of sight ends in thick smoke (acup.gunfeel.smoke.SmokeSight, behind a server key)
@Mixin(LivingEntity.class)
public abstract class MixinSmokeSight {
    @Inject(method = "hasLineOfSight(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/ClipContext$Block;Lnet/minecraft/world/level/ClipContext$Fluid;D)Z",
            at = @At("RETURN"), cancellable = true)
    private void gunfeel$smoke(Entity target, ClipContext.Block block, ClipContext.Fluid fluid, double eyeHeight, CallbackInfoReturnable<Boolean> ci) {
        if (!ci.getReturnValueZ()) return;
        if (acup.gunfeel.smoke.SmokeSight.hides((LivingEntity) (Object) this, target, eyeHeight)) ci.setReturnValue(false);
    }
}
