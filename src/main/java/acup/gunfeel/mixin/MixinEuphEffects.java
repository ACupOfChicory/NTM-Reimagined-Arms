package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.items.armor.ArmorSuitEffects;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.EuphImmunity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

// Euphemium's window (EuphImmunity): while the wearer is open to hits (the window, the radon gas), the set's
// Regeneration and Resistance 128 are not given, or the window would give nothing.
@Mixin(value = ArmorSuitEffects.class, remap = false)
public abstract class MixinEuphEffects {
    @WrapOperation(method = "applyEffects", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;)Z"), require = 1)
    private static boolean gunfeel$effect(ServerPlayer player, MobEffectInstance effect, Operation<Boolean> original) {
        if ((effect.is(MobEffects.REGENERATION) || effect.is(MobEffects.RESISTANCE)) && effect.getAmplifier() >= 100 && EuphImmunity.vulnerable(player)) {
            EuphImmunity.dropEffects(player);
            return false;
        }
        return original.call(player, effect);
    }
}
