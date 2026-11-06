package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.factory.XFactory40mm;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import acup.gunfeel.water.WaterImpact;
import net.minecraft.world.phys.HitResult;

// Server: an incendiary or phosphorus grenade going off in water: its blast, no fire; as NTM's spawnFire, not on its
// shooter in its first 3 ticks. The rocket's: MixinWaterFireRocket
@Mixin(value = XFactory40mm.class, remap = false)
public abstract class MixinWaterFire {
    @WrapMethod(method = "spawnFire")
    private static void gunfeel$water(EntityBulletBaseMK4 bullet, HitResult hit, boolean phosphorus, int duration, Operation<Void> original) {
        if (WaterImpact.noFire(bullet, hit)) WaterImpact.fireBlast(bullet, hit, false);
        else original.call(bullet, hit, phosphorus, duration);
    }
}
