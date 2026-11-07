package acup.gunfeel.mixins;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.config.CompatibilityConfig;
import com.hbm.entity.projectile.EntityBulletBase;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.LegacyBullets;
import acup.gunfeel.ModelAABB;
import acup.gunfeel.ModelHits;
import net.minecraft.entity.Entity;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

// port only (26.2 has no such bullet): NTM CE's old EntityBulletBase, fired by the masked man (minigun, orbs, rockets,
// tracers, meteors), the UFO, BOT Prime and the death blast. Server, npcShots: its hit counts where it met the creature's
// model, head x1.75, limbs x0.5, as MixinLegacyBullet does for EntityBullet. Its blocks stay NTM's: its configs bounce,
// ricochet, explode or pass through blocks their own way, and the client draws it with no tracer to leave a hole from.
// The tested NTM CE (2.6.1.0) has no config for the rounds of the old player guns (ItemGunBase: the Super Shotgun's
// G12_*; the Vortex hits by a ray, no bullet) nor the trapped brick's dart: those fire nothing of this.
// The tested jar (2.6.1.0) asks World.rayTraceBlocks for blocks, meets creatures by getEntitiesWithinAABBExcludingEntity,
// getEntityBoundingBox().grow(0.3) and calculateIntercept and takes that intercept as the hit (no new RayTraceResult),
// and hurts with attackEntityFrom: its damage, or (the creature still shielded by its last hit) that plus the last hit.
@Mixin(value = EntityBulletBase.class, remap = false)
public abstract class MixinOldBulletBase {
    // this tick: where each creature's model was met; the change the head or a limb makes to the hit
    @Unique private final Map<Entity, Vec3d> gunfeel$met = new IdentityHashMap<Entity, Vec3d>();
    @Unique private float gunfeel$extra;
    @Unique private boolean gunfeel$counted;

    @Inject(method = "onUpdate", at = @At("HEAD"), remap = true)
    private void gunfeel$begin(CallbackInfo ci) {
        gunfeel$met.clear();
        gunfeel$counted = false;
        gunfeel$extra = 0F;
    }

    // NTM hurts creatures only in its war dimensions; elsewhere a creature it meets is taken for a block: its box is left
    @Unique
    private static boolean gunfeel$on(World world) {
        return !world.isRemote && LegacyBullets.on() && CompatibilityConfig.isWarDim(world);
    }

    // a creature whose model reaches out of its box is met too (as MixinLegacyBullet)
    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/World;getEntitiesWithinAABBExcludingEntity(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/AxisAlignedBB;)Ljava/util/List;"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List gunfeel$candidates(World world, Entity except, AxisAlignedBB area, Operation<List> original) {
        List found = original.call(world, except, area);
        return !gunfeel$on(world) ? found : ModelHits.candidates(world, except, (List<Entity>) found, area, e -> true);
    }

    // a creature's box is its model's (ModelAABB): grow keeps it one
    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/entity/Entity;getEntityBoundingBox()Lnet/minecraft/util/math/AxisAlignedBB;"))
    private AxisAlignedBB gunfeel$box(Entity entity, Operation<AxisAlignedBB> original) {
        if (entity == (Object) this || !gunfeel$on(entity.world)) return original.call(entity);
        return ModelAABB.of(entity);
    }

    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/util/math/AxisAlignedBB;calculateIntercept(Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/RayTraceResult;"))
    private RayTraceResult gunfeel$clip(AxisAlignedBB box, Vec3d from, Vec3d to, Operation<RayTraceResult> original) {
        RayTraceResult out = original.call(box, from, to);
        if (out != null && out.hitVec != null && box instanceof ModelAABB) gunfeel$met.put(((ModelAABB) box).entity(), out.hitVec);
        return out;
    }

    // the first hurt carries NTM's rolled damage: the change is worked out from it and added to the second too. NTM's
    // try/catch around both would turn a throw here into no damage at all: it keeps NTM's damage instead
    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/entity/Entity;attackEntityFrom(Lnet/minecraft/util/DamageSource;F)Z"))
    private boolean gunfeel$hurt(Entity target, DamageSource source, float amount, Operation<Boolean> original) {
        if (!gunfeel$counted) {
            gunfeel$counted = true;
            Vec3d point = gunfeel$met.get(target);
            if (point != null && gunfeel$on(target.world)) {
                try {
                    gunfeel$extra = LegacyBullets.damage(target, point, amount) - amount;
                } catch (RuntimeException e) {
                    gunfeel$extra = 0F;
                }
            }
        }
        return original.call(target, source, amount + gunfeel$extra);
    }

    @Inject(method = "onUpdate", at = @At("RETURN"), remap = true)
    private void gunfeel$end(CallbackInfo ci) {
        gunfeel$met.clear();
    }
}
