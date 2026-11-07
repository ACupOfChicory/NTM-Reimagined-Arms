package acup.gunfeel.mixins;

import java.util.HashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.explosion.vanillant.standard.EntityProcessorStandard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import acup.gunfeel.water.WaterBlast;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

// port only: an old bullet's blast with its centre in water (WaterBlast.beginStandard: only inside its impact), as
// MixinWaterBlastProcessor does with EntityProcessorCross. NTM 1.7.10's EntityProcessorStandard.process is the same
// method with the same locals (size *= 2, distanceScaled, knockback, calculateDamage(DDDF)F; its jar keeps the local
// variable table); its damage grows with the size, so a mob in the water takes it by the blast's own size
// (WaterBlast.damageStandard)
@Mixin(value = EntityProcessorStandard.class, remap = false)
public abstract class MixinWaterBlastStandard {
    @WrapMethod(method = "process")
    private HashMap<EntityPlayer, Vec3> gunfeel$water(ExplosionVNT explosion, World world, double x, double y, double z, float size,
            Operation<HashMap<EntityPlayer, Vec3>> original) {
        WaterBlast.beginStandard(explosion, world, x, y, z);
        try {
            return original.call(explosion, world, x, y, z, size);
        } finally {
            WaterBlast.end();
        }
    }

    @ModifyVariable(method = "process", at = @At(value = "STORE", ordinal = 0), argsOnly = true, require = 1)
    private float gunfeel$range(float size) {
        return WaterBlast.size(size);
    }

    @ModifyVariable(method = "process", at = @At("STORE"), name = "distanceScaled", require = 1)
    private double gunfeel$distance(double scaled, @Local(name = "entity") Entity entity) {
        return WaterBlast.distance(scaled, entity);
    }

    @ModifyVariable(method = "process", at = @At("STORE"), name = "knockback", require = 1)
    private double gunfeel$push(double knockback, @Local(name = "entity") Entity entity) {
        return WaterBlast.knockback(knockback, entity);
    }

    @WrapOperation(method = "process", at = @At(value = "INVOKE",
            target = "Lcom/hbm/explosion/vanillant/standard/EntityProcessorStandard;calculateDamage(DDDF)F"), require = 1)
    private float gunfeel$damage(EntityProcessorStandard self, double scaled, double density, double knockback, float size,
            Operation<Float> original, @Local(name = "entity") Entity entity) {
        return WaterBlast.damageStandard(size, knockback, entity, (s, k) -> original.call(self, scaled, density, k, s));
    }
}
