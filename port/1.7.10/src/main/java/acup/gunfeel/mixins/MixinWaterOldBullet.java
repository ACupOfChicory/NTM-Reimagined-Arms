package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBulletBaseNT;
import com.hbm.handler.BulletConfiguration;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import acup.gunfeel.water.WaterBlast;
import acup.gunfeel.water.WaterBullet;
import acup.gunfeel.water.WaterClass;
import acup.gunfeel.water.WaterImpact;
import acup.gunfeel.water.WaterPath;
import acup.gunfeel.water.WaterTick;
import net.minecraft.util.MovingObjectPosition;

// port only: NTM 1.7.10's old EntityBulletBaseNT in water, as 26.2 X560 has the masked man's and the UFO's rounds
// (there NTM's own bullets, XFactoryNPC, with an impact fuze). Only the ones that blow up where they hit take water, by name
// as 26.2's (WaterClass.Fuze.ofOld, WaterTick.covers: the masked man's orb, bolt, rocket and meteor, the UFO's rocket); the rest stay NTM's. What
// MixinWaterBullet keeps for NTM's bullet, kept here: f (motionMult × f), skips, the stop, the tick's path. Besides:
// - its impact marks the old blast (WaterBlast.old): NTM's EntityProcessorStandard counts it at water as CrossSmooth's;
// - in water it sets nothing alight (the meteor): its config's incendiary reads 0 there, as the plain bullet's fire is
//   doused (WaterImpact.lambda);
// - a stopped one goes off where it touches a creature, also one that goes through creatures in flight (the bolt);
// - the surface its fuze meets takes no NTM ricochet: it goes off there;
// - a step straight down (a sinking one) keeps the yaw it had, as MixinWaterBullet.
@Mixin(value = EntityBulletBaseNT.class, remap = false)
public abstract class MixinWaterOldBullet implements WaterBullet, WaterImpact.Hits {
    @Shadow
    protected abstract void onImpact(MovingObjectPosition mop);

    @Shadow
    public abstract BulletConfiguration getConfig();

    @Override
    public void gunfeel$impact(MovingObjectPosition hit) {
        onImpact(hit);
    }

    @Unique private double gunfeel$f = 1.0;
    @Unique private int gunfeel$skips;
    @Unique private boolean gunfeel$stopped;
    @Unique private WaterPath.Result gunfeel$tick;
    @Unique private WaterClass gunfeel$class;
    @Unique private WaterClass.Fuze gunfeel$fuze = WaterClass.Fuze.NONE;
    @Unique private BulletConfiguration gunfeel$classOf;
    @Unique private double gunfeel$opened;
    @Unique private boolean gunfeel$met;
    @Unique private long gunfeel$shot;
    @Unique private net.minecraft.util.Vec3 gunfeel$goneAt;
    @Unique private acup.gunfeel.phys.Vec3 gunfeel$sunkHeading;
    @Unique private long gunfeel$sent;
    @Unique private float gunfeel$yaw;

    // the shot it was fired in: the world's time at its first tick (NTM makes one at a time, never a volley)
    @Inject(method = "onUpdate", at = @At("HEAD"), remap = true)
    private void gunfeel$before(CallbackInfo ci) {
        EntityBulletBaseNT self = (EntityBulletBaseNT) (Object) this;
        if (gunfeel$shot == 0 && self.worldObj != null) gunfeel$shot = self.worldObj.getTotalWorldTime();
        gunfeel$yaw = self.rotationYaw;
    }

    @Inject(method = "onUpdate", at = @At("RETURN"), remap = true)
    private void gunfeel$after(CallbackInfo ci) {
        EntityBulletBaseNT self = (EntityBulletBaseNT) (Object) this;
        double dx = self.posX - self.prevPosX, dz = self.posZ - self.prevPosZ;
        if (gunfeel$stopped && dx * dx + dz * dz < 1e-12) {
            self.rotationYaw = gunfeel$yaw;
            self.prevRotationYaw = gunfeel$yaw;
        }
    }

    // its impact: the blast a covered one sets off is an old bullet's (WaterBlast.old), and where it ended
    // (WaterImpact.touch). The mark comes off in finally: left on after a throw, it would turn every later
    // EntityProcessorStandard blast on this thread (C4, a creeper) into one in water
    @WrapMethod(method = "onImpact")
    private void gunfeel$impactMark(MovingObjectPosition mop, Operation<Void> original) {
        EntityBulletBaseNT self = (EntityBulletBaseNT) (Object) this;
        boolean old = WaterTick.covers(self);
        if (old) WaterBlast.oldBegin();
        try {
            original.call(mop);
        } finally {
            if (old) WaterBlast.oldEnd();
        }
        if (self.isDead && gunfeel$goneAt == null && mop != null && mop.hitVec != null)
            gunfeel$goneAt = net.minecraft.util.Vec3.createVectorHelper(mop.hitVec.xCoord, mop.hitVec.yCoord, mop.hitVec.zCoord);
    }

