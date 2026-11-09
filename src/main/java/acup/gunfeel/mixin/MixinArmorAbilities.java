package acup.gunfeel.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.items.armor.ArmorFullSetBonus;

import acup.gunfeel.ArmorAbilities;

// The balance's armour abilities (ArmorAbilities): a full set's potion effects and dashes, cut to its tier. NTM asks
// the set's bonus for them every tick (ArmorSuitEffects.applyEffects, ArmorDash on both sides) and for the tooltip.
@Mixin(value = ArmorFullSetBonus.class, remap = false)
public abstract class MixinArmorAbilities {
    @Inject(method = "effects", at = @At("RETURN"), cancellable = true, require = 1)
    private void gunfeel$effects(CallbackInfoReturnable<List<ArmorFullSetBonus.EffectBonus>> cir) {
        List<ArmorFullSetBonus.EffectBonus> ntm = cir.getReturnValue();
        List<ArmorFullSetBonus.EffectBonus> out = ArmorAbilities.effects((ArmorFullSetBonus) (Object) this, ntm);
        if (out != ntm) cir.setReturnValue(out);
    }

    @Inject(method = "dashCount", at = @At("RETURN"), cancellable = true, require = 1)
    private void gunfeel$dashes(CallbackInfoReturnable<Integer> cir) {
        int ntm = cir.getReturnValueI();
        int out = ArmorAbilities.dashes((ArmorFullSetBonus) (Object) this, ntm);
        if (out != ntm) cir.setReturnValue(out);
    }
}
