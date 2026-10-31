package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.client.anim.GunAnimationHandler;
import com.hbm.items.weapon.sedna.GunAnimation;
import com.hbm.packet.GunAnimationPayload;

import acup.gunfeel.AnimCue;
import acup.gunfeel.InstantSwitch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;

// port/continuum: GunAnimationHandler.handle plays the server's gun animation (a GunAnimationPayload) on NTM Continuum.
// - instantSwitch (NTM NEXT's MixinHbmAnimationPayload): the server's draw for a gun whose draw the client already
//   started is not started again. The payload names no slot: it is for the gun in that hand, so the selected slot.
//   InstantSwitch.serverPacket is true but while InstantSwitch starts the draw itself (stonecutter.gradle.kts).
// - arms: the cue of the animation about to start (AnimCue; NTM NEXT keeps it on the animation, getGunAnimation).
@Mixin(value = GunAnimationHandler.class, remap = false)
public abstract class MixinGunAnimationHandler {
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$head(GunAnimationPayload payload, CallbackInfo ci) {
        int i = payload.animation();
        GunAnimation type = i >= 0 && i < GunAnimation.VALUES.length ? GunAnimation.byOrdinal(i) : null;
        if (InstantSwitch.serverPacket && payload.interactionHand() == InteractionHand.MAIN_HAND && type != null) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && !InstantSwitch.accept(player.getInventory().getSelectedSlot(), player.getMainHandItem().getItem(),
                    type, payload.configIndex())) {
                ci.cancel();
                return;
            }
        }
        AnimCue.handling(type);
    }

    @Inject(method = "handle", at = @At("RETURN"))
    private static void gunfeel$cueDone(GunAnimationPayload payload, CallbackInfo ci) {
        AnimCue.handling(null);
    }
}
