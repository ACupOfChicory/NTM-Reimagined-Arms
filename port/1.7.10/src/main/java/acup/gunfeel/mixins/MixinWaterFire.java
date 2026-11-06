package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.factory.XFactory40mm;
import com.hbm.items.weapon.sedna.factory.XFactoryRocket;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import acup.gunfeel.water.WaterImpact;
import net.minecraft.util.MovingObjectPosition;

// Server: an incendiary or phosphorus grenade or rocket going off in water: its blast, no fire.
// port: 26.2's MixinWaterFire (7e2382c); NTM 1.7.10 has the same static spawnFire in both factories
@Mixin(value = { XFactory40mm.class, XFactoryRocket.class }, remap = false)
public abstract class MixinWaterFire {
    @WrapMethod(method = "spawnFire")
    private static void gunfeel$water(EntityBulletBaseMK4 bullet, MovingObjectPosition hit, boolean phosphorus, int duration,
            Operation<Void> original) {
        if (WaterImpact.noFire(bullet, hit)) WaterImpact.fireBlast(bullet, hit);
        else original.call(bullet, hit, phosphorus, duration);
    }
}
