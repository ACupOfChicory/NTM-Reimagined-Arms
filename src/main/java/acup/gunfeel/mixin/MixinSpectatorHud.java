package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.client.ClientRegistry;

import acup.gunfeel.SpectatorGuns;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

// Client: no gun HUD for a spectator (spectatorNoGuns, see SpectatorGuns): NTM's ammo counter and durability bar (or
// the mod's corner HUD in their place, MixinClientRegistry), its scope and its crosshair, which then is the game's own.
@Mixin(value = ClientRegistry.class, remap = false)
public abstract class MixinSpectatorHud {
    @Inject(method = "renderGunHud", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$hud(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (SpectatorGuns.blocks(Minecraft.getInstance().player)) ci.cancel();
    }

    @Inject(method = "renderGunScope", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$scope(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (SpectatorGuns.blocks(Minecraft.getInstance().player)) ci.cancel();
    }

    @Inject(method = "replacesCrosshair", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$crosshair(CallbackInfoReturnable<Boolean> cir) {
        if (SpectatorGuns.blocks(Minecraft.getInstance().player)) cir.setReturnValue(false);
    }
}
