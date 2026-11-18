package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.ParticleStand;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;

// The particle stand only (ParticleStand, off in the game): each particle group's handing over to the renderer timed
@Mixin(ParticlesRenderState.class)
public abstract class MixinParticleStandSubmit {
    @WrapOperation(method = "submit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/state/level/ParticleGroupRenderState;submit(Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
    private void gunfeel$standGroupSubmit(ParticleGroupRenderState state, SubmitNodeCollector collector, CameraRenderState camera, Operation<Void> op) {
        if (!ParticleStand.ENABLED) {
            op.call(state, collector, camera);
            return;
        }
        long at = System.nanoTime();
        op.call(state, collector, camera);
        ParticleStand.submitted(state, System.nanoTime() - at);
    }
}
