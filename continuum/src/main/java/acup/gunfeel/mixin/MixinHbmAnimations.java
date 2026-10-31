package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.client.anim.HbmAnimations;

import acup.gunfeel.AnimCue;
import acup.gunfeel.ArmAnimationPreview;
import acup.gunfeel.Arms;
import dev.golem.core.render.anim.BusAnimation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;

// port/continuum: the arms' part of src/main/java MixinHbmAnimations on NTM Continuum's HbmAnimations, whose animations are
// per hand (getRelevantAnim(hand, rail); the rail-only form and getRelevantTransformation go through it). Its RECOIL bus
// toning is not here.
@Mixin(value = HbmAnimations.class, remap = false)
public abstract class MixinHbmAnimations {
    // Arms.measuring: the gun as it is with no animation playing; the arm editor's preview in place of the main hand's
    @Inject(method = "getRelevantAnim(Lnet/minecraft/world/InteractionHand;I)Lcom/hbm/client/anim/HbmAnimations$Animation;",
            at = @At("HEAD"), cancellable = true)
    private static void gunfeel$still(InteractionHand hand, int rail, CallbackInfoReturnable<HbmAnimations.Animation> cir) {
        if (Arms.measuring) cir.setReturnValue(null);
        else if (hand == InteractionHand.MAIN_HAND && rail == 0 && ArmAnimationPreview.active()) cir.setReturnValue(ArmAnimationPreview.animation());
    }

    // the cue GunAnimationHandler started this animation for (AnimCue)
    @Inject(method = "start(Lnet/minecraft/world/item/Item;Ldev/golem/core/render/anim/BusAnimation;Lnet/minecraft/world/InteractionHand;IZ)V",
            at = @At("RETURN"))
    private static void gunfeel$cue(Item item, BusAnimation animation, InteractionHand hand, int rail, boolean hold, CallbackInfo ci) {
        if (AnimCue.pending() != null) AnimCue.started(HbmAnimations.getRelevantAnim(hand, rail));
    }
}
