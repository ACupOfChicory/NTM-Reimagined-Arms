package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import acup.gunfeel.SprintFire;
import net.minecraft.client.player.LocalPlayer;

// sprintFireDelay: no sprint while an NTM gun's fire key is held or it aims, so the delay after a sprint can run out
// (as TaCZ). The server's rule; SprintFire.stopsSprint says when. Without the methods the player just keeps sprinting.
// 1.21.1 has no shouldStopRunSprinting: aiStep asks isSprinting() a second time and then works out whether to stop.
// Before that the sprint is stopped here, on land only, as 26.2's shouldStopRunSprinting is asked only then.
@Mixin(LocalPlayer.class)
public abstract class MixinSprintStop {
    @Inject(method = "canStartSprinting", at = @At("RETURN"), cancellable = true, require = 0)
    private void gunfeel$noStart(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && SprintFire.stopsSprint((LocalPlayer) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isSprinting()Z", ordinal = 1), require = 0)
    private void gunfeel$stop(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (self.isSprinting() && !self.isSwimming() && SprintFire.stopsSprint(self)) self.setSprinting(false);
    }
}
