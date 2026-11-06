package acup.gunfeel.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import acup.gunfeel.water.WaterBullet;
import acup.gunfeel.water.WaterClass;
import acup.gunfeel.water.WaterPath;

// Both sides: what an NTM bullet keeps of water between ticks (WaterBullet). Its speed is NTM's motionMult × f, so the
// server, the client's bullet and the tracer move it alike; the path in water is worked out in MixinThrowableHit
// (WaterTick).
@Mixin(value = EntityBulletBaseMK4.class, remap = false)
public abstract class MixinWaterBullet implements WaterBullet, acup.gunfeel.water.WaterImpact.Hits {
    @org.spongepowered.asm.mixin.Shadow protected abstract void onImpact(net.minecraft.world.phys.HitResult mop);

    @Override public void gunfeel$impact(net.minecraft.world.phys.HitResult hit) { onImpact(hit); }

    @Unique private double gunfeel$f = 1.0;
    @Unique private int gunfeel$skips;
    @Unique private boolean gunfeel$stopped;
    @Unique private WaterPath.@Nullable Result gunfeel$tick;
    @Unique private @Nullable WaterClass gunfeel$class;
    @Unique private WaterClass.@Nullable Expansion gunfeel$expansion;
    @Unique private double gunfeel$opened;
    @Unique private boolean gunfeel$met;
    @Unique private long gunfeel$shot;
    @Unique private net.minecraft.world.phys.@Nullable Vec3 gunfeel$goneAt;

    // every constructor of NTM's comes through this one; a gun makes all pellets of a shot in one loop
    @Inject(method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V", at = @At("TAIL"))
    private void gunfeel$born(net.minecraft.world.entity.EntityType<?> type, net.minecraft.world.level.Level level,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        gunfeel$shot = level.getGameTime();
    }

    // modified, not set: a cancelling inject would end the method before the others (bulletSpeed, MixinBulletServer),
    // and this one is applied after it, so it multiplies the fast bullets' speed too
    @com.llamalad7.mixinextras.injector.ModifyReturnValue(method = "motionMult", at = @At("RETURN"))
    private double gunfeel$waterSpeed(double mult) {
        // a stopped explosive sinking: its motion is its path a tick (WaterTick.sink)
        if (gunfeel$stopped) return 1.0;
        return gunfeel$f < 1.0 ? mult * gunfeel$f : mult;
    }

    @Override public double gunfeel$waterF() { return gunfeel$f; }
    @Override public void gunfeel$waterF(double f) { gunfeel$f = f; }
    @Override public int gunfeel$waterSkips() { return gunfeel$skips; }
    @Override public void gunfeel$waterSkips(int skips) { gunfeel$skips = skips; }
    @Override public boolean gunfeel$waterStopped() { return gunfeel$stopped; }
    @Override public void gunfeel$waterStopped(boolean stopped) { gunfeel$stopped = stopped; }
    @Override public WaterPath.@Nullable Result gunfeel$waterTick() { return gunfeel$tick; }
    @Override public void gunfeel$waterTick(WaterPath.@Nullable Result tick) { gunfeel$tick = tick; }
    @Override public double gunfeel$waterOpened() { return gunfeel$opened; }
    @Override public void gunfeel$waterOpened(double opened) { gunfeel$opened = opened; }
    @Override public boolean gunfeel$waterMet() { return gunfeel$met; }
    @Override public void gunfeel$waterMet(boolean met) { gunfeel$met = met; }
    @Override public long gunfeel$shot() { return gunfeel$shot; }
    @Override public void gunfeel$shot(long shot) { gunfeel$shot = shot; }
    @Override public net.minecraft.world.phys.@Nullable Vec3 gunfeel$goneAt() { return gunfeel$goneAt; }
    @Override public void gunfeel$goneAt(net.minecraft.world.phys.@Nullable Vec3 at) { gunfeel$goneAt = at; }

    @Override
    public WaterClass.@Nullable Expansion gunfeel$waterExpansion() {
        gunfeel$waterClass();
        return gunfeel$expansion;
    }

    @Override
    public WaterClass gunfeel$waterClass() {
        if (gunfeel$class != null) return gunfeel$class;
        var config = ((EntityBulletBaseMK4) (Object) this).config;
        // NTM sets the config on the first tick
        if (config == null) return WaterClass.PISTOL;
        gunfeel$expansion = WaterClass.Expansion.of(config);
        return gunfeel$class = WaterClass.of(config);
    }
}
