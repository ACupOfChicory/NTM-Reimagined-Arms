package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.SprintFire;
import net.minecraft.client.entity.EntityPlayerSP;

// port: 26.2's mixin/mc/MixinSprintStop (ef36e84). 1.7.10 has no canStartSprinting / shouldStopRunSprinting: the sprint
// is started and stopped inline in onLivingUpdate, so it is taken away after that and before the player moves (the
// super.onLivingUpdate call), the tick's move then goes at walking speed and the move packet says no sprint.
// sprintFireDelay: no sprint while an NTM gun's fire key is held or it aims, so the delay after a sprint can run out
// (as TaCZ). The server's rule; SprintFire.stopsSprint says when.
@Mixin(EntityPlayerSP.class)
public abstract class MixinSprintStop {
    @Inject(method = "onLivingUpdate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/entity/AbstractClientPlayer;onLivingUpdate()V"))
    private void gunfeel$noSprint(CallbackInfo ci) {
        EntityPlayerSP self = (EntityPlayerSP) (Object) this;
        if (self.isSprinting() && SprintFire.stopsSprint(self)) self.setSprinting(false);
    }
}
