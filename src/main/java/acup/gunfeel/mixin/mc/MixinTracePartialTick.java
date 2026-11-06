package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import acup.gunfeel.MobSmoke;
import net.minecraft.client.DeltaTracker;

// Client, the mob stand with a trace only (MobSmoke.frozen): every renderer gets the partial tick 0, so a mob's
// pose does not depend on when in a tick the frame came
@Mixin(DeltaTracker.Timer.class)
public abstract class MixinTracePartialTick {
    @Inject(method = "getGameTimeDeltaPartialTick", at = @At("RETURN"), cancellable = true)
    private void gunfeel$frozen(boolean ignoreFrozen, CallbackInfoReturnable<Float> cir) {
        if (MobSmoke.frozen()) cir.setReturnValue(0F);
    }
}
