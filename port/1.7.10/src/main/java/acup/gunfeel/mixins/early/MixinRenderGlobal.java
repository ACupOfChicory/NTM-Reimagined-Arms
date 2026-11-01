package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.Tracers;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.EntityLivingBase;

@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobal {
    // The bullet holes with the world, just before the block cracks (Tracers.drawWorldHoles). Under a shader pack
    // (Angelica) this call is the pack's DESTROY phase, its program for marks on blocks. The overload Forge adds, the one
    // EntityRenderer calls: no SRG name, the same name in the players' game (remap = false). Not required: without it the
    // holes are drawn at RenderWorldLastEvent, as before.
    @Inject(method = "drawBlockDamageTexture(Lnet/minecraft/client/renderer/Tessellator;Lnet/minecraft/entity/EntityLivingBase;F)V", remap = false,
            require = 0, at = @At("HEAD"))
    private void gunfeel$holes(Tessellator t, EntityLivingBase viewer, float partial, CallbackInfo ci) {
        Tracers.drawWorldHoles(partial);
    }
}
