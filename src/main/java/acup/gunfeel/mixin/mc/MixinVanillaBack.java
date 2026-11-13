package acup.gunfeel.mixin.mc;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import acup.gunfeel.BalanceKit;
import acup.gunfeel.gas.GasServer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

// The wearer's vanilla armour as a hit meets it. balance.vanilla0 takes the combat sets' vanilla armour off for the
// formula's hits (NTM's shots and blasts); a hit the formula does not take (melee, a mob's blow, an arrow, fire) meets
// that armour as before the balance, within vanilla's bounds (30 armour, 20 toughness). The GL-54's acid then leaves
// (1 - K) of the armour's points while its corrosion K is on the wearer (NTM's own threshold and percentage are cut in
// MixinGasCorrosion). One wrapper for both: the acid takes the armour given back too.
@Mixin(LivingEntity.class)
public abstract class MixinVanillaBack {
    @WrapOperation(method = "getDamageAfterArmorAbsorb", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/damagesource/CombatRules;getDamageAfterAbsorb(Lnet/minecraft/world/entity/LivingEntity;FLnet/minecraft/world/damagesource/DamageSource;FF)F"))
    private float gunfeel$armor(LivingEntity entity, float damage, DamageSource source, float armor, float toughness, Operation<Float> original) {
        // a hit of the final scheme on a mob: the armour of the pieces worn alone, as a player in them has
        float[] own = acup.gunfeel.BalanceFinal.mobOwnArmor(entity, source);
        if (own[0] > 0F) armor = Math.max(0F, armor - own[0]);
        if (own[1] > 0F) toughness = Math.max(0F, toughness - own[1]);
        float[] back = BalanceKit.vanillaBack(entity, source);
        if (back[0] > 0F) armor = Math.min(30F, armor + back[0]);
        if (back[1] > 0F) toughness = Math.min(20F, toughness + back[1]);
        float k = GasServer.corrosion(entity);
        if (k > 0F) armor = acup.gunfeel.gas.GasModel.corrode(armor, k);
        return original.call(entity, damage, source, armor, toughness);
    }
}
