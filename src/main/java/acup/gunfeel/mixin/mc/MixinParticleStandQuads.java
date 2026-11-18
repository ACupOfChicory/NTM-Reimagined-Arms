package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.ParticleStand;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;

// The particle stand only (ParticleStand, off in the game): the quad particles' vertices written (prepareGroup) and
// their draw recorded (executeGroup), timed, the draw on the GPU too. Nothing where another mod (Sodium) takes those methods over
@Mixin(QuadParticleFeatureRenderer.class)
public abstract class MixinParticleStandQuads {
    @Unique
    private long gunfeel$standPrepareAt, gunfeel$standExecuteAt;

    @Inject(method = "prepareGroup", at = @At("HEAD"), require = 0)
    private void gunfeel$standPrepare(CallbackInfo ci) {
        if (ParticleStand.ENABLED) gunfeel$standPrepareAt = System.nanoTime();
    }

    @Inject(method = "prepareGroup", at = @At("RETURN"), require = 0)
    private void gunfeel$standPrepared(CallbackInfo ci) {
        if (ParticleStand.ENABLED) ParticleStand.prepared(System.nanoTime() - gunfeel$standPrepareAt);
    }

    @Inject(method = "executeGroup", at = @At("HEAD"), require = 0)
    private void gunfeel$standExecute(CallbackInfo ci) {
        if (!ParticleStand.ENABLED) return;
        gunfeel$standExecuteAt = System.nanoTime();
        ParticleStand.gpuBegin(ParticleStand.GPU_QUADS);
    }

    @Inject(method = "executeGroup", at = @At("RETURN"), require = 0)
    private void gunfeel$standExecuted(CallbackInfo ci) {
        if (!ParticleStand.ENABLED) return;
        ParticleStand.gpuEnd(ParticleStand.GPU_QUADS);
        ParticleStand.executed(System.nanoTime() - gunfeel$standExecuteAt);
    }
}
