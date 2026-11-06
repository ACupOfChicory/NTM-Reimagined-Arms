package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.factory.XFactoryRocket;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import acup.gunfeel.water.WaterImpact;
import net.minecraft.util.MovingObjectPosition;

// Server: an incendiary or phosphorus rocket going off in water: its blast, no fire; as NTM's spawnFire, not on any
// mob it hits in its first 3 ticks.
// port: 26.2's MixinWaterFire (7e2382c) takes both factories with the grenade's guard
@Mixin(value = XFactoryRocket.class, remap = false)
public abstract class MixinWaterFireRocket {
    @WrapMethod(method = "spawnFire")
    private static void gunfeel$water(EntityBulletBaseMK4 bullet, MovingObjectPosition hit, boolean phosphorus, int duration,
            Operation<Void> original) {
        if (WaterImpact.noFire(bullet, hit)) WaterImpact.fireBlast(bullet, hit, true);
        else original.call(bullet, hit, phosphorus, duration);
    }
}
