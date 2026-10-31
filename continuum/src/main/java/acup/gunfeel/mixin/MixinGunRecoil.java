package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.client.anim.GunRecoil;
import com.hbm.config.ClientConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.CameraRecoil;
import acup.gunfeel.Config;

// port/continuum: MixinItemGunBaseNT.gunfeel$recoilTick on NTM Continuum, whose 20 Hz camera recoil is GunRecoil.tick.
// The new impulse goes to CameraRecoil, which applies it per frame.
@Mixin(value = GunRecoil.class, remap = false)
public abstract class MixinGunRecoil {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$recoilTick(CallbackInfo ci) {
        if (!Config.enabled || !ClientConfig.GUN_VISUAL_RECOIL) return;
        if (ItemGunBaseNT.recoilVertical != 0F || ItemGunBaseNT.recoilHorizontal != 0F) {
            CameraRecoil.addImpulse(ItemGunBaseNT.recoilVertical, ItemGunBaseNT.recoilHorizontal);
        }
        ItemGunBaseNT.recoilVertical = 0F;
        ItemGunBaseNT.recoilHorizontal = 0F;
        ItemGunBaseNT.offsetVertical = 0F;
        ItemGunBaseNT.offsetHorizontal = 0F;
        ci.cancel();
    }
}
