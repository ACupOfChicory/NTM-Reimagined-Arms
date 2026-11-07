package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBulletBase;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.water.OldBullets;
import acup.gunfeel.water.WaterBullet;
import acup.gunfeel.water.WaterClass;
import acup.gunfeel.water.WaterPath;
import acup.gunfeel.water.WaterTick;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

// port only (26.2 X560 fires these as NTM's bullet): water for NTM CE's old EntityBulletBase with a charge, the masked
// man's and the UFO's (OldBullets: the same configs as 26.2's list), and its plain ones 26.2 fires as NTM's bullet too
// (the masked man's tracer, the worm's bolts: no fuze, gone where water stops them). The same rules as NTM's bullets (MixinWaterBullet,
// MixinThrowableClip, WaterTick): an impact fuze on the water's surface, a skip under the fuze's angle, slowing in
// water, sinking once stopped and going off where it touches. On both sides: the old bullet flies on the client too.
// Its tick (2.6.1.0): the step is motion × config.velocity (read four times: the block test, the mob test, the area,
// the move), blocks by World.rayTraceBlocks, gravity off motionY when nothing was hit. So velocity is read × f here
// (a stopped one sinking: 1, its motion is the step), its gravity is held off while it sinks (WaterTick.sink takes it),
// NTM's update of it (bUpdate) does not turn it while it sinks (the UFO rocket's homing),
// and NTM's random ricochet does not turn the surface's fuze or the sinking round's touch into a bounce. NTM sets an old
// bullet off where it was at the tick's start (the bolt: up to 5 blocks short); on a tick in water it goes off where it
// touches (a block or a creature), as NTM's bullets do. The mobs' end of the path and the damage in water: MixinOldBulletBase.
@Mixin(value = EntityBulletBase.class, remap = false)
public abstract class MixinOldBulletWater implements WaterBullet {
    @Unique private double gunfeel$f = 1.0;
    @Unique private int gunfeel$skips;
    @Unique private boolean gunfeel$stopped;
    @Unique private WaterPath.Result gunfeel$tick;
    @Unique private WaterClass gunfeel$class;
    @Unique private double gunfeel$opened;
    @Unique private boolean gunfeel$metWater;
    @Unique private long gunfeel$shot;
    @Unique private Vec3d gunfeel$goneAt;
    @Unique private Vec3d gunfeel$sunkHeading;
    // water's rules apply this tick (OldBullets.on): asked at the first read of the config's fields, after NTM set
    // the config (on the bullet's first tick it is set in the tick); null: not asked yet this tick
    @Unique private Boolean gunfeel$water;

    @Inject(method = "onUpdate", at = @At("HEAD"), remap = true)
    private void gunfeel$waterBegin(CallbackInfo ci) {
        gunfeel$water = null;
    }

    @Unique
    private boolean gunfeel$waterOn() {
        if (gunfeel$water == null) gunfeel$water = OldBullets.on((EntityBulletBase) (Object) this);
        return gunfeel$water;
    }

    @ModifyExpressionValue(method = "onUpdate", remap = true, at = @At(value = "FIELD", remap = false,
            target = "Lcom/hbm/handler/BulletConfiguration;velocity:F"))
    private float gunfeel$waterSpeed(float velocity) {
        if (!gunfeel$waterOn()) return velocity;
        if (gunfeel$stopped) return 1F;
        return gunfeel$f < 1.0 ? (float) (velocity * gunfeel$f) : velocity;
    }

    @ModifyExpressionValue(method = "onUpdate", remap = true, at = @At(value = "FIELD", remap = false,
            target = "Lcom/hbm/handler/BulletConfiguration;gravity:F"))
    private float gunfeel$waterGravity(float gravity) {
        return gunfeel$waterOn() && gunfeel$stopped ? 0F : gravity;
    }

