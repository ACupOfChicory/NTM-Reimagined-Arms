package acup.gunfeel.mixin;

import java.util.List;
import java.util.function.Predicate;

import com.hbm.entity.projectile.EntityBulletBeamBase;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import acup.gunfeel.ModelHits;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// port/continuum: MixinBeamModelQuery on NTM Continuum. A hitscan beam looks for what it hits in entityHits (NTM NEXT:
// performHitscan itself), with a filter; the model candidates (a wing or an arm outside the box) join the same list,
// and the shot is seen as the shooter saw the mobs (ModelHits.beginShot)
@Mixin(value = EntityBulletBeamBase.class, remap = false)
public abstract class MixinBeamModelQuery {
    @WrapMethod(method = "performHitscan")
    private void gunfeel$view(Operation<Void> original) {
        var previous = ModelHits.beginShot(((EntityBulletBeamBase) (Object) this).thrower);
        try {
            original.call();
        } finally {
            ModelHits.endShot(previous);
        }
    }

    @Redirect(method = "entityHits", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private List<Entity> gunfeel$modelCandidates(Level level, Entity except, AABB area, Predicate<? super Entity> filter) {
        return ModelHits.candidates(level, except, level.getEntities(except, area, filter), area, filter::test);
    }
}
