package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.blockentity.machine.BlockEntityMachineAmmoPress;

import acup.gunfeel.gl54.GrenadeItem;
import acup.gunfeel.gl54.Gl54Items;
import acup.gunfeel.gl54.PressLoading;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

// The GL-54's magazines in NTM's ammo press (PressLoading): with no recipe chosen the press loads our grenades, in any order,
// into an empty magazine. With a recipe chosen nothing here acts.
@Mixin(value = BlockEntityMachineAmmoPress.class, remap = false)
public abstract class MixinAmmoPress {
    @Shadow
    public int selectedRecipe;

    @Shadow
    public abstract void hbm$syncSet$BlockEntityMachineAmmoPress$playAnimation(int next);

    @Inject(method = "performRecipe", at = @At("HEAD"), cancellable = true)
    private void gunfeel$load(CallbackInfo ci) {
        if (!PressLoading.ours(selectedRecipe)) return;
        if (PressLoading.perform(selectedRecipe, (Container) (Object) this)) {
            hbm$syncSet$BlockEntityMachineAmmoPress$playAnimation(40);
            ((net.minecraft.world.level.block.entity.BlockEntity) (Object) this).setChanged();
        }
        ci.cancel();
    }

    // with no recipe NTM takes nothing; ours takes our grenades in slots 0..7 and an empty magazine in slot 8
    @Inject(method = "canPlaceItem", at = @At("HEAD"), cancellable = true)
    private void gunfeel$place(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (PressLoading.accepts(selectedRecipe, slot, stack.getItem() instanceof GrenadeItem, stack.getItem() == Gl54Items.MAG_EMPTY.get())) cir.setReturnValue(true);
    }
}
