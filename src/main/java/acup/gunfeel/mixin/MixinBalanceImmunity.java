package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.util.EntityDamageUtil;

import acup.gunfeel.EuphImmunity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

// The pool of DNS and FAU (EuphImmunity.Kit): NTM's early check (allowsAttack, LivingIncomingDamageEvent) calls a
// bullet, beam or blast on the full set off while the pool holds; beams and blasts come with ignoreEarlyCancellation
// and go on to actuallyHurt, so NTM's accepted damage takes them to 0 by the same decision (one drain a hit). The
// accepted damage's head is before NTM's shield: a called-off hit does not touch the shield either.
@Mixin(value = EntityDamageUtil.class, remap = false)
public abstract class MixinBalanceImmunity {
    @Inject(method = "allowsAttack", at = @At("HEAD"), cancellable = true, require = 1)
    private static void gunfeel$early(LivingEntity entity, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof Player player) || entity.level().isClientSide() || !(amount > 0F)) return;
        EuphImmunity.Kit kit = EuphImmunity.kitOf(player);
        if (kit != null && EuphImmunity.early(kit, player, source, amount)) cir.setReturnValue(false);
    }

    @Inject(method = "modifyAcceptedDamage", at = @At("HEAD"), cancellable = true, require = 1)
    private static void gunfeel$accepted(LivingEntity entity, DamageSource source, float amount, CallbackInfoReturnable<Float> cir) {
        if (!(entity instanceof Player player) || entity.level().isClientSide() || !(amount > 0F)) return;
        EuphImmunity.Kit kit = EuphImmunity.kitOf(player);
        if (kit != null && EuphImmunity.accepted(kit, player, source, amount)) cir.setReturnValue(0F);
    }
}