    // a stopped round sinks straight (WaterTick.sink sets its motion): NTM's update of it still runs (the meteor's
    // sparks, the orb's bolts, the UFO rocket's nearness fuze), but its steering (the UFO's rocket homes in) does not
    // turn it meanwhile
    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = false,
            target = "Lcom/hbm/interfaces/IBulletUpdateBehavior;behaveUpdate(Lcom/hbm/entity/projectile/EntityBulletBase;)V"))
    private void gunfeel$waterSteer(com.hbm.interfaces.IBulletUpdateBehavior update, EntityBulletBase self, Operation<Void> original) {
        if (!gunfeel$waterOn() || !gunfeel$stopped) {
            original.call(update, self);
            return;
        }
        double mx = self.motionX, my = self.motionY, mz = self.motionZ;
        original.call(update, self);
        self.motionX = mx;
        self.motionY = my;
        self.motionZ = mz;
    }

    // a hit that is the surface's fuze, or the sinking round's touch: it goes off, not bounces
    @Unique
    private boolean gunfeel$goesOff() {
        return gunfeel$waterOn() && (gunfeel$stopped || WaterTick.fuzedOnSurface((EntityBulletBase) (Object) this));
    }

    @ModifyExpressionValue(method = "onUpdate", remap = true, at = @At(value = "FIELD", remap = false,
            target = "Lcom/hbm/handler/BulletConfiguration;doesRicochet:Z"))
    private boolean gunfeel$waterRicochet(boolean ricochet) {
        return ricochet && !gunfeel$goesOff();
    }

    @ModifyExpressionValue(method = "onUpdate", remap = true, at = @At(value = "FIELD", remap = false,
            target = "Lcom/hbm/handler/BulletConfiguration;HBRC:I"))
    private int gunfeel$waterRandomRicochet(int chance) {
        return gunfeel$goesOff() ? 0 : chance;
    }

    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/World;rayTraceBlocks(Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;ZZZ)Lnet/minecraft/util/math/RayTraceResult;"))
    private RayTraceResult gunfeel$waterBlocks(World world, Vec3d from, Vec3d to, boolean liquids, boolean noBox, boolean last,
            Operation<RayTraceResult> original) {
        if (!gunfeel$waterOn()) {
            gunfeel$tick = null;
            return original.call(world, from, to, liquids, noBox, last);
        }
        // `to` is the slowed step already (gunfeel$waterSpeed)
        EntityBulletBase self = (EntityBulletBase) (Object) this;
        to = WaterTick.path(self, world, from, to);
        return WaterTick.blocks(self, original.call(world, from, to, liquids, noBox, last));
    }

    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = false,
            target = "Lcom/hbm/entity/projectile/EntityBulletBase;onBlockImpact(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/RayTraceResult;)V"))
    private void gunfeel$waterImpact(EntityBulletBase self, net.minecraft.util.math.BlockPos pos, RayTraceResult hit,
            Operation<Void> original) {
        if (gunfeel$waterOn() && (gunfeel$stopped || gunfeel$tick != null) && hit != null && hit.hitVec != null)
            self.setPosition(hit.hitVec.x, hit.hitVec.y, hit.hitVec.z);
        // where it went off: NTM moves an old bullet that did not bounce a whole step on after its impact, dead or not
        if (!gunfeel$waterOn()) {
            original.call(self, pos, hit);
            return;
        }
        gunfeel$goneAt = new Vec3d(self.posX, self.posY, self.posZ);
        // its blast (the game's own) hurts creatures by water's rule (OldBlast, MixinOldBlast)
        acup.gunfeel.water.OldBlast.impact(() -> original.call(self, pos, hit));
    }

    // a creature met in water: it goes off where it met it too (NTM's onEntityImpact sets it off at its position)
    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = false,
            target = "Lcom/hbm/entity/projectile/EntityBulletBase;onEntityImpact(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/RayTraceResult;)V"))
    private void gunfeel$waterMobImpact(EntityBulletBase self, net.minecraft.entity.Entity target, RayTraceResult hit,
            Operation<Void> original) {
        if (!gunfeel$waterOn()) {
            original.call(self, target, hit);
            return;
        }
        if ((gunfeel$stopped || gunfeel$tick != null) && hit != null && hit.hitVec != null)
            self.setPosition(hit.hitVec.x, hit.hitVec.y, hit.hitVec.z);
        acup.gunfeel.water.OldBlast.impact(() -> original.call(self, target, hit));
    }

    @Inject(method = "onUpdate", at = @At("RETURN"), remap = true)
    private void gunfeel$waterEnd(CallbackInfo ci) {
        if (Boolean.TRUE.equals(gunfeel$water)) WaterTick.end((EntityBulletBase) (Object) this);
    }

    @Override public boolean gunfeel$waterMet() { return gunfeel$metWater; }
    @Override public void gunfeel$waterMet(boolean met) { gunfeel$metWater = met; }
    @Override public long gunfeel$shot() { return gunfeel$shot; }
    @Override public void gunfeel$shot(long shot) { gunfeel$shot = shot; }
    @Override public Vec3d gunfeel$goneAt() { return gunfeel$goneAt; }
    @Override public void gunfeel$goneAt(Vec3d at) { gunfeel$goneAt = at; }
    @Override public Vec3d gunfeel$sunkHeading() { return gunfeel$sunkHeading; }
    @Override public void gunfeel$sunkHeading(Vec3d heading) { gunfeel$sunkHeading = heading; }

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

    // no hollow or soft point
    @Override public WaterClass.Expansion gunfeel$waterExpansion() { return null; }

    @Override
    public WaterClass gunfeel$waterClass() {
        if (gunfeel$class != null) return gunfeel$class;
        com.hbm.handler.BulletConfiguration config = ((EntityBulletBase) (Object) this).getConfig();
        if (config == null) return WaterClass.PISTOL;
        return gunfeel$class = OldBullets.cls(config);
    }
}
