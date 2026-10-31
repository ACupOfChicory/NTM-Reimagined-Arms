package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import acup.gunfeel.FixedStep;
import net.minecraft.client.DeltaTracker;

// Client, a trace scenario only (FixedStep): the frame timer counts a fixed step per frame instead of the real time
@Mixin(DeltaTracker.Timer.class)
public abstract class MixinDeltaTimer {
    @org.spongepowered.asm.mixin.Shadow
    private long lastMs;
    @org.spongepowered.asm.mixin.Shadow
    private float deltaTickResidual;

    @ModifyVariable(method = "advanceGameTime", at = @At("HEAD"), argsOnly = true)
    private long gunfeel$fixedGame(long ms) {
        if (!FixedStep.on()) return ms;
        // the first fixed frame: the partial tick from 0, so ticks fall on every fifth frame from here
        if (FixedStep.fresh()) deltaTickResidual = 0F;
        return FixedStep.advance(lastMs);
    }

    @ModifyVariable(method = "advanceRealTime", at = @At("HEAD"), argsOnly = true)
    private long gunfeel$fixedReal(long ms) {
        return FixedStep.on() ? FixedStep.now() : ms;
    }
}
