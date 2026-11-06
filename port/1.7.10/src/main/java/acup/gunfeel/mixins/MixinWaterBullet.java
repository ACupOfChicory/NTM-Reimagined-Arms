package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import acup.gunfeel.water.WaterBullet;
import acup.gunfeel.water.WaterClass;
import acup.gunfeel.water.WaterPath;

// What an NTM bullet keeps of water between ticks (WaterBullet). Its speed is NTM's motionMult × f, so the server and
// the tracer move it alike; the path in water is worked out in MixinThrowableClip (WaterTick).
// port: 26.2's MixinWaterBullet (df28728) on NTM 1.7.10's EntityBulletBaseMK4 (the same motionMult and config). Like
// MixinBulletServer's bulletSpeed, the return value is modified, not set: a cancelling inject (setReturnValue) would end
// the method before the other, and a fast bullet out of water would fly at its full speed.
@Mixin(value = EntityBulletBaseMK4.class, remap = false)
public abstract class MixinWaterBullet implements WaterBullet {
    @Unique private double gunfeel$f = 1.0;
    @Unique private int gunfeel$skips;
    @Unique private boolean gunfeel$stopped;
    @Unique private WaterPath.Result gunfeel$tick;
    @Unique private WaterClass gunfeel$class;
    @Unique private WaterClass.Expansion gunfeel$expansion;
    @Unique private double gunfeel$opened;

    @ModifyReturnValue(method = "motionMult", at = @At("RETURN"))
    private double gunfeel$waterSpeed(double mult) {
        return gunfeel$f < 1.0 ? mult * gunfeel$f : mult;
    }

    @Override public double gunfeel$waterF() { return gunfeel$f; }
    @Override public void gunfeel$waterF(double f) { gunfeel$f = f; }
    @Override public int gunfeel$waterSkips() { return gunfeel$skips; }
    @Override public void gunfeel$waterSkips(int skips) { gunfeel$skips = skips; }
    @Override public boolean gunfeel$waterStopped() { return gunfeel$stopped; }
    @Override public void gunfeel$waterStopped(boolean stopped) { gunfeel$stopped = stopped; }
    @Override public WaterPath.Result gunfeel$waterTick() { return gunfeel$tick; }
    @Override public void gunfeel$waterTick(WaterPath.Result tick) { gunfeel$tick = tick; }
    @Override public double gunfeel$waterOpened() { return gunfeel$opened; }
    @Override public void gunfeel$waterOpened(double opened) { gunfeel$opened = opened; }

    @Override
    public WaterClass.Expansion gunfeel$waterExpansion() {
        gunfeel$waterClass();
        return gunfeel$expansion;
    }

    @Override
    public WaterClass gunfeel$waterClass() {
        if (gunfeel$class != null) return gunfeel$class;
        var config = ((EntityBulletBaseMK4) (Object) this).config;
        // NTM sets the config on the first tick
        if (config == null) return WaterClass.PISTOL;
        gunfeel$expansion = WaterClass.Expansion.ofConfig(config);
        return gunfeel$class = WaterClass.ofConfig(config);
    }
}
