package acup.gunfeel.mixins.early;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.entity.projectile.ProjectileHelper;

import acup.gunfeel.ModelAABB;
import acup.gunfeel.ModelHits;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;

// port: a fireball (ProjectileHelper.forwardsRaycast, as the fireballs ask it) meets mobs by their models (MixinVanillaProjectileHits): the mobs near its path, and each mob's box
@Mixin(ProjectileHelper.class)
public abstract class MixinProjectileHelperPath {
    @WrapOperation(method = "forwardsRaycast", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/Entity;getEntityBoundingBox()Lnet/minecraft/util/math/AxisAlignedBB;"))
    private static AxisAlignedBB gunfeel$modelBox(Entity entity, Operation<AxisAlignedBB> original,
            @Local(argsOnly = true, ordinal = 0) Entity projectile) {
        if (entity == projectile || entity.world.isRemote) return original.call(entity);
        return ModelAABB.of(entity);
    }

    @WrapOperation(method = "forwardsRaycast", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;getEntitiesWithinAABBExcludingEntity(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/AxisAlignedBB;)Ljava/util/List;"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List gunfeel$candidates(World world, Entity except, AxisAlignedBB area, Operation<List> original) {
        List list = original.call(world, except, area);
        if (world.isRemote) return list;
        return ModelHits.candidates(world, except, (List<Entity>) list, area, e -> e.canBeCollidedWith());
    }
}
