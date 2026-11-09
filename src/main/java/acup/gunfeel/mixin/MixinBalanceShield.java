package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.extprop.HbmPlayerProps;
import com.hbm.handler.ArmorModHandler;
import com.hbm.items.armor.ItemModShield;

import acup.gunfeel.BalanceKit;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

// The balance's shield (BalanceKit): the most shield a player has is the balance's count of NTM's flasks and mod,
// under the balance's cap. NTM keeps its own count of flasks; the key off gives NTM's numbers back.
@Mixin(value = HbmPlayerProps.class, remap = false)
public abstract class MixinBalanceShield {
    @Shadow
    public float maxShield;

    @Inject(method = "getEffectiveMaxShield", at = @At("RETURN"), cancellable = true, require = 1)
    private void gunfeel$max(Player player, CallbackInfoReturnable<Float> cir) {
        if (!BalanceKit.shieldOn()) return;
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        float mod = !chest.isEmpty() && ArmorModHandler.modItem(chest, 6) instanceof ItemModShield shield ? shield.shield : 0F;
        cir.setReturnValue(BalanceKit.maxShield(maxShield, mod, BalanceKit.shield()));
    }
}
