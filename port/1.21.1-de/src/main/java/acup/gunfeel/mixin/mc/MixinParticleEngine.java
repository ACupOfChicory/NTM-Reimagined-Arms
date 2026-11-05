package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import acup.gunfeel.BulletFx;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;

// Block crumbs of NTM's machine cells (no particle texture: purple and black squares, as NTM's explosion debris
// next to a machine) get the machine's colours instead.
// The port's copy (notes/ports/de-1.21.1.md, "Копии"): 1.21.1 keeps the particles in queues and has no extract, the
// measured hand-over is its render (NeoForge's, with the frustum and the render type filter: twice a frame with
// Fabulous); 26.2's ParticlesRenderState.submit (MixinParticlesSubmit) has no 1.21.1 counterpart. render both
// gathers and draws, so P_EXTRACT here is 26.2's extract and submit together, and P_SUBMIT stays 0.
@Mixin(ParticleEngine.class)
public abstract class MixinParticleEngine {
    @Shadow
    protected ClientLevel level;

    @Shadow
    public abstract void add(Particle p);

    @Shadow
    @org.spongepowered.asm.mixin.Final
    private java.util.Map<net.minecraft.client.particle.ParticleRenderType, java.util.Queue<Particle>> particles;

    // measured only (Perf "Not ours")
    @org.spongepowered.asm.mixin.Unique
    private long gunfeel$tickAt, gunfeel$extractAt;

    @Inject(method = "tick", at = @At("HEAD"))
    private void gunfeel$tickStart(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        gunfeel$tickAt = System.nanoTime();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void gunfeel$tickEnd(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        acup.gunfeel.Perf.other(acup.gunfeel.Perf.P_TICK, gunfeel$tickAt);
    }

    @Inject(method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V", at = @At("HEAD"))
    private void gunfeel$extractStart(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        gunfeel$extractAt = System.nanoTime();
    }

    @Inject(method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V", at = @At("RETURN"))
    private void gunfeel$extractEnd(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        acup.gunfeel.Perf.other(acup.gunfeel.Perf.P_EXTRACT, gunfeel$extractAt);
        long n = 0;
        for (java.util.Queue<Particle> g : particles.values()) n += g.size();
        acup.gunfeel.Perf.particles(n);
    }

    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void gunfeel$machineCrumbs(ParticleOptions options, double x, double y, double z, double xa, double ya, double za,
            CallbackInfoReturnable<Particle> cir) {
        if(acup.gunfeel.HoleBenchMetrics.ENABLED && (options.getType()==ParticleTypes.EXPLOSION || options.getType()==ParticleTypes.EXPLOSION_EMITTER)
                && acup.gunfeel.HoleBenchMetrics.explosion()) { cir.setReturnValue(null); return; }
        if (!(options instanceof BlockParticleOption block) || options.getType() != ParticleTypes.BLOCK || level == null
                || !acup.gunfeel.compat.Feature.BULLETS.on()) return;
        Particle p = BulletFx.missingTextureCrumb(level, block.getState(), x, y, z, xa, ya, za);
        if (p == null) return;
        add(p);
        cir.setReturnValue(p);
    }
}
