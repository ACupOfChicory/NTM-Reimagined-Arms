package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.client.render.projectile.TracerRegistry;

import acup.gunfeel.Tracers;

// port/continuum: MixinLegoClient and MixinRenderBulletMK4 on NTM Continuum. The plain bullet tracer is
// TracerRegistry.ribbon(dark, light, fullbright); the tracer it returns is wrapped so that it reports its colours while
// Tracers probes it, and draws nothing for bullets whose thin tracer Tracers draws.
@Mixin(value = TracerRegistry.class, remap = false)
public abstract class MixinTracerRibbon {
    @Inject(method = "ribbon(IIZ)Lcom/hbm/client/render/projectile/TracerRegistry$BulletTracer;", at = @At("RETURN"), cancellable = true)
    private static void gunfeel$wrap(int dark, int light, boolean fullbright, CallbackInfoReturnable<TracerRegistry.BulletTracer> cir) {
        TracerRegistry.BulletTracer inner = cir.getReturnValue();
        cir.setReturnValue((state, pose, sink) -> {
            if (Tracers.probing()) {
                Tracers.probed(dark, light);
                return;
            }
            if (Tracers.replaces(state.config)) return;
            inner.draw(state, pose, sink);
        });
    }
}
