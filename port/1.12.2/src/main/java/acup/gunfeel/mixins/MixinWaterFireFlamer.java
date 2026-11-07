package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.factory.XFactoryFlamer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import acup.gunfeel.water.WaterImpact;
import net.minecraft.util.math.RayTraceResult;

// 26.2's MixinWaterFireFlamer (X560). Server: the Daybreaker's flame going off on water (its blast is NTM's, before
// this): no lingering fire there. NTM's spawnFire removes the bullet on a block, so this does too
@Mixin(value = XFactoryFlamer.class, remap = false)
public abstract class MixinWaterFireFlamer {
    @WrapMethod(method = "spawnFire")
    private static void gunfeel$water(EntityBulletBaseMK4 bullet, RayTraceResult hit, float width, float height, int duration, int type,
            Operation<Void> original) {
        if (!WaterImpact.noFire(bullet, hit)) original.call(bullet, hit, width, height, duration, type);
        else if (hit.typeOfHit == RayTraceResult.Type.BLOCK) bullet.setDead();
    }
}
