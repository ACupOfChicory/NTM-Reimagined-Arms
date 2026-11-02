package acup.gunfeel.mixins.early;

// port: new for 1.7.10. With Shader Fixer's NTM_GUN_FIX vanilla's renderItemInFirstPerson draws NTM's guns (see
// MixinItemRendererGunSway), and it moves them as it moves any held item: the swing turns them by up to 80° and the
// equip height lowers them. NTM's own first-person path (RenderHandEvent) has neither. A right click held on a block
// swings the arm again every 4 ticks (vanilla's repeat), and the swing carried the gun and the arms behind the camera
// for as long as the button was held (the owner: "the gun disappears for a second after a right click on a machine").
// For an NTM gun the swing is 0 and the gun is fully up, as NTM draws it.

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import acup.gunfeel.GunFeelClient;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.item.ItemStack;

@Mixin(ItemRenderer.class)
public abstract class MixinItemRendererGunSwing {
    @Shadow
    private ItemStack itemToRender;

    @ModifyExpressionValue(method = "renderItemInFirstPerson", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/entity/EntityClientPlayerMP;getSwingProgress(F)F"))
    private float gunfeel$noSwing(float original) {
        return GunFeelClient.ntmGunInVanillaHand(itemToRender) ? 0F : original;
    }

    @ModifyExpressionValue(method = "renderItemInFirstPerson", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/ItemRenderer;equippedProgress:F"))
    private float gunfeel$fullyUp(float original) {
        return GunFeelClient.ntmGunInVanillaHand(itemToRender) ? 1F : original;
    }

    @ModifyExpressionValue(method = "renderItemInFirstPerson", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/ItemRenderer;prevEquippedProgress:F"))
    private float gunfeel$fullyUpBefore(float original) {
        return GunFeelClient.ntmGunInVanillaHand(itemToRender) ? 1F : original;
    }
}
