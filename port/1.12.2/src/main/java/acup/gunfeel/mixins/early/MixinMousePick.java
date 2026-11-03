package acup.gunfeel.mixins.early;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.ModelAABB;
import acup.gunfeel.ModelHits;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;

// port: 26.2's MixinProjectileModelQuery for the melee pick (GameRenderer.pick asks ProjectileUtil.getEntityHitResult):
// the mob under the crosshair, the one a punch or a sword strikes, is found by its model. 1.12.2's
// EntityRenderer.getMouseOver tests entity.getEntityBoundingBox().grow(border) itself; the mob's box there is its model's
// (ModelAABB: calculateIntercept and contains go by the pose this client drew), and mobs whose model sticks out of
// the vanilla box are candidates too.
@Mixin(EntityRenderer.class)
public abstract class MixinMousePick {
    @WrapOperation(method = "getMouseOver", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/Entity;getEntityBoundingBox()Lnet/minecraft/util/math/AxisAlignedBB;"))
    private AxisAlignedBB gunfeel$pickBox(Entity entity, Operation<AxisAlignedBB> original) {
        // the camera's own box (the search area) stays as it is
        if (entity == net.minecraft.client.Minecraft.getMinecraft().getRenderViewEntity()) return original.call(entity);
        return ModelAABB.of(entity);
    }

    @WrapOperation(method = "getMouseOver", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/WorldClient;getEntitiesInAABBexcluding(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/AxisAlignedBB;Lcom/google/common/base/Predicate;)Ljava/util/List;"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List gunfeel$pickCandidates(net.minecraft.client.multiplayer.WorldClient world, Entity except, AxisAlignedBB area,
            com.google.common.base.Predicate<? super Entity> filter, Operation<List> original) {
        List list = original.call(world, except, area, filter);
        return ModelHits.candidates((World) world, except, (List<Entity>) list, area, e -> filter == null || filter.apply(e));
    }
}
