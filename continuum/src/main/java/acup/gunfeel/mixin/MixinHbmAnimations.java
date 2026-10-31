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
import acup.gunfeel.Config;
import acup.gunfeel.GunFeel;
import acup.gunfeel.compat.Feature;
import dev.golem.core.render.anim.BusAnimation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;

// port/continuum: the arms' part of src/main/java MixinHbmAnimations on NTM Continuum's HbmAnimations, whose animations are
// per hand (getRelevantAnim(hand, rail); the rail-only form and getRelevantTransformation go through it), and its RECOIL bus
// toning (Feature.RECOIL), which the arms follow: the gun's parts read their buses through WeaponPose.bus.
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

    // Tone down NTM's own "RECOIL" animation bus in first person; the spring kick replaces most of it.
    @Inject(method = "getRelevantTransformation(Ljava/lang/String;Lnet/minecraft/world/InteractionHand;I)[D", at = @At("RETURN"),
            cancellable = true)
    private static void gunfeel$recoilBus(String bus, InteractionHand hand, int rail, CallbackInfoReturnable<double[]> cir) {
        if (!Config.enabled || !Feature.RECOIL.on() || GunFeel.current == null || !"RECOIL".equals(bus)) return;
        var player = net.minecraft.client.Minecraft.getInstance().player;
        boolean aiming = player != null && com.hbm.items.weapon.sedna.ItemGunBaseNT.getIsAiming(player.getMainHandItem());
        float strength = Config.ntmRecoilAnim * (aiming ? Config.adsRecoil : 1F);
        if (strength == 1F) return;
        double[] t = cir.getReturnValue();
        if (t == null) return;
        double[] scaled = t.clone();
        for (int i = 0; i < Math.min(3, scaled.length); i++) scaled[i] *= strength;
        cir.setReturnValue(scaled);
    }
}
