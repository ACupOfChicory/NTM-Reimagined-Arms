package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.client.anim.GunAnimationHandler;
import com.hbm.items.weapon.sedna.GunAnimation;
import com.hbm.packet.GunAnimationPayload;

import acup.gunfeel.AnimCue;

// port/continuum: the cue of the animation GunAnimationHandler is about to start (AnimCue; NTM NEXT's HbmAnimations
// keeps it on the animation itself, getGunAnimation)
@Mixin(value = GunAnimationHandler.class, remap = false)
public abstract class MixinGunAnimationHandler {
    @Inject(method = "handle", at = @At("HEAD"))
    private static void gunfeel$cue(GunAnimationPayload payload, CallbackInfo ci) {
        int i = payload.animation();
        AnimCue.handling(i >= 0 && i < GunAnimation.VALUES.length ? GunAnimation.byOrdinal(i) : null);
    }

    @Inject(method = "handle", at = @At("RETURN"))
    private static void gunfeel$cueDone(GunAnimationPayload payload, CallbackInfo ci) {
        AnimCue.handling(null);
    }
}
