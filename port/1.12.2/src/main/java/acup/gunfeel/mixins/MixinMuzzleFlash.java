package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;

import acup.gunfeel.water.WaterFx;

// 26.2's MixinMuzzleFlash: no muzzle flash under water, the shot makes a gas bubble there instead (WaterFx.fired).
// port: NTM CE draws its flashes in ItemRenderWeaponBase's static renderMuzzleFlash (the short form calls the long
// one) and renderGapFlash, at the muzzle's GL matrix: the point is that matrix's origin in the world.
@Mixin(value = ItemRenderWeaponBase.class, remap = false)
public abstract class MixinMuzzleFlash {
    @Inject(method = "renderMuzzleFlash(JID)V", at = @At("HEAD"), cancellable = true, require = 1)
    private static void gunfeel$underWater(long lastShot, int duration, double l, CallbackInfo ci) {
        if (System.currentTimeMillis() - lastShot < duration && WaterFx.flashWet()) ci.cancel();
    }

    @Inject(method = "renderGapFlash(J)V", at = @At("HEAD"), cancellable = true, require = 1)
    private static void gunfeel$gapUnderWater(long lastShot, CallbackInfo ci) {
        if (System.currentTimeMillis() - lastShot < 75 && WaterFx.flashWet()) ci.cancel();
    }
}
