package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.factory.XFactoryFlamer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import acup.gunfeel.water.WaterImpact;
import net.minecraft.util.MovingObjectPosition;

// Server: a flamethrower's flame, the Daybreaker's going off on water too (its blast is NTM's, before this): no
// lingering fire there. NTM's spawnFire kills the bullet on a block, so this does too.
// port: 26.2's MixinWaterFireFlamer (27023c4) on NTM 1.7.10's XFactoryFlamer.spawnFire
@Mixin(value = XFactoryFlamer.class, remap = false)
public abstract class MixinWaterFireFlamer {
    @WrapMethod(method = "spawnFire")
    private static void gunfeel$water(EntityBulletBaseMK4 bullet, MovingObjectPosition hit, float width, float height, int duration, int type,
            Operation<Void> original) {
        if (!WaterImpact.noFire(bullet, hit)) original.call(bullet, hit, width, height, duration, type);
        else if (hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) bullet.setDead();
    }
}
