package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.hbm.util.EntityDamageUtil;

import acup.gunfeel.BalanceArmor;

// NTM takes an electric hit on a powered chestplate five times (modifyAcceptedDamage): with the balance's armour on,
// balanceElectricPowered times instead (BalanceArmor.electricPowered). The formula takes that damage: five times made
// the Tesla Cannon's click past every set but DNS's rating.
@Mixin(value = EntityDamageUtil.class, remap = false)
public abstract class MixinBalanceElectric {
    @ModifyConstant(method = "modifyAcceptedDamage", constant = @Constant(floatValue = 5.0F), require = 1)
    private static float gunfeel$electricPowered(float ntm) {
        return BalanceArmor.electricPowered(ntm);
    }
}
