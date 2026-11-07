package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityThrowableInterp;

import acup.gunfeel.water.WaterBullet;

// port only (26.2's bullet comes with its motion). Client: NTM 1.7.10's bullet gets its motion only from the server's
// S12 (WaterTick.announce; the spawn has none). setVelocity (client only in NTM, as in vanilla) marks the bullet
// when the first S12 arrives, so SunkBullets.sinking can tell "no motion yet" from "sinking".
@Mixin(value = EntityThrowableInterp.class, remap = false)
public abstract class MixinThrowableMotion {
    @Inject(method = "setVelocity", at = @At("HEAD"), remap = true)
    private void gunfeel$moved(double x, double y, double z, CallbackInfo ci) {
        if ((Object) this instanceof WaterBullet w) w.gunfeel$sent(1);
    }
}
