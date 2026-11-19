package acup.gunfeel.mixin.mc;

import java.util.Map;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.ParticleStand;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;
import net.minecraft.core.particles.ParticleOptions;

// The particle stand only (ParticleStand, off in the game): the particle tick timed with the groups' sizes, gathering
// them for drawing timed (by group) or skipped (its hidden frames), and every particle counted or refused (its X runs)
@Mixin(ParticleEngine.class)
public abstract class MixinParticleStandEngine {
    @Shadow
    @Final
    private Map<ParticleRenderType, ParticleGroup<?>> particles;

    @Unique
    private long gunfeel$standTickAt, gunfeel$standExtractAt;

    @Inject(method = "tick", at = @At("HEAD"))
    private void gunfeel$standTick(CallbackInfo ci) {
        if (ParticleStand.ENABLED) gunfeel$standTickAt = System.nanoTime();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void gunfeel$standTicked(CallbackInfo ci) {
        if (ParticleStand.ENABLED) ParticleStand.ticked(System.nanoTime() - gunfeel$standTickAt, particles);
    }

    @Inject(method = "extract", at = @At("HEAD"), cancellable = true)
    private void gunfeel$standExtract(CallbackInfo ci) {
        if (!ParticleStand.ENABLED) return;
        if (ParticleStand.hide) {
            ci.cancel();
            return;
        }
        gunfeel$standExtractAt = System.nanoTime();
    }

    @Inject(method = "extract", at = @At("RETURN"))
    private void gunfeel$standExtracted(CallbackInfo ci) {
        if (ParticleStand.ENABLED) ParticleStand.extracted(System.nanoTime() - gunfeel$standExtractAt);
    }

    @WrapOperation(method = "extract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/particle/ParticleGroup;extractRenderState(Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)Lnet/minecraft/client/renderer/state/level/ParticleGroupRenderState;"))
    private ParticleGroupRenderState gunfeel$standGroupExtract(ParticleGroup<?> group, Frustum frustum, Camera camera, float partialTick,
            Operation<ParticleGroupRenderState> op) {
        if (!ParticleStand.ENABLED) return op.call(group, frustum, camera, partialTick);
        long at = System.nanoTime();
        ParticleGroupRenderState state = op.call(group, frustum, camera, partialTick);
        ParticleStand.groupExtracted(group, state, System.nanoTime() - at);
        return state;
    }

    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void gunfeel$standRefuse(ParticleOptions options, double x, double y, double z, double xa, double ya, double za,
            CallbackInfoReturnable<Particle> cir) {
        if (ParticleStand.ENABLED && ParticleStand.refused(options)) cir.setReturnValue(null);
    }

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void gunfeel$standAdd(Particle p, CallbackInfo ci) {
        if (ParticleStand.ENABLED && ParticleStand.added(p)) ci.cancel();
    }
}
