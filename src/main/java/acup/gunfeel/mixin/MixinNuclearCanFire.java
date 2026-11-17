package acup.gunfeel.mixin;

import java.util.function.BiFunction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import acup.gunfeel.ammo.NuclearBmg;
import net.minecraft.world.item.ItemStack;

// The nuclear .50 (NuclearBmg): a worn out gun does not fire it (owner 2026-11-17), it dry-fires as an empty one; its
// other rounds fire as NTM has it. Every shot of NTM's asks the receiver's "can fire" first (a click, a held burst, a
// round of a cycle), so the check is put after that one, whatever it is
@Mixin(value = Receiver.class, remap = false)
public abstract class MixinNuclearCanFire {
    @ModifyReturnValue(method = "getCanFire", at = @At("RETURN"), require = 1)
    private BiFunction<ItemStack, ItemGunBaseNT.LambdaContext, Boolean> gunfeel$nuclearWornOut(
            BiFunction<ItemStack, ItemGunBaseNT.LambdaContext, Boolean> can) {
        if (can == null) return null;
        Receiver rec = (Receiver) (Object) this;
        return (stack, ctx) -> can.apply(stack, ctx) && !NuclearBmg.blocked(stack, ctx, rec);
    }
}
