package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.render.entity.projectile.RenderBulletMK4;

import acup.gunfeel.Tracers;
import net.minecraft.entity.Entity;

// NTM's wide ribbon is not drawn for bullets whose thin tracer Tracers draws (26.2: MixinRenderBulletMK4.submit).
// port: Render.doRender is a Minecraft method, so NTM's release jar has it under its SRG name; both names are listed
// (one of them is there), and no refmap is needed.
@Mixin(value = RenderBulletMK4.class, remap = false)
public abstract class MixinRenderBulletMK4 {
    @Inject(method = {"doRender(Lnet/minecraft/entity/Entity;DDDFF)V", "func_76986_a(Lnet/minecraft/entity/Entity;DDDFF)V"},
            at = @At("HEAD"), cancellable = true, require = 1)
    private void gunfeel$hide(Entity entity, double x, double y, double z, float yaw, float partial, CallbackInfo ci) {
        if (!(entity instanceof EntityBulletBaseMK4)) return;
        EntityBulletBaseMK4 bullet = (EntityBulletBaseMK4) entity;
        if (Tracers.replaces(bullet.config != null ? bullet.config : bullet.getBulletConfig())) ci.cancel();
    }
}
