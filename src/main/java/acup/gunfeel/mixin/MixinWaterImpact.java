package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import acup.gunfeel.water.WaterImpact;
import net.minecraft.world.phys.HitResult;

// Server: NTM's impact of a bullet, marked for the blasts it sets off (WaterImpact.impact, WaterBlast). What water changes
// in it (b75_inc's small blast, no setting alight) is in its onImpact lambda: MixinExplosiveBullet, WaterImpact.lambda
@Mixin(value = EntityBulletBaseMK4.class, remap = false)
public abstract class MixinWaterImpact {
    @WrapMethod(method = "onImpact")
    private void gunfeel$water(HitResult hit, Operation<Void> original) {
        WaterImpact.impact((EntityBulletBaseMK4) (Object) this, hit, () -> original.call(hit));
    }
}
