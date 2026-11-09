package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.hbm.items.food.ItemFlask;

import acup.gunfeel.BalanceKit;

// The balance's shield (BalanceKit): a shield flask can no longer be drunk once the flasks alone reach the cap (NTM:
// 100 of its count), and it tops the shield up by the balance's flask (NTM: 5). NTM's own count still goes up by 5.
@Mixin(value = ItemFlask.class, remap = false)
public abstract class MixinBalanceShieldFlask {
    @ModifyConstant(method = "use", constant = @Constant(floatValue = 100F), require = 1)
    private float gunfeel$limit(float ntm) {
        return BalanceKit.shieldOn() ? BalanceKit.flaskLimit(BalanceKit.shield()) : ntm;
    }

    @ModifyConstant(method = "finishUsingItem", constant = @Constant(floatValue = 5F, ordinal = 1), require = 1)
    private float gunfeel$topUp(float ntm) {
        return BalanceKit.shieldOn() ? BalanceKit.shield().flask() : ntm;
    }
}
