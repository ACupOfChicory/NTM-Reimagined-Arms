package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import acup.gunfeel.water.WaterBullet;
import acup.gunfeel.water.WaterClass;
import acup.gunfeel.water.WaterImpact;
import acup.gunfeel.water.WaterPath;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

// What an NTM bullet keeps of water between ticks (WaterBullet). Its speed is NTM's motionMult × f, so the server and
// the tracer move it alike; the path in water is worked out in MixinThrowableClip (WaterTick).
// port: 26.2's MixinWaterBullet (a1f618b) on NTM 1.7.10's EntityBulletBaseMK4 (the same motionMult, config and
// onImpact).
@Mixin(value = EntityBulletBaseMK4.class, remap = false)
public abstract class MixinWaterBullet implements WaterBullet, WaterImpact.Hits {
    @Shadow
    protected abstract void onImpact(MovingObjectPosition mop);

    @Override
    public void gunfeel$impact(MovingObjectPosition hit) {
        onImpact(hit);
    }

    @Unique private double gunfeel$f = 1.0;
    @Unique private int gunfeel$skips;
    @Unique private boolean gunfeel$stopped;
    @Unique private WaterPath.Result gunfeel$tick;
    @Unique private WaterClass gunfeel$class;
    @Unique private WaterClass.Expansion gunfeel$expansion;
    @Unique private double gunfeel$opened;
    @Unique private boolean gunfeel$met;
    @Unique private long gunfeel$shot;
    @Unique private net.minecraft.util.Vec3 gunfeel$goneAt;
    @Unique private acup.gunfeel.phys.Vec3 gunfeel$sunkHeading;
    @Unique private long gunfeel$sent;
    @Unique private float gunfeel$yaw;

    // every constructor of NTM's comes through this one; a gun makes all pellets of a shot in one loop
    @Inject(method = "<init>(Lnet/minecraft/world/World;)V", at = @At("TAIL"), remap = true)
    private void gunfeel$born(World world, CallbackInfo ci) {
        if (world != null) gunfeel$shot = world.getTotalWorldTime();
    }

    // port: NTM's tick turns its yaw to atan2(dX, dZ) of its step, on the server and the client, so a step straight
    // down (a sinking round) turns it to 0, and SunkBullets' fallback heading (the yaw) would always lie along +Z. A
    // step with no level part keeps the yaw it had (prevRotationYaw too, so it does not swing); the server's yaw then
    // also reaches the players who come in sight of it later
    @Inject(method = "onUpdate", at = @At("HEAD"), remap = true)
    private void gunfeel$yawBefore(CallbackInfo ci) {
        gunfeel$yaw = ((EntityBulletBaseMK4) (Object) this).rotationYaw;
    }

    @Inject(method = "onUpdate", at = @At("RETURN"), remap = true)
    private void gunfeel$yawAfter(CallbackInfo ci) {
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        double dx = self.posX - self.prevPosX, dz = self.posZ - self.prevPosZ;
        if (dx * dx + dz * dz < 1e-12) {
            self.rotationYaw = gunfeel$yaw;
            self.prevRotationYaw = gunfeel$yaw;
        }
    }

    // modified, not set: a cancelling inject would end the method before the others (bulletSpeed, MixinBulletServer),
    // and this one is applied after it, so it multiplies the fast bullets' speed too
    @ModifyReturnValue(method = "motionMult", at = @At("RETURN"))
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
    @Override public WaterPath.Result gunfeel$waterTick() { return gunfeel$tick; }
    @Override public void gunfeel$waterTick(WaterPath.Result tick) { gunfeel$tick = tick; }
    @Override public double gunfeel$waterOpened() { return gunfeel$opened; }
    @Override public void gunfeel$waterOpened(double opened) { gunfeel$opened = opened; }
    @Override public boolean gunfeel$waterMet() { return gunfeel$met; }
    @Override public void gunfeel$waterMet(boolean met) { gunfeel$met = met; }
    @Override public long gunfeel$shot() { return gunfeel$shot; }
    @Override public void gunfeel$shot(long shot) { gunfeel$shot = shot; }
    @Override public net.minecraft.util.Vec3 gunfeel$goneAt() { return gunfeel$goneAt; }
    @Override public void gunfeel$goneAt(net.minecraft.util.Vec3 at) { gunfeel$goneAt = at; }
    @Override public acup.gunfeel.phys.Vec3 gunfeel$sunkHeading() { return gunfeel$sunkHeading; }
    @Override public void gunfeel$sunkHeading(acup.gunfeel.phys.Vec3 heading) { gunfeel$sunkHeading = heading; }
    @Override public long gunfeel$sent() { return gunfeel$sent; }
    @Override public void gunfeel$sent(long sent) { gunfeel$sent = sent; }

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
