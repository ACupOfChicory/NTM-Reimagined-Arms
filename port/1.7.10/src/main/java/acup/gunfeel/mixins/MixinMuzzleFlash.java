package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;

import acup.gunfeel.water.WaterFx;
import net.minecraft.client.Minecraft;

// No muzzle flash under water: the shot makes a gas bubble there instead (WaterFx.fired).
// port: 26.2's MixinMuzzleFlash on MuzzleFlash.submit; NTM 1.7.10 draws its flashes with the static
// ItemRenderWeaponBase.renderMuzzleFlash and renderGapFlash at the muzzle's GL matrix (Tracers.glWorldPoint).
@Mixin(value = ItemRenderWeaponBase.class, remap = false)
public abstract class MixinMuzzleFlash {
    @Inject(method = "renderMuzzleFlash(JID)V", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$underWater(long lastShot, int duration, double l, CallbackInfo ci) {
        if (gunfeel$wet()) ci.cancel();
    }

    @Inject(method = "renderGapFlash(J)V", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$gapUnderWater(long lastShot, CallbackInfo ci) {
        if (gunfeel$wet()) ci.cancel();
    }

    private static boolean gunfeel$wet() {
        if (!WaterFx.on() || acup.gunfeel.Arms.measuring) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return false;
        // the gun in this player's hands (first person) by the hands' projection, else a gun in the world
        double[] p = acup.gunfeel.Tracers.glWorldPoint(acup.gunfeel.GunFeelClient.current != null);
        if (p == null || !WaterFx.wetAt(mc.theWorld, new acup.gunfeel.phys.Vec3(p[0], p[1], p[2]))) return false;
        WaterFx.flashHidden++;
        return true;
    }
}
