package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.render.entity.projectile.RenderBulletMK4;

import acup.gunfeel.Tracers;

// NTM's wide ribbon is not drawn for bullets whose thin tracer Tracers draws (26.2: MixinRenderBulletMK4.submit).
// port: NTM CE's doRender takes its bullet (the generic Render<EntityBulletBaseMK4>; the bridge from Entity is
// Minecraft's func_76986_a), so its name is NTM's own in every jar.
@Mixin(value = RenderBulletMK4.class, remap = false)
public abstract class MixinRenderBulletMK4 {
    @Inject(method = "doRender(Lcom/hbm/entity/projectile/EntityBulletBaseMK4;DDDFF)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void gunfeel$hide(EntityBulletBaseMK4 bullet, double x, double y, double z, float yaw, float partial, CallbackInfo ci) {
        if (Tracers.replaces(bullet.config != null ? bullet.config : bullet.getBulletConfig())) ci.cancel();
    }
}
