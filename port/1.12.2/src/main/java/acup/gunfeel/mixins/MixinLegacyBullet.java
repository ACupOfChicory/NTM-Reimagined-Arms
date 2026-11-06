package acup.gunfeel.mixins;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.config.CompatibilityConfig;
import com.hbm.entity.projectile.EntityBullet;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.BulletServer;
import acup.gunfeel.LegacyBullets;
import acup.gunfeel.ModelAABB;
import acup.gunfeel.ModelHits;
import net.minecraft.entity.Entity;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

// 26.2's MixinLegacyBullet (X547). Server: NTM's old bullet (the hunter chopper's, the cyber crab's) through the mod's
// systems, see LegacyBullets. Its tick meets the creatures by their boxes (model boxes, ModelAABB) and then hits the
// nearest at its middle: here the point where the model was met is kept for the damage. A block on its path goes by
// the bullet rules, and where it stops a hole is sent. A "critical" one (the crab's) flies through blocks in NTM and
// still does.
// port: NTM CE's EntityBullet.onUpdate asks World.rayTraceBlocks for blocks (26.2: Level.clip; CE's later sources ask
// Library.rayTraceBlocksForProjectile, the tested 2.6.1.0 does not), meets
// creatures by getEntitiesWithinAABBExcludingEntity, getEntityBoundingBox().grow(0.3) and calculateIntercept, and hurts
// with attackEntityFrom (26.2: hurtServer). CE's bullet hits nothing outside its war dimensions
// (CompatibilityConfig.isWarDim): there the rules leave its blocks alone too.
@Mixin(value = EntityBullet.class, remap = false)
public abstract class MixinLegacyBullet {
    @Shadow public double damage;
    @Shadow public Entity shootingEntity;

    @Shadow public abstract boolean getIsCritical();

    // this tick: where each creature's model was met, the one hit and where, the block it stopped in
    @Unique private final Map<Entity, Vec3d> gunfeel$met = new IdentityHashMap<Entity, Vec3d>();
    @Unique private Vec3d gunfeel$point;
    @Unique private RayTraceResult gunfeel$stuck;

    @Inject(method = "onUpdate", at = @At("HEAD"), remap = true)
    private void gunfeel$begin(CallbackInfo ci) {
        gunfeel$met.clear();
        gunfeel$point = null;
        gunfeel$stuck = null;
    }

    // a creature whose model reaches out of its box is met too (as NTM's bullets, MixinThrowableClip)
    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/World;getEntitiesWithinAABBExcludingEntity(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/AxisAlignedBB;)Ljava/util/List;"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List gunfeel$candidates(World world, Entity except, AxisAlignedBB area, Operation<List> original) {
        List found = original.call(world, except, area);
        return world.isRemote || !LegacyBullets.on() ? found : ModelHits.candidates(world, except, (List<Entity>) found, area, e -> true);
    }

    // a creature's box is its model's (ModelAABB): grow keeps it one
    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/entity/Entity;getEntityBoundingBox()Lnet/minecraft/util/math/AxisAlignedBB;"))
    private AxisAlignedBB gunfeel$box(Entity entity, Operation<AxisAlignedBB> original) {
        if (entity == (Object) this || entity.world.isRemote || !LegacyBullets.on()) return original.call(entity);
        return ModelAABB.of(entity);
    }

    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/util/math/AxisAlignedBB;calculateIntercept(Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/RayTraceResult;"))
    private RayTraceResult gunfeel$clip(AxisAlignedBB box, Vec3d from, Vec3d to, Operation<RayTraceResult> original) {
        RayTraceResult out = original.call(box, from, to);
        if (out != null && out.hitVec != null && box instanceof ModelAABB) gunfeel$met.put(((ModelAABB) box).entity(), out.hitVec);
        return out;
    }

    // a constructor by its descriptor, as 26.2: no name to remap (through the refmap the descriptor came out spoilt)
    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "NEW", remap = false,
            target = "(Lnet/minecraft/entity/Entity;)Lnet/minecraft/util/math/RayTraceResult;"))
    private RayTraceResult gunfeel$hit(Entity entity, Operation<RayTraceResult> original) {
        gunfeel$point = gunfeel$met.get(entity);
        gunfeel$stuck = null; // a creature before the block: it hits the creature
        return original.call(entity);
    }

    // NTM hurts with its damage, or (the creature still shielded by its last hit) that plus the last hit: the change
    // the head or a limb makes is added to either
    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/entity/Entity;attackEntityFrom(Lnet/minecraft/util/DamageSource;F)Z"))
    private boolean gunfeel$hurt(Entity target, DamageSource source, float amount, Operation<Boolean> original) {
        return original.call(target, source, amount + gunfeel$extra(target));
    }

    @Unique
    private float gunfeel$extra(Entity target) {
        if (gunfeel$point == null || target.world.isRemote || !LegacyBullets.on()) return 0F;
        float base = (float) damage;
        return LegacyBullets.damage(target, gunfeel$point, base) - base;
    }

    // the bullet rules: a block it breaks and flies on through is not met; the path goes on behind it (the same walk,
    // from just past the broken block). The block is asked before NTM picks a creature: when a creature stands before
    // it within this tick's step, NTM hits the creature instead and its bullet flies its whole step on through the
    // block (as 26.2): the block is worn by then and gets no hole
    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/World;rayTraceBlocks(Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;ZZZ)Lnet/minecraft/util/math/RayTraceResult;"))
    private RayTraceResult gunfeel$blocks(World world, Vec3d from, Vec3d to, boolean liquids, boolean noBox, boolean last,
            Operation<RayTraceResult> original) {
        RayTraceResult hit = original.call(world, from, to, liquids, noBox, last);
        if (world.isRemote || getIsCritical() || !LegacyBullets.on() || !CompatibilityConfig.isWarDim(world)) return hit;
        Entity self = (Entity) (Object) this;
        for (int i = 0; hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK && hit.hitVec != null; i++) {
            if (i == 8) { // a stack of broken blocks past the limit: it stops in this one
                gunfeel$stuck = hit;
                return hit;
            }
            int got = BulletServer.blockHit(self, shootingEntity, (float) damage, hit.getBlockPos().getX(), hit.getBlockPos().getY(),
                    hit.getBlockPos().getZ(), hit.hitVec.x, hit.hitVec.y, hit.hitVec.z);
            if (got == 1) return hit; // broke, and stops there: no block left for a hole
            if (got == 0) {
                gunfeel$stuck = hit;
                return hit;
            }
            Vec3d dir = to.subtract(hit.hitVec);
            if (dir.lengthSquared() < 1e-12) return null;
            hit = original.call(world, hit.hitVec.add(dir.normalize().scale(1e-3)), to, liquids, noBox, last);
        }
        return hit;
    }

    @Inject(method = "onUpdate", at = @At("RETURN"), remap = true)
    private void gunfeel$hole(CallbackInfo ci) {
        RayTraceResult stuck = gunfeel$stuck;
        gunfeel$stuck = null;
        gunfeel$met.clear();
        if (stuck != null) LegacyBullets.stuck((EntityBullet) (Object) this, stuck);
    }
}
