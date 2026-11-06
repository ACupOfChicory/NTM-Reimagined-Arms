package acup.gunfeel.mixin.mc;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// The port's copy of the root's MixinSmokeSight (notes/ports/de-1.21.1.md, "Дым"): 1.21.1 has only
// hasLineOfSight(Entity), which looks at the target's eyes, what 26.2's (Entity) passes on to the four-argument one.
// A mob's line of sight ends in thick smoke (acup.gunfeel.smoke.SmokeSight, behind a server key)
@Mixin(LivingEntity.class)
public abstract class MixinSmokeSight {
    @Inject(method = "hasLineOfSight(Lnet/minecraft/world/entity/Entity;)Z", at = @At("RETURN"), cancellable = true)
    private void gunfeel$smoke(Entity target, CallbackInfoReturnable<Boolean> ci) {
        if (!ci.getReturnValueZ()) return;
        if (acup.gunfeel.smoke.SmokeSight.hides((LivingEntity) (Object) this, target, target.getEyeY())) ci.setReturnValue(false);
    }
}
