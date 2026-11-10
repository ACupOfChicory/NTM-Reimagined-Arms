package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.items.armor.ArmorDamageHandler;
import com.hbm.items.armor.ModArmorItem;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.EuphImmunity;
import net.minecraft.world.entity.LivingEntity;

// DNS without its invulnerability (balance.immune.dns = formula): NTM calls off every hit but a blast on a full DNS set
// (filterAttack) and takes blasts down to 0.001 (filterHurt); the balance's DNS takes its hits by its rating like the
// rest of the ladder. Only the DNS checks of these two methods: the set's sprint, jetpack and the rest stay.
@Mixin(value = ArmorDamageHandler.class, remap = false)
public abstract class MixinBalanceDns {
    @WrapOperation(method = {"filterAttack", "filterHurt"}, at = @At(value = "INVOKE", target = "Lcom/hbm/items/armor/ArmorSuitEffects;hasFullSet(Lnet/minecraft/world/entity/LivingEntity;Lcom/hbm/items/armor/ModArmorItem$Suit;)Z"), require = 2)
    private static boolean gunfeel$dns(LivingEntity entity, ModArmorItem.Suit suit, Operation<Boolean> original) {
        if (suit == ModArmorItem.Suit.DNS && EuphImmunity.dnsFormula()) return false;
        return original.call(entity, suit);
    }
}
