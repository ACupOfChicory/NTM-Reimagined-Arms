package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import acup.gunfeel.gas.GasServer;
import net.minecraft.world.entity.LivingEntity;

// The GL-54's acid: the points of the wearer's vanilla armour count for (1 - K) of what they are while the acid's
// corrosion K is on them (NTM's own threshold and percentage are cut in MixinGasCorrosion).
@Mixin(LivingEntity.class)
public abstract class MixinGasArmor {
    @ModifyArg(method = "getDamageAfterArmorAbsorb", index = 3, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/damagesource/CombatRules;getDamageAfterAbsorb(Lnet/minecraft/world/entity/LivingEntity;FLnet/minecraft/world/damagesource/DamageSource;FF)F"))
    private float gunfeel$corrodedArmor(float armor) {
        float k = GasServer.corrosion((LivingEntity) (Object) this);
        return k > 0F ? acup.gunfeel.gas.GasModel.corrode(armor, k) : armor;
    }
}
