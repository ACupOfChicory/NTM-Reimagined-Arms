package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.items.food.MedicalSyringeItem;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.BalanceKit;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

// The balance's medicine (BalanceKit): Med-X gives Resistance I for 60 s (NTM: III for 4 min), the super stimpak heals
// its 25 HP over 12 s (NTM: at once). Used on oneself (use) and injected into another (hurtEnemy).
@Mixin(value = MedicalSyringeItem.class, remap = false)
public abstract class MixinBalanceSyringe {
    @Shadow
    @Final
    private MedicalSyringeItem.Type type;

    @WrapOperation(method = "use", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;heal(F)V"), require = 2)
    private void gunfeel$healSelf(Player player, float amount, Operation<Void> original) {
        if (type == MedicalSyringeItem.Type.SUPER_STIMPAK && BalanceKit.superStimpakOn()) BalanceKit.healOverTime(player, amount);
        else original.call(player, amount);
    }

    @WrapOperation(method = "hurtEnemy", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;heal(F)V"), require = 2)
    private void gunfeel$healOther(LivingEntity target, float amount, Operation<Void> original) {
        if (type == MedicalSyringeItem.Type.SUPER_STIMPAK && BalanceKit.superStimpakOn()) BalanceKit.healOverTime(target, amount);
        else original.call(target, amount);
    }

    @WrapOperation(method = "use", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;)Z"), require = 1)
    private boolean gunfeel$effectSelf(Player player, MobEffectInstance effect, Operation<Boolean> original) {
        return original.call(player, medx(effect));
    }

    @WrapOperation(method = "hurtEnemy", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;)Z"), require = 1)
    private boolean gunfeel$effectOther(LivingEntity target, MobEffectInstance effect, Operation<Boolean> original) {
        return original.call(target, medx(effect));
    }

    private MobEffectInstance medx(MobEffectInstance effect) {
        if (type != MedicalSyringeItem.Type.MEDX || !effect.is(MobEffects.RESISTANCE) || !BalanceKit.medxOn()) return effect;
        return BalanceKit.medx();
    }
}
