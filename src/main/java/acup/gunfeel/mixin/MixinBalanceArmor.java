package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.util.EntityDamageUtil;

import acup.gunfeel.BalanceArmor;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

// The balance's armour (BalanceArmor): where NTM takes its threshold and resistance off a hit, the combat sets'
// formula C replaces NTM's result for bullets, beams and blasts. The hit's damage is taken at the head: NTM takes its
// threshold off the same local before it returns. NTM's early check (allowsAttack, from LivingIncomingDamageEvent) calls
// a hit off when its threshold or resistance would leave nothing: for the balance's hits that check sees no threshold
// and no resistance (its armour's own check before it, filterAttack: Trenchmaster's block and the rest, stays).
@Mixin(value = EntityDamageUtil.class, remap = false)
public abstract class MixinBalanceArmor {
    @Unique private static float gunfeel$amount;

    @org.spongepowered.asm.mixin.injection.Redirect(method = "allowsAttack", at = @At(value = "INVOKE", target = "Lcom/hbm/util/EntityDamageUtil;getDTDR(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;FFF)[F"), require = 1)
    private static float[] gunfeel$allows(LivingEntity entity, DamageSource source, float amount, float pierceDT, float pierce) {
        if (BalanceArmor.covers(entity, source)) return new float[] {0F, 0F};
        return EntityDamageUtil.getDTDR(entity, source, amount, pierceDT, pierce);
    }

    @Inject(method = "calculateDamage", at = @At("HEAD"), require = 1)
    private static void gunfeel$amount(LivingEntity entity, DamageSource source, float amount, float pierceDT, float pierce, CallbackInfoReturnable<Float> cir) {
        gunfeel$amount = amount;
    }

    @Inject(method = "calculateDamage", at = @At("RETURN"), cancellable = true, require = 1)
    private static void gunfeel$balance(LivingEntity entity, DamageSource source, float amount, float pierceDT, float pierce, CallbackInfoReturnable<Float> cir) {
        float ntm = cir.getReturnValueF();
        float out = BalanceArmor.apply(entity, source, gunfeel$amount, pierceDT, pierce, ntm);
        if (out != ntm) cir.setReturnValue(out);
    }
}
