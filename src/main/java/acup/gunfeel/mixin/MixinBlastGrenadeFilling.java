package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.hbm.entity.grenade.EntityGrenadeUniversal;
import com.hbm.items.weapon.grenade.ItemGrenadeFilling;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

// Server: a hand grenade's blast is dealt with its filling known (BalanceFinal.grenade: blasts on the scheme's ladder)
@Mixin(value = ItemGrenadeFilling.class, remap = false)
public abstract class MixinBlastGrenadeFilling {
    @WrapMethod(method = "explode")
    private static void gunfeel$filling(EntityGrenadeUniversal grenade, Operation<Void> original) {
        acup.gunfeel.BalanceFinal.grenade(grenade, () -> original.call(grenade));
    }
}
