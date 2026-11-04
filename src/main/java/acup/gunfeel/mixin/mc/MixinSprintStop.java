package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import acup.gunfeel.SprintFire;
import net.minecraft.client.player.LocalPlayer;

// sprintFireDelay: no sprint while an NTM gun's fire key is held or it aims, so the delay after a sprint can run out
// (as TaCZ). The server's rule; SprintFire.stopsSprint says when. Without the methods the player just keeps sprinting.
@Mixin(LocalPlayer.class)
public abstract class MixinSprintStop {
    @Inject(method = "canStartSprinting", at = @At("RETURN"), cancellable = true, require = 0)
    private void gunfeel$noStart(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && SprintFire.stopsSprint((LocalPlayer) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "shouldStopRunSprinting", at = @At("RETURN"), cancellable = true, require = 0)
    private void gunfeel$stop(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && SprintFire.stopsSprint((LocalPlayer) (Object) this)) cir.setReturnValue(true);
    }
}
