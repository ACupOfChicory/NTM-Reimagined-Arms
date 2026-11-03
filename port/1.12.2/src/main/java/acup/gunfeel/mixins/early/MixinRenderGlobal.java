package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.Tracers;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;

@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobal {
    // The bullet holes with the world, just before the block cracks (Tracers.drawWorldHoles). Under a shader pack
    // (Angelica) this call is the pack's DESTROY phase, its program for marks on blocks. port: 1.12.2's one method, with
    // its BufferBuilder (1.7.10: the overload Forge adds). Not required: without it the holes are drawn at
    // RenderWorldLastEvent, as before.
    @Inject(method = "drawBlockDamageTexture", require = 0, at = @At("HEAD"))
    private void gunfeel$holes(Tessellator t, net.minecraft.client.renderer.BufferBuilder buffer, net.minecraft.entity.Entity viewer, float partial,
            CallbackInfo ci) {
        Tracers.drawWorldHoles(partial);
    }
}
