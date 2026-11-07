package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import acup.gunfeel.gas.client.GasClient;
import net.minecraft.client.player.LocalPlayer;

// Chlorine: no sprint while the server says so (a dose of it on the player), as the sprint-fire rule stops it.
@Mixin(LocalPlayer.class)
public abstract class MixinGasSprint {
    @Inject(method = "canStartSprinting", at = @At("RETURN"), cancellable = true, require = 0)
    private void gunfeel$gasNoStart(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && GasClient.noSprint()) cir.setReturnValue(false);
    }

    @Inject(method = "shouldStopRunSprinting", at = @At("RETURN"), cancellable = true, require = 0)
    private void gunfeel$gasStop(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && GasClient.noSprint()) cir.setReturnValue(true);
    }
}
