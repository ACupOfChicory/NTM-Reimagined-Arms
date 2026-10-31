package acup.gunfeel.mixins;

// port: 26.2's mixin/MixinClientRegistry.java (0762086) for NTM 1.7.10. 26.2 cancels ClientRegistry.renderGunHudComponents;
// NTM 1.7.10 draws the same two components (HUDComponentAmmoCounter, HUDComponentDurabilityBar) from
// ItemGunBaseNT.renderHUD in RenderGameOverlayEvent.Pre, for each element, next to its crosshair. The components are
// skipped there (the crosshair stays NTM's) and the corner HUD is drawn once, on the HOTBAR element. Client only.

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.hud.IHUDComponent;

import acup.gunfeel.Config;
import acup.gunfeel.Hud;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType;

@Mixin(value = ItemGunBaseNT.class, remap = false)
public abstract class MixinGunHud {
    // Replace NTM's ammo counter and durability bar next to the hotbar with the corner HUD.
    @Inject(method = "renderHUD", at = @At("HEAD"))
    private void gunfeel$hud(RenderGameOverlayEvent.Pre event, ElementType type, EntityPlayer player, ItemStack stack, CallbackInfo ci) {
        if (!Config.enabled || !Config.cornerHud || type != ElementType.HOTBAR || player == null || stack == null) return;
        Hud.render(event.resolution, player, stack, (ItemGunBaseNT) (Object) this);
    }

    @Redirect(method = "renderHUD", at = @At(value = "INVOKE",
            target = "Lcom/hbm/items/weapon/sedna/hud/IHUDComponent;renderHUDComponent(Lnet/minecraftforge/client/event/RenderGameOverlayEvent$Pre;Lnet/minecraftforge/client/event/RenderGameOverlayEvent$ElementType;Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/item/ItemStack;II)V"))
    private void gunfeel$components(IHUDComponent c, RenderGameOverlayEvent.Pre event, ElementType type, EntityPlayer player, ItemStack stack,
            int bottomOffset, int gunIndex) {
        if (!Config.enabled || !Config.cornerHud) c.renderHUDComponent(event, type, player, stack, bottomOffset, gunIndex);
    }
}
