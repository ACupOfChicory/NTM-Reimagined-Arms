package acup.gunfeel.mixin;

import java.util.HashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.explosion.vanillant.standard.EntityProcessorStandard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import acup.gunfeel.BlastParts;
import acup.gunfeel.HeadDamage;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

// Server: NTM's EntityProcessorStandard (C4 and dynamite blocks, plastic explosive, the oil well's and the boilers'
// blowups, the torpedo, the gold and volatile creepers) by the body's parts, as MixinWaterBlastProcessor does Cross:
// each part's distance and rays from its own points to the blast's centre, the parts' mean multiplier after the armour
// (BlastParts). Its locals by name, NTM's jar keeps them.
@Mixin(value = EntityProcessorStandard.class, remap = false)
public abstract class MixinBlastPartsStandard {
    @WrapMethod(method = "process")
    private HashMap<Player, Vec3> gunfeel$parts(ExplosionVNT explosion, Level world, double x, double y, double z, float size,
            Operation<HashMap<Player, Vec3>> original) {
        BlastParts.begin(true);
        try {
            return original.call(explosion, world, x, y, z, size);
        } finally {
            BlastParts.end();
        }
    }

    @WrapOperation(method = "process", at = @At(value = "INVOKE",
            target = "Lcom/hbm/explosion/vanillant/standard/EntityProcessorStandard;calculateDamage(DDDF)F"), require = 1)
    private float gunfeel$damage(EntityProcessorStandard self, double scaled, double density, double knockback, float size,
            Operation<Float> original, @Local(name = "entity") Entity entity, @Local(argsOnly = true) Level world,
            @Local(name = "center") Vec3 center) {
        float whole = original.call(self, scaled, density, knockback, size);
        return BlastParts.damage(entity, world, center, new Vec3[] { center }, size, whole, density,
                (ds, seen, push) -> original.call(self, ds, seen, push, size), ds -> ds, k -> k);
    }

    @WrapOperation(method = "process", at = @At(value = "INVOKE",
            target = "Lcom/hbm/explosion/vanillant/standard/EntityProcessorStandard;attackEntity(Lnet/minecraft/world/entity/Entity;Lcom/hbm/explosion/vanillant/ExplosionVNT;F)V"),
            require = 1)
    private void gunfeel$hurt(EntityProcessorStandard self, Entity entity, ExplosionVNT explosion, float amount, Operation<Void> original) {
        float m = BlastParts.multiplier(entity);
        if (m == 1F || !(entity instanceof LivingEntity living)) {
            original.call(self, entity, explosion, amount);
            return;
        }
        HeadDamage.attack(living, amount, m, d -> {
            original.call(self, entity, explosion, d);
            return true;
        });
    }
}
