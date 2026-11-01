package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.client.anim.GunAnimations;
import com.hbm.items.weapon.sedna.GunAnimation;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.ContinuumAnimations;
import dev.golem.core.render.anim.BusAnimation;
import net.minecraft.world.item.ItemStack;

// port/continuum: a gun NTM NEXT has animates as on 26.2 (ContinuumAnimations), Continuum's own table is not asked; for
// a cue NEXT has no animation for there is none, as on 26.2
@Mixin(value = GunAnimations.class, remap = false)
public abstract class MixinGunAnimations {
    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$next(ItemStack stack, GunAnimation cue, int rail, CallbackInfoReturnable<BusAnimation> cir) {
        if (!(stack.getItem() instanceof ItemGunBaseNT gun) || !ContinuumAnimations.covers(stack)) return;
        cir.setReturnValue(gun.isValidConfigIndex(rail) ? ContinuumAnimations.get(stack, cue) : null);
    }
}
