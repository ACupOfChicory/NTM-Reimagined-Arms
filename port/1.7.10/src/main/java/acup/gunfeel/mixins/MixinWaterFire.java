package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.factory.XFactory40mm;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import acup.gunfeel.water.WaterImpact;
import net.minecraft.util.MovingObjectPosition;

// Server: an incendiary or phosphorus grenade going off in water: its blast, no fire.
// port: 26.2's MixinWaterFire (7e2382c) for XFactory40mm; the rocket's is MixinWaterFireRocket (its guard spares any mob)
@Mixin(value = XFactory40mm.class, remap = false)
public abstract class MixinWaterFire {
    @WrapMethod(method = "spawnFire")
    private static void gunfeel$water(EntityBulletBaseMK4 bullet, MovingObjectPosition hit, boolean phosphorus, int duration,
            Operation<Void> original) {
        if (WaterImpact.noFire(bullet, hit)) WaterImpact.fireBlast(bullet, hit, false);
        else original.call(bullet, hit, phosphorus, duration);
    }
}
