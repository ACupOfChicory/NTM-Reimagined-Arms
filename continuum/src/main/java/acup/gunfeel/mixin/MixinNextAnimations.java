package acup.gunfeel.mixin;

import java.util.function.BiFunction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.items.weapon.sedna.GunAnimation;
import com.hbm.items.weapon.sedna.GunConfig;

import acup.gunfeel.ContinuumAnimations;
import acup.gunfeel.MagazineInspect;
import dev.golem.core.render.anim.BusAnimation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

// port/continuum: the gun's config side of ContinuumAnimations. Continuum's GunAnimationHandler asks the config's own
// animation first (the charge thrower has one): a gun NTM NEXT has gets none, so GunAnimations.get (MixinGunAnimations)
// gives NEXT's. And main's MixinG3Inspect's inspect length: the G3's inspect with the owner's magazine release.
@Mixin(value = GunConfig.class, remap = false)
public abstract class MixinNextAnimations {
    @Inject(method = "getAnimation", at = @At("RETURN"), cancellable = true)
    private void gunfeel$next(ItemStack stack, CallbackInfoReturnable<BiFunction<ItemStack, GunAnimation, BusAnimation>> cir) {
        if (cir.getReturnValue() != null && stack != null && ContinuumAnimations.covers(stack)) cir.setReturnValue(null);
    }

    @Inject(method = "getInspectDuration", at = @At("RETURN"), cancellable = true)
    private void gunfeel$g3InspectDuration(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        if (stack == null) return;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!id.equals("hbm:gun_g3") && !id.equals("hbm:gun_g3_zebra")) return;
        float value = ContinuumAnimations.g3Release(stack);
        if (Float.isFinite(value) && value >= 0) cir.setReturnValue((MagazineInspect.release(value) + 1350 + 49) / 50);
    }
}
