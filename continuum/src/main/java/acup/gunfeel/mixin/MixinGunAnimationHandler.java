package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.client.anim.GunAnimationHandler;
import com.hbm.items.weapon.sedna.GunAnimation;
import com.hbm.packet.GunAnimationPayload;

import acup.gunfeel.InstantSwitch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;

// port/continuum: MixinHbmAnimationPayload on NTM Continuum, where the server's gun animation is a GunAnimationPayload
// that GunAnimationHandler.handle plays. instantSwitch: the server's draw for a gun whose draw the client already
// started is not started again. The payload names no slot: it is for the gun in that hand, so the selected slot.
// InstantSwitch.serverPacket is true but while InstantSwitch starts the draw itself (stonecutter.gradle.kts).
@Mixin(value = GunAnimationHandler.class, remap = false)
public abstract class MixinGunAnimationHandler {
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$predicted(GunAnimationPayload payload, CallbackInfo ci) {
        if (!InstantSwitch.serverPacket || payload.interactionHand() != InteractionHand.MAIN_HAND) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || payload.animation() < 0 || payload.animation() >= GunAnimation.VALUES.length) return;
        GunAnimation type = GunAnimation.byOrdinal(payload.animation());
        if (!InstantSwitch.accept(player.getInventory().getSelectedSlot(), player.getMainHandItem().getItem(), type, payload.configIndex()))
            ci.cancel();
    }
}
