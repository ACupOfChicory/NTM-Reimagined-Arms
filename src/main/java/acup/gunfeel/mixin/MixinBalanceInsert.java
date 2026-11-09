package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.items.armor.ItemModInsert;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import acup.gunfeel.BalanceKit;

// The balance's inserts (BalanceKit): an insert takes at most balance.inserts.bullets off a bullet, in the hit and in
// its tooltips; its damage and blast shares are NTM's.
@Mixin(value = ItemModInsert.class, remap = false)
public abstract class MixinBalanceInsert {
    @ModifyExpressionValue(method = {"modDamage", "addSpecificTooltip", "addDescription"},
            at = @At(value = "FIELD", target = "Lcom/hbm/items/armor/ItemModInsert;projectileMod:F", opcode = org.objectweb.asm.Opcodes.GETFIELD), require = 3)
    private float gunfeel$bullets(float ntm) {
        return BalanceKit.insertBulletsNow(ntm);
    }
}
