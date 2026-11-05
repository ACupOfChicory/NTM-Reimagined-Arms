package acup.gunfeel.mixin.mc;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Port only (notes/ports/de-1.21.1.md): which render type a buffer draws, while an entity is drawn. 26.2 gives a model
// its render type with it (the submit); 1.21.1 draws a model straight to a consumer, so NtmMeshHits looks its texture
// up by the consumer the world's buffers gave out for that type.
@Mixin(MultiBufferSource.BufferSource.class)
public abstract class MixinBufferTypes {
    @Inject(method = "getBuffer", at = @At("RETURN"))
    private void gunfeel$type(RenderType type, CallbackInfoReturnable<VertexConsumer> ci) {
        if (acup.gunfeel.NtmMeshHits.active()) acup.gunfeel.NtmMeshHits.buffer(type, ci.getReturnValue());
    }
}
