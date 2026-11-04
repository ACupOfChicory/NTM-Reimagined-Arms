package acup.gunfeel.mixins;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;

import acup.gunfeel.Config;
import acup.gunfeel.GunFeelClient;
import acup.gunfeel.HandsScenario;
import acup.gunfeel.Tracers;

// The muzzle of the gun in the player's hands: NTM draws the muzzle smoke there, so the player's own tracers start
// there (26.2: MixinItemRenderWeaponBase.gunfeel$smoke). A class of its own beside MixinItemRenderWeaponBase.
@Mixin(value = ItemRenderWeaponBase.class, remap = false)
public abstract class MixinSmokeNodes {
    @Inject(method = "renderSmokeNodes(Ljava/util/List;D)V", at = @At("HEAD"))
    private static void gunfeel$muzzle(List<?> nodes, double scale, CallbackInfo ci) {
        if (GunFeelClient.current != null) Tracers.muzzle();
    }

    // Muzzle smoke width (smokeWidth, 26.2: MixinItemRenderWeaponBase.gunfeel$smokeWidth): the scale argument is the
    // smoke's width, only the gun in the player's hands. NTM CE skips nodes faded below zero itself (26.2 clamps them).
    @ModifyVariable(method = "renderSmokeNodes(Ljava/util/List;D)V", at = @At("HEAD"), argsOnly = true)
    private static double gunfeel$smokeWidth(double scale) {
        double out = Config.enabled && GunFeelClient.current != null ? scale * Config.smokeWidth : scale;
        if (HandsScenario.on()) HandsScenario.smoke(scale, out);
        return out;
    }
}
