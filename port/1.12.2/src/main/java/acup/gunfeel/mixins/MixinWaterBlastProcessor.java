package acup.gunfeel.mixins;

import java.util.HashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.explosion.vanillant.standard.EntityProcessorCross;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import acup.gunfeel.water.WaterBlast;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

// 26.2's MixinWaterBlastProcessor. Server: a blast with its centre in water (WaterBlast) reaches mobs in water out to
// waterBlastRadius × its range; those out of the water take it by its own range, half the damage and half the push.
// NTM's bullets and grenades blow up through EntityProcessorCross (CrossSmooth): its locals by name, NTM CE's jar
// keeps them.
@Mixin(value = EntityProcessorCross.class, remap = false)
public abstract class MixinWaterBlastProcessor {
    @WrapMethod(method = "process")
    private HashMap<EntityPlayer, Vec3d> gunfeel$water(ExplosionVNT explosion, World world, double x, double y, double z, float size,
            Operation<HashMap<EntityPlayer, Vec3d>> original) {
        WaterBlast.begin(explosion, world, x, y, z);
        try {
            return original.call(explosion, world, x, y, z, size);
        } finally {
            WaterBlast.end();
        }
    }

    // size *= 2: the range
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
            target = "Lcom/hbm/explosion/vanillant/standard/EntityProcessorCross;calculateDamage(DDDF)F"), require = 1)
    private float gunfeel$damage(EntityProcessorCross self, double scaled, double density, double knockback, float size,
            Operation<Float> original, @Local(name = "entity") Entity entity) {
        return WaterBlast.damage(size, knockback, entity, (s, k) -> original.call(self, scaled, density, k, s));
    }
}
