package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.items.weapon.sedna.factory.LegoClient;

import acup.gunfeel.Tracers;
import net.minecraft.client.renderer.Tessellator;

// Learns a bullet type's tracer colours: Tracers calls NTM's tracer once while probing, and the plain bullet tracer
// reports its colours here instead of drawing (26.2: the same, renderBulletStandard with a PoseStack).
@Mixin(value = LegoClient.class, remap = false)
public abstract class MixinLegoClient {
    @Inject(method = "renderBulletStandard(Lnet/minecraft/client/renderer/Tessellator;IIDDDZ)V", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$probe(Tessellator tess, int dark, int light, double length, double widthF, double widthB, boolean fullbright,
            CallbackInfo ci) {
        if (!Tracers.probing()) return;
        Tracers.probed(dark, light);
        ci.cancel();
    }
}
