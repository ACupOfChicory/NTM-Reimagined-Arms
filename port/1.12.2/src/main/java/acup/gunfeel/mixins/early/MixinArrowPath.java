package acup.gunfeel.mixins.early;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.projectile.EntityArrow;

import acup.gunfeel.ModelAABB;
import acup.gunfeel.ModelHits;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;

// port: an arrow (EntityArrow.findEntityOnPath) meets mobs by their models (MixinVanillaProjectileHits): the mobs near its path, and each mob's box
@Mixin(EntityArrow.class)
public abstract class MixinArrowPath {
    @WrapOperation(method = "findEntityOnPath", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/Entity;getEntityBoundingBox()Lnet/minecraft/util/math/AxisAlignedBB;"))
    private AxisAlignedBB gunfeel$modelBox(Entity entity, Operation<AxisAlignedBB> original) {
        if (entity == (Object) this || entity.world.isRemote) return original.call(entity);
        return ModelAABB.of(entity);
    }

    @WrapOperation(method = "findEntityOnPath", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;getEntitiesInAABBexcluding(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/AxisAlignedBB;Lcom/google/common/base/Predicate;)Ljava/util/List;"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List gunfeel$candidates(World world, Entity except, AxisAlignedBB area, com.google.common.base.Predicate<? super Entity> filter, Operation<List> original) {
        List list = original.call(world, except, area, filter);
        if (world.isRemote) return list;
        return ModelHits.candidates(world, except, (List<Entity>) list, area, e -> filter == null || filter.apply(e));
    }
}
