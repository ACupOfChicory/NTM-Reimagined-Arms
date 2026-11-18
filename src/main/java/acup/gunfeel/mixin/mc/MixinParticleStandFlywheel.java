package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import acup.gunfeel.ParticleStand;

// The particle stand only (ParticleStand, off in the game): Flywheel's frame (its visuals updated, NTM's particle
// visuals among them) and its drawing, timed. Nothing when Flywheel (CrankShaft) is missing or changed
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl", remap = false)
public abstract class MixinParticleStandFlywheel {
    @Unique
    private long gunfeel$standFrameAt, gunfeel$standRenderAt;

    @Inject(method = "beginFrame", at = @At("HEAD"), require = 0)
    private void gunfeel$standFrame(CallbackInfo ci) {
        if (ParticleStand.ENABLED) gunfeel$standFrameAt = System.nanoTime();
    }

    @Inject(method = "beginFrame", at = @At("RETURN"), require = 0)
    private void gunfeel$standFramed(CallbackInfo ci) {
        if (ParticleStand.ENABLED) ParticleStand.flywheelFrame(System.nanoTime() - gunfeel$standFrameAt);
    }

    // render: the solid visuals; renderOit: the translucent ones (NTM's particle visuals), each on the GPU too
    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void gunfeel$standRender(CallbackInfo ci) {
        if (!ParticleStand.ENABLED) return;
        gunfeel$standRenderAt = System.nanoTime();
        ParticleStand.gpuBegin(ParticleStand.GPU_FLYWHEEL);
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void gunfeel$standRendered(CallbackInfo ci) {
        if (!ParticleStand.ENABLED) return;
        ParticleStand.gpuEnd(ParticleStand.GPU_FLYWHEEL);
        ParticleStand.flywheelRender(System.nanoTime() - gunfeel$standRenderAt);
    }

    @Inject(method = "renderOit", at = @At("HEAD"), require = 0)
    private void gunfeel$standOit(CallbackInfoReturnable<Boolean> cir) {
        if (!ParticleStand.ENABLED) return;
        gunfeel$standRenderAt = System.nanoTime();
        ParticleStand.gpuBegin(ParticleStand.GPU_FLYWHEEL);
    }

    @Inject(method = "renderOit", at = @At("RETURN"), require = 0)
    private void gunfeel$standOitDone(CallbackInfoReturnable<Boolean> cir) {
        if (!ParticleStand.ENABLED) return;
        ParticleStand.gpuEnd(ParticleStand.GPU_FLYWHEEL);
        ParticleStand.flywheelRender(System.nanoTime() - gunfeel$standRenderAt);
    }
}
