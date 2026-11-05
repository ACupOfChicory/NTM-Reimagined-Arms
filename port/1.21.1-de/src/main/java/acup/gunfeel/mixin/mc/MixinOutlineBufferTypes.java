package acup.gunfeel.mixin.mc;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Port only (notes/ports/de-1.21.1.md), with MixinBufferTypes: a glowing mob draws to the outline buffers, which give
// out the type's consumer and the outline's together as a new one; that one draws the type too.
@Mixin(OutlineBufferSource.class)
public abstract class MixinOutlineBufferTypes {
    @Inject(method = "getBuffer", at = @At("RETURN"))
    private void gunfeel$type(RenderType type, CallbackInfoReturnable<VertexConsumer> ci) {
        if (acup.gunfeel.NtmMeshHits.active()) acup.gunfeel.NtmMeshHits.buffer(type, ci.getReturnValue());
    }
}
