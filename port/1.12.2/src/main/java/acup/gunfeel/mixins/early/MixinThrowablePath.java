package acup.gunfeel.mixins.early;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.projectile.EntityThrowable;

import acup.gunfeel.ModelAABB;
import acup.gunfeel.ModelHits;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;

// port: a thrown item (EntityThrowable.onUpdate) meets mobs by their models (MixinVanillaProjectileHits): the mobs near its path, and each mob's box
@Mixin(EntityThrowable.class)
public abstract class MixinThrowablePath {
    @WrapOperation(method = "onUpdate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/Entity;getEntityBoundingBox()Lnet/minecraft/util/math/AxisAlignedBB;"))
    private AxisAlignedBB gunfeel$modelBox(Entity entity, Operation<AxisAlignedBB> original) {
        if (entity == (Object) this || entity.world.isRemote) return original.call(entity);
        return ModelAABB.of(entity);
    }

    @WrapOperation(method = "onUpdate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;getEntitiesWithinAABBExcludingEntity(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/AxisAlignedBB;)Ljava/util/List;"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List gunfeel$candidates(World world, Entity except, AxisAlignedBB area, Operation<List> original) {
        List list = original.call(world, except, area);
        if (world.isRemote) return list;
        return ModelHits.candidates(world, except, (List<Entity>) list, area, e -> e.canBeCollidedWith());
    }
}
