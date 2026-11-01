package acup.gunfeel.mixins.early;

// port: new for 1.7.10. With Shader Fixer's NTM_GUN_FIX vanilla's renderItemInFirstPerson draws NTM's guns (see
// MixinItemRenderWeaponBase.gunfeel$directBegin), and its two arm lag turns, times NTM's turnMagnitude, stand for the
// lag turns of setupTransformsAndRender. As there, WeaponSway takes their place as a whole.

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.GunFeelClient;
import acup.gunfeel.WeaponSway;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.item.ItemStack;

@Mixin(ItemRenderer.class)
public abstract class MixinItemRendererGunSway {
    @Shadow
    private ItemStack itemToRender;

    // the pitch lag turn: the sway in its place
    @WrapOperation(method = "renderItemInFirstPerson", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V", ordinal = 2, remap = false))
    private void gunfeel$swayPitch(float angle, float x, float y, float z, Operation<Void> original) {
        if (!GunFeelClient.swayInVanillaHand(itemToRender)) {
            original.call(angle, x, y, z);
            return;
        }
        WeaponSway.apply(Minecraft.getMinecraft().thePlayer);
    }

    // the yaw lag turn: in the sway already
    @WrapOperation(method = "renderItemInFirstPerson", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glRotatef(FFFF)V", ordinal = 3, remap = false))
    private void gunfeel$swayYaw(float angle, float x, float y, float z, Operation<Void> original) {
        if (!GunFeelClient.swayInVanillaHand(itemToRender)) original.call(angle, x, y, z);
    }
}
