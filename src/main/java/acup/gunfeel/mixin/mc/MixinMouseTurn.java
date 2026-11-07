package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.RadialMenu;
import net.minecraft.client.MouseHandler;

// While the radial ammo menu is open the mouse moves its pointer, not the camera. Lying down (Prone) the view turns up
// and down only so far (TaCZ's getCrawlPitch, acup.gunfeel.tacz.CrawlPitch).
@Mixin(MouseHandler.class)
public abstract class MixinMouseTurn {
    @Shadow
    private double accumulatedDX;
    @Shadow
    private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void gunfeel$radial(double dt, CallbackInfo ci) {
        if (!acup.gunfeel.compat.Feature.AMMO_CHOICE.on() || !RadialMenu.isOpen()) return;
        RadialMenu.move(accumulatedDX, accumulatedDY);
        accumulatedDX = 0;
        accumulatedDY = 0;
        ci.cancel();
    }

    @ModifyArg(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"), index = 1)
    private double gunfeel$pronePitch(double pitch) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        return player == null ? pitch : acup.gunfeel.tacz.CrawlPitch.getCrawlPitch(player, pitch);
    }
}
