package acup.gunfeel.mixins;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import acup.gunfeel.Config;
import acup.gunfeel.GunFeelClient;
import acup.gunfeel.KickFrame;
import acup.gunfeel.WeaponSway;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.IItemRenderer;

// The first-person gun (NTM: setPerspectiveAndRender -> setupTransformsAndRender -> renderItem -> setupFirstPerson,
// renderFirstPerson). 26.2 has the same steps in setupTransformsAndSubmit.
@Mixin(value = ItemRenderWeaponBase.class, remap = false)
public abstract class MixinItemRenderWeaponBase {
    @Inject(method = "setupTransformsAndRender", at = @At("HEAD"))
    private void gunfeel$begin(ItemStack stack, CallbackInfo ci) {
        ItemRenderWeaponBase self = (ItemRenderWeaponBase) (Object) this;
        // the gun drawn may not be the one in hand (switching slots): its own settings, not the defaults
        Config.select(stack == null ? null : GunFeelClient.gunId(stack.getItem()));
        GunFeelClient.current = self;
        GunFeelClient.currentAkimbo = self.isAkimbo(Minecraft.getMinecraft().thePlayer);
        if (Config.enabled) GunFeelClient.checkShot(stack);
        KickFrame.begin();
    }

    // NTM's two lag turns ((pitch - arm pitch) * 0.1 * turnMagnitude, the same for yaw) are replaced by WeaponSway as a
    // whole, not added to it: with the magnitude 0 they turn by nothing.
    @ModifyExpressionValue(method = "setupTransformsAndRender", at = @At(value = "INVOKE",
            target = "Lcom/hbm/render/item/weapon/sedna/ItemRenderWeaponBase;getTurnMagnitude(Lnet/minecraft/item/ItemStack;)F"))
    private float gunfeel$noLag(float original) {
        return Config.enabled ? 0F : original;
    }

    // the sway where NTM's lag turns were: after the lighting set-up (its own push/pop), before the lag turns
    @Inject(method = "setupTransformsAndRender", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/entity/EntityPlayerSP;prevRenderArmPitch:F", opcode = Opcodes.GETFIELD, ordinal = 0, remap = true))
    private void gunfeel$sway(ItemStack stack, CallbackInfo ci) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (Config.enabled && player != null) WeaponSway.apply(player);
    }

    // Spring kick in the gun's frame, after NTM placed the gun and before it draws the model.
    @Inject(method = "renderItem", at = @At(value = "INVOKE",
            target = "Lcom/hbm/render/item/weapon/sedna/ItemRenderWeaponBase;renderFirstPerson(Lnet/minecraft/item/ItemStack;)V"))
    private void gunfeel$kick(IItemRenderer.ItemRenderType type, ItemStack stack, Object[] data, CallbackInfo ci) {
        if (GunFeelClient.current != (Object) this) return;
        if (Config.enabled) KickFrame.kick(stack);
        KickFrame.gunFrame();
    }

    @Inject(method = "setupTransformsAndRender", at = @At("RETURN"))
    private void gunfeel$end(ItemStack stack, CallbackInfo ci) {
        KickFrame.end(stack);
        GunFeelClient.current = null;
        net.minecraft.entity.player.EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        ItemStack held = player == null ? null : player.getHeldItem();
        Config.select(held != null && held.getItem() instanceof ItemGunBaseNT ? GunFeelClient.gunId(held.getItem()) : null);
    }
}
