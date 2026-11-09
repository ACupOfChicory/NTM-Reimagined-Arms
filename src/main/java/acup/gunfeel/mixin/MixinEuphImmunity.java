package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.items.armor.ArmorDamageHandler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import acup.gunfeel.EuphImmunity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

// Euphemium's invulnerability with a pool (EuphImmunity): NTM calls off every hit on a full euphemium set
// (filterAttack, its first full-set check); the balance lets the hit through in the window and in the tomb's radon.
@Mixin(value = ArmorDamageHandler.class, remap = false)
public abstract class MixinEuphImmunity {
    @WrapOperation(method = "filterAttack", at = @At(value = "INVOKE", target = "Lcom/hbm/items/armor/ArmorDamageHandler;wearsAll(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/Item;Lnet/minecraft/world/item/Item;Lnet/minecraft/world/item/Item;Lnet/minecraft/world/item/Item;)Z"), require = 1)
    private static boolean gunfeel$euphemium(Player player, Item helmet, Item chest, Item legs, Item boots, Operation<Boolean> original,
            @Local(argsOnly = true) DamageSource source, @Local(argsOnly = true) float amount) {
        if (!original.call(player, helmet, chest, legs, boots)) return false;
        return EuphImmunity.absorbs(player, source, amount);
    }
}
