package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.FixedStep;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Timer;

// Client, a trace scenario only (FixedStep): the frame timer counts a fixed step per frame instead of the real time
// (26.2: MixinDeltaTimer on DeltaTracker.Timer). 1.7.10's Timer keeps its partial tick in elapsedPartialTicks and
// syncs to the wall clock; while the step is fixed it adds STEP_MS / 50 ms of a tick in floats, as 26.2's timer adds
// its deltaTicks, and gives whole ticks out the same way.
@Mixin(Timer.class)
public abstract class MixinTimer {
    @Shadow
    public int elapsedTicks;
    @Shadow
    public float renderPartialTicks;
    @Shadow
    public float elapsedPartialTicks;

    @Inject(method = "updateTimer", at = @At("HEAD"), cancellable = true)
    private void gunfeel$fixed(CallbackInfo ci) {
        if (!FixedStep.on()) return;
        // the first fixed frame: the partial tick from 0, so ticks fall on every fifth frame from here
        if (FixedStep.fresh()) elapsedPartialTicks = 0F;
        FixedStep.advance(Minecraft.getSystemTime());
        elapsedPartialTicks += (float) FixedStep.STEP_MS / 50F;
        elapsedTicks = (int) elapsedPartialTicks;
        elapsedPartialTicks -= (float) elapsedTicks;
        renderPartialTicks = elapsedPartialTicks;
        ci.cancel();
    }
}
