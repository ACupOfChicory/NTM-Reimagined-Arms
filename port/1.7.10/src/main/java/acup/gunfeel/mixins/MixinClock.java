package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.util.Clock;

import acup.gunfeel.FixedStep;
import acup.gunfeel.TraceScenario;

// NTM's frame clock (animations, their end) on the fixed frames while a trace scenario plays (26.2: GameTime.CLIENT_NOW)
@Mixin(value = Clock.class, remap = false)
public abstract class MixinClock {
    @Shadow
    private static long time_ms;

    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$fixed(CallbackInfo ci) {
        if (!FixedStep.on()) return;
        time_ms = TraceScenario.ntmNow();
        ci.cancel();
    }
}
