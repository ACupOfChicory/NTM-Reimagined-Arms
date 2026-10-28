package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.render.anim.HbmAnimations;

import acup.gunfeel.Config;
import acup.gunfeel.GunFeel;

@Mixin(value = HbmAnimations.class, remap = false)
public abstract class MixinHbmAnimations {
    // Arms.measuring: the gun as it is with no animation playing
    @Inject(method = "getRelevantAnim(I)Lcom/hbm/render/anim/HbmAnimations$Animation;", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$still(int index, CallbackInfoReturnable<HbmAnimations.Animation> cir) {
        if (acup.gunfeel.Arms.measuring) cir.setReturnValue(null);
        else if (index == 0 && acup.gunfeel.ArmAnimationPreview.active()) cir.setReturnValue(acup.gunfeel.ArmAnimationPreview.animation());
    }

    @Inject(method = "getGunAnimation", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$previewCue(int index, CallbackInfoReturnable<com.hbm.render.anim.AnimationEnums.GunAnimation> cir) {
        if (index == 0 && acup.gunfeel.ArmAnimationPreview.active()) cir.setReturnValue(acup.gunfeel.ArmAnimationPreview.type());
    }

    @Inject(method = "getGunAnimationTimer", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$previewTimer(int index, CallbackInfoReturnable<Integer> cir) {
        if (index == 0 && acup.gunfeel.ArmAnimationPreview.active()) cir.setReturnValue(acup.gunfeel.ArmAnimationPreview.millis()/50);
    }

    @Inject(method = "getRelevantTransformation(Ljava/lang/String;I)[D", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$previewBus(String bus, int index, CallbackInfoReturnable<double[]> cir) {
        if (!acup.gunfeel.Arms.measuring && index == 0 && acup.gunfeel.ArmAnimationPreview.active()) cir.setReturnValue(acup.gunfeel.ArmAnimationPreview.transform(bus));
    }

    // Tone down NTM's own "RECOIL" animation bus in first person; the spring kick replaces most of it.
    @Inject(method = "getRelevantTransformation(Ljava/lang/String;I)[D", at = @At("RETURN"), cancellable = true)
    private static void gunfeel$recoilBus(String bus, int index, CallbackInfoReturnable<double[]> cir) {
        if (!Config.enabled || GunFeel.current == null || !"RECOIL".equals(bus)) return;
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
