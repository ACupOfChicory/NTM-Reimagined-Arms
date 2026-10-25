package acup.gunfeel.mixin.mc;

import acup.gunfeel.ModelAABB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class MixinEntityModelBox {
    @Unique private AABB gunfeel$rawBox;
    @Unique private ModelAABB gunfeel$modelBox;

    @Inject(method = "getBoundingBox", at = @At("RETURN"), cancellable = true)
    private void gunfeel$rayBox(CallbackInfoReturnable<AABB> ci) {
        if (!((Object) this instanceof LivingEntity living)) return;
        AABB raw = ci.getReturnValue();
        if (raw instanceof ModelAABB) return;
        if (raw != gunfeel$rawBox) {
            gunfeel$rawBox = raw;
            gunfeel$modelBox = new ModelAABB(living, raw);
        }
        ci.setReturnValue(gunfeel$modelBox);
    }
}
