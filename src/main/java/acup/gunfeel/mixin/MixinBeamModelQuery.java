package acup.gunfeel.mixin;

import java.util.List;
import acup.gunfeel.ModelHits;
import com.hbm.entity.projectile.EntityBulletBeamBase;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = EntityBulletBeamBase.class, remap = false)
public abstract class MixinBeamModelQuery {
    @Redirect(method = "performHitscan", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<Entity> gunfeel$modelCandidates(Level level, Entity except, AABB area) {
        return ModelHits.candidates(level, except, level.getEntities(except, area), area, e -> true);
    }
}
