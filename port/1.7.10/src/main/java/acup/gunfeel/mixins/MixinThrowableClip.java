package acup.gunfeel.mixins;

import java.util.HashSet;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.entity.projectile.EntityThrowableNT;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.BlockRay;
import acup.gunfeel.BulletServer;
import acup.gunfeel.PassableClip;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

// 26.2: MixinThrowableHit (0762086), the bullets' part: their path through blocks and point-blank hits. (Its other
// part, the mobs' hitboxes from their models, is the hitboxes' port; both wrap the same calls with WrapOperation, so
// they chain.) NTM 1.7.10's EntityThrowableNT.onUpdate asks World.func_147447_a for blocks, then
// AxisAlignedBB.calculateIntercept for every mob near (lines 177-199); NTM's bullet ticks there only on the server (the
// client's EntityThrowableInterp moves it by the server's updates).
//  - Blocks: a bullet's path goes through the mod's own walk (BlockRay, PassableClip) and piercing (BulletServer.clip)
//    instead of func_147447_a: 26.2's cells and collision boxes, no 200-cell limit.
//  - Point-blank: NTM's bullet starts a little in front of the shooter's eyes, and a hitbox that already contains the
//    start of the path is never hit (calculateIntercept misses a segment starting inside). On the bullet's first tick
//    its path starts at the shooter's eyes (blocks and mobs in between count), and a path starting inside a hitbox hits it.
//  - A trace (-Dntmgunfeel.trace): NTM's spread draws from the new bullet's own random; seeded from the trace's seed by
//    the order of the shots, so every run fires the same bullets.
@Mixin(value = EntityThrowableNT.class, remap = false)
public abstract class MixinThrowableClip extends Entity {
    private MixinThrowableClip(World world) {
        super(world);
    }

    // blocks the bullet pierced (bullet rules "pierce"), kept between ticks: one it stopped inside of is not paid twice
    @Unique
    private Set<Long> gunfeel$pierced;

    @Inject(method = "setThrowableHeading", at = @At("HEAD"), remap = true)
    private void gunfeel$traceSpread(double x, double y, double z, float velocity, float inaccuracy, CallbackInfo ci) {
        if (acup.gunfeel.Trace.ON && !worldObj.isRemote) rand.setSeed(acup.gunfeel.Rng.spread());
    }

    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/World;func_147447_a(Lnet/minecraft/util/Vec3;Lnet/minecraft/util/Vec3;ZZZ)Lnet/minecraft/util/MovingObjectPosition;"))
    private MovingObjectPosition gunfeel$blocks(World world, Vec3 from, Vec3 to, boolean liquids, boolean noBox, boolean last,
            Operation<MovingObjectPosition> original) {
        if (!((Object) this instanceof EntityBulletBaseMK4)) return original.call(world, from, to, liquids, noBox, last);
        EntityBulletBaseMK4 bullet = (EntityBulletBaseMK4) (Object) this;
        Vec3 start = gunfeel$start(from);
        BlockRay.Hit hit;
        if (world.isRemote) {
            hit = PassableClip.clip(world, start, to);
        } else {
            if (gunfeel$pierced == null) gunfeel$pierced = new HashSet<Long>();
            hit = BulletServer.clip(bullet, world, start, to, gunfeel$pierced);
        }
        return hit == null ? null : new MovingObjectPosition(hit.x, hit.y, hit.z, hit.face, Vec3.createVectorHelper(hit.ax, hit.ay, hit.az));
    }

    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/util/AxisAlignedBB;calculateIntercept(Lnet/minecraft/util/Vec3;Lnet/minecraft/util/Vec3;)Lnet/minecraft/util/MovingObjectPosition;"))
    private MovingObjectPosition gunfeel$mob(AxisAlignedBB box, Vec3 from, Vec3 to, Operation<MovingObjectPosition> original) {
        if (!((Object) this instanceof EntityBulletBaseMK4)) return original.call(box, from, to);
        Vec3 start = gunfeel$start(from);
        // 26.2's AABB.contains: min inclusive, max exclusive
        if (start.xCoord >= box.minX && start.xCoord < box.maxX && start.yCoord >= box.minY && start.yCoord < box.maxY
                && start.zCoord >= box.minZ && start.zCoord < box.maxZ)
            return new MovingObjectPosition(0, 0, 0, -1, Vec3.createVectorHelper(start.xCoord, start.yCoord, start.zCoord));
        return original.call(box, start, to);
    }

    // the path's start: on the bullet's first tick the shooter's eyes, when it was fired from them (a bullet spawned
    // elsewhere, such as the Shredder's plasma drops at the beam's end, starts where it is)
    @Unique
    private Vec3 gunfeel$start(Vec3 from) {
        EntityThrowableNT self = (EntityThrowableNT) (Object) this;
        if (self.ticksInAir != 1) return from;
        EntityLivingBase shooter = self.getThrower();
        if (shooter == null) return from;
        double ex = shooter.posX, ey = shooter.posY + shooter.getEyeHeight(), ez = shooter.posZ;
        double dx = ex - from.xCoord, dy = ey - from.yCoord, dz = ez - from.zCoord;
        return dx * dx + dy * dy + dz * dz < 9.0 ? Vec3.createVectorHelper(ex, ey, ez) : from;
    }
}