    // the fire it lights round the block it hit, and the blast's fire, and a creature set alight: none in water
    @WrapOperation(method = "onBlockImpact", at = @At(value = "FIELD", target = "Lcom/hbm/handler/BulletConfiguration;incendiary:I"))
    private int gunfeel$blockFire(BulletConfiguration config, Operation<Integer> original) {
        return gunfeel$doused() ? 0 : original.call(config);
    }

    @WrapOperation(method = "onEntityHurt", at = @At(value = "FIELD", target = "Lcom/hbm/handler/BulletConfiguration;incendiary:I"))
    private int gunfeel$mobFire(BulletConfiguration config, Operation<Integer> original) {
        return gunfeel$doused() ? 0 : original.call(config);
    }

    // where it is (NTM puts it on the point hit first) is in water, or on its surface
    @Unique
    private boolean gunfeel$doused() {
        EntityBulletBaseNT self = (EntityBulletBaseNT) (Object) this;
        return !self.worldObj.isRemote && WaterTick.covers(self) && acup.gunfeel.water.WaterSettings.ballistics()
                && WaterImpact.wet(self, new acup.gunfeel.phys.Vec3(self.posX, self.posY, self.posZ));
    }

    // the surface its fuze meets (WaterTick.surfaceHit) is no block to bounce off: NTM's random ricochet (doesRicochet,
    // HBRC: the bolt's 2 in 100; the grenade-like rocket and meteor have none) does not fire there, it goes off. Off a block it is NTM's
    @WrapOperation(method = "onImpact", at = @At(value = "FIELD", target = "Lcom/hbm/handler/BulletConfiguration;doesRicochet:Z"))
    private boolean gunfeel$noSurfaceRicochet(BulletConfiguration config, Operation<Boolean> original, @Local(argsOnly = true) MovingObjectPosition mop) {
        EntityBulletBaseNT self = (EntityBulletBaseNT) (Object) this;
        return original.call(config) && !(WaterTick.covers(self) && WaterTick.surfaceHit(self, mop));
    }

    // a stopped one that goes through creatures (the masked man's bolt, doesPenetrate) no longer does: sinking through
    // one it would hurt it every tick. It goes off where it touches, as NTM's bullet with an impact fuze does in 26.2
    // (onEntityImpact: its hit, its blast, gone)
    @ModifyReturnValue(method = "doesPenetrate", at = @At("RETURN"))
    private boolean gunfeel$sinkingStops(boolean penetrates) {
        return penetrates && !gunfeel$stopped;
    }

    @WrapOperation(method = {"onImpact", "onBlockImpact"}, at = @At(value = "FIELD", target = "Lcom/hbm/handler/BulletConfiguration;doesPenetrate:Z"))
    private boolean gunfeel$sinkingStopsHere(BulletConfiguration config, Operation<Boolean> original) {
        return original.call(config) && !gunfeel$stopped;
    }

    // as MixinWaterBullet: a stopped one sinking moves by its motion a tick
    @ModifyReturnValue(method = "motionMult", at = @At("RETURN"))
    private double gunfeel$waterSpeed(double mult) {
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

    // no expansion: none of them is a hollow or soft point
    @Override
    public WaterClass.Expansion gunfeel$waterExpansion() {
        return null;
    }

    @Override
    public WaterClass gunfeel$waterClass() {
        return gunfeel$known() ? gunfeel$class : WaterClass.PISTOL;
    }

    @Override
    public WaterClass.Fuze gunfeel$waterFuze() {
        return gunfeel$known() ? gunfeel$fuze : WaterClass.Fuze.NONE;
    }

    // its class and fuze, worked out once a config (NTM pulls it by its id on the first tick); false: none yet
    @Unique
    private boolean gunfeel$known() {
        BulletConfiguration config = getConfig();
        if (config == null) return false;
        if (gunfeel$classOf != config) {
            gunfeel$fuze = WaterClass.Fuze.ofOld(config);
            gunfeel$class = WaterClass.ofOld(config);
            gunfeel$classOf = config;
        }
        return true;
    }

    @Override
    public Object gunfeel$waterConfig() {
        return getConfig();
    }
}
