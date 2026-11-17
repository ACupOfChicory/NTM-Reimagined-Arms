package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.inventory.container.MenuItemBox;
import com.hbm.inventory.container.ModMenus;

import acup.gunfeel.ammo.AmmoBagOurs;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

// NTM's ammo bags (the infinite one too) take only NTM's own round families (ModItems.AMMO_STANDARD, AMMO_SECRET); the
// mod's rounds (the tag ntmgunfeel:ammo_bag) go in as well, plain stacks only as NTM's. A gun reloads from the bag by
// its configs' matchesAmmo, which knows the mod's rounds already.
@Mixin(value = MenuItemBox.class, remap = false)
public abstract class MixinAmmoBagOurs {
    @Inject(method = "accepts", at = @At("HEAD"), cancellable = true)
    private void gunfeel$ours(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (((AbstractContainerMenu) (Object) this).getType() == ModMenus.AMMO_BAG.get() && AmmoBagOurs.takes(stack)) cir.setReturnValue(true);
    }
}
