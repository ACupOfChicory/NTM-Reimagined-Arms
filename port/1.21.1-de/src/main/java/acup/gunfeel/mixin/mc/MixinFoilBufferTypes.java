package acup.gunfeel.mixin.mc;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Port only (notes/ports/de-1.21.1.md), with MixinBufferTypes: an enchanted elytra draws to the glint's consumer and its
// type's together, a new consumer; that one draws the type.
@Mixin(ItemRenderer.class)
public abstract class MixinFoilBufferTypes {
    @Inject(method = "getArmorFoilBuffer", at = @At("RETURN"))
    private static void gunfeel$type(MultiBufferSource buffers, RenderType type, boolean foil, CallbackInfoReturnable<VertexConsumer> ci) {
        if (acup.gunfeel.NtmMeshHits.active()) acup.gunfeel.NtmMeshHits.buffer(type, ci.getReturnValue());
    }
}
