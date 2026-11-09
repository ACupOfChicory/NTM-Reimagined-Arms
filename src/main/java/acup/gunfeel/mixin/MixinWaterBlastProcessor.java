package acup.gunfeel.mixin;

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

import acup.gunfeel.BlastParts;
import acup.gunfeel.ExplosiveDamage;
import acup.gunfeel.HeadDamage;
import acup.gunfeel.water.WaterBlast;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

// Server: a blast with its centre in water (WaterBlast) reaches mobs in water out to waterBlastRadius × its range;
// those out of the water take it by its own range, half the damage and half the push. NTM's bullets and grenades
// blow up through EntityProcessorCross (CrossSmooth): its locals by name, NTM's jar keeps them.
@Mixin(value = EntityProcessorCross.class, remap = false)
public abstract class MixinWaterBlastProcessor {
    @WrapMethod(method = "process")
    private HashMap<Player, Vec3> gunfeel$water(ExplosionVNT explosion, Level world, double x, double y, double z, float size,
            Operation<HashMap<Player, Vec3>> original) {
        WaterBlast.begin(explosion, world, x, y, z);
        // the blast by the body's parts (BlastParts): a CrossSmooth of another damage class than the explosive not
        try {
            BlastParts.begin(!((Object) this instanceof ExplosiveDamage.Processor p) || p.gunfeel$explosive());
            return original.call(explosion, world, x, y, z, size);
        } finally {
            BlastParts.end();
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

    // the damage: the whole mob's (in water as WaterBlast counts it), or its parts' (BlastParts), each with its own
    // distance, visibility and push, the water's changes on them as on the whole mob's
    @WrapOperation(method = "process", at = @At(value = "INVOKE",
            target = "Lcom/hbm/explosion/vanillant/standard/EntityProcessorCross;calculateDamage(DDDF)F"), require = 1)
    private float gunfeel$damage(EntityProcessorCross self, double scaled, double density, double knockback, float size,
            Operation<Float> original, @Local(name = "entity") Entity entity, @Local(argsOnly = true) Level world,
            @Local(argsOnly = true, ordinal = 0) double x, @Local(argsOnly = true, ordinal = 1) double y,
            @Local(argsOnly = true, ordinal = 2) double z, @Local(name = "nodes") Vec3[] nodes) {
        float whole = WaterBlast.damage(size, knockback, entity, (s, k) -> original.call(self, scaled, density, k, s));
        return BlastParts.damage(entity, world, new Vec3(x, y, z), nodes, size, whole, density,
                (ds, seen, push) -> WaterBlast.damage(size, push, entity, (s, k) -> original.call(self, ds, seen, k, s)),
                ds -> WaterBlast.distance(ds, entity), k -> WaterBlast.knockback(k, entity));
    }

    // the hurt: the parts' mean multiplier after the armour (HeadDamage)
    @WrapOperation(method = "process", at = @At(value = "INVOKE",
            target = "Lcom/hbm/explosion/vanillant/standard/EntityProcessorCross;attackEntity(Lnet/minecraft/world/entity/Entity;Lcom/hbm/explosion/vanillant/ExplosionVNT;F)V"),
            require = 1)
    private void gunfeel$hurt(EntityProcessorCross self, Entity entity, ExplosionVNT explosion, float amount, Operation<Void> original) {
        float m = BlastParts.multiplier(entity);
        if (m == 1F || !(entity instanceof net.minecraft.world.entity.LivingEntity living)) {
            original.call(self, entity, explosion, amount);
            return;
        }
        HeadDamage.attack(living, amount, m, d -> {
            original.call(self, entity, explosion, d);
            return true;
        });
    }
}
