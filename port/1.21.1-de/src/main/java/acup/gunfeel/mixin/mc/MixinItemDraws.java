package acup.gunfeel.mixin.mc;

import net.minecraft.client.renderer.entity.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Port only (notes/ports/de-1.21.1.md): an item a mob holds or wears on its head is not its body, as 26.2's NtmMeshHits
// does not take submitItem. A block entity item (a chest held) draws model parts of its own: they are left out too.
@Mixin(ItemRenderer.class)
public abstract class MixinItemDraws {
    @Inject(method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
            at = @At("HEAD"))
    private void gunfeel$itemStart(CallbackInfo ci) {
        acup.gunfeel.NtmMeshHits.suspend(true);
    }

    @Inject(method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
            at = @At("RETURN"))
    private void gunfeel$itemEnd(CallbackInfo ci) {
        acup.gunfeel.NtmMeshHits.suspend(false);
    }
}
