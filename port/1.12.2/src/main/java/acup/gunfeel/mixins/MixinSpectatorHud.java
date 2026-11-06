package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.interfaces.IItemHUD;
import com.hbm.items.weapon.ItemGunBase;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.main.ModEventHandlerClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.SpectatorGuns;
import acup.gunfeel.SpectatorScenario;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

// port: 26.2's MixinSpectatorHud. NTM CE draws a held item's HUD in its overlay handler (IItemHUD.renderHUD): for an
// NTM gun (and one of the old kind, ItemGunBase) in a spectator's hand it is not asked, so neither NTM's crosshair and ammo counter nor the mod's corner HUD
// (MixinGunHud, in renderHUD) are drawn, and the game keeps its own crosshair.
@Mixin(value = ModEventHandlerClient.class, remap = false)
public abstract class MixinSpectatorHud {
    @WrapOperation(method = "onOverlayRender", at = @At(value = "INVOKE",
            target = "Lcom/hbm/interfaces/IItemHUD;renderHUD(Lnet/minecraftforge/client/event/RenderGameOverlayEvent$Pre;Lnet/minecraftforge/client/event/RenderGameOverlayEvent$ElementType;Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/EnumHand;)V"))
    private void gunfeel$spectator(IItemHUD item, RenderGameOverlayEvent.Pre event, RenderGameOverlayEvent.ElementType type, EntityPlayer player,
            ItemStack stack, EnumHand hand, Operation<Void> original) {
        boolean skip = (item instanceof ItemGunBaseNT || item instanceof ItemGunBase) && SpectatorGuns.blocks(player);
        if (item instanceof ItemGunBaseNT && SpectatorScenario.on()) SpectatorScenario.hud(!skip);
        if (skip) return;
        original.call(item, event, type, player, stack, hand);
    }
}
