package acup.gunfeel.mixin;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.items.weapon.sedna.GunAnimation;
import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.Orchestras;

import acup.gunfeel.ContinuumAnimations;
import acup.gunfeel.MagazineInspect;
import acup.gunfeel.compat.Feature;
import dev.golem.core.render.anim.BusAnimation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

// port/continuum: the gun's config side of ContinuumAnimations. Continuum's GunAnimationHandler asks the config's own
// animation first (the charge thrower has one): a gun NTM NEXT has gets none, so GunAnimations.get (MixinGunAnimations)
// gives NEXT's. And the sounds and length of the inspects ContinuumAnimations changes: main's MixinG3Inspect (the G3's
// inspect with the owner's magazine release) and MixinGunConfigAnims (Sexy / Heretic: the bottle comes later).
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

    // main's MixinG3Inspect.SOUNDS: the magazine's two sounds at the owner's release, Continuum's ORCHESTRA_G3 otherwise
    private static final BiConsumer<ItemStack, ItemGunBaseNT.LambdaContext> G3_SOUNDS = (stack, ctx) -> {
        int index = ctx.configIndex;
        float value = ContinuumAnimations.g3Release(stack);
        if (!Feature.ARMS.on() || ItemGunBaseNT.getLastAnim(stack, index) != GunAnimation.INSPECT || !Float.isFinite(value) || value < 0) {
            Orchestras.ORCHESTRA_G3.accept(stack, ctx);
            return;
        }
        int timer = ItemGunBaseNT.getAnimTimer(stack, index), r = MagazineInspect.release(value);
        int original = timer == (MagazineInspect.PREPARE + (r - MagazineInspect.PREPARE) / 3 + 25) / 50 ? 2
                : timer == (r + 1100 + 25) / 50 ? 28 : -1;
        if (original < 0) return;
        ItemGunBaseNT.setAnimTimer(stack, index, original);
        try {
            Orchestras.ORCHESTRA_G3.accept(stack, ctx);
        } finally {
            ItemGunBaseNT.setAnimTimer(stack, index, timer);
        }
    };

    // main's MixinGunConfigAnims.SEXY_ORCHESTRA: the inspect's gulps as much later as the bottle (ContinuumAnimations.sexy)
    private static final int SEXY_BOTTLE_WAIT_TICKS = ContinuumAnimations.SEXY_BOTTLE_WAIT_MS / 50;
    private static final BiConsumer<ItemStack, ItemGunBaseNT.LambdaContext> SEXY_SOUNDS = (stack, ctx) -> {
        int index = ctx.configIndex;
        if (!Feature.MARESLEG.on() || ItemGunBaseNT.getLastAnim(stack, index) != GunAnimation.INSPECT) {
            Orchestras.ORCHESTRA_SHREDDER_SEXY.accept(stack, ctx);
            return;
        }
        int timer = ItemGunBaseNT.getAnimTimer(stack, index);
        if (timer < SEXY_BOTTLE_WAIT_TICKS) return;
        ItemGunBaseNT.setAnimTimer(stack, index, timer - SEXY_BOTTLE_WAIT_TICKS);
        try {
            Orchestras.ORCHESTRA_SHREDDER_SEXY.accept(stack, ctx);
        } finally {
            ItemGunBaseNT.setAnimTimer(stack, index, timer);
        }
    };

    @Inject(method = "getOrchestra", at = @At("RETURN"), cancellable = true)
    private void gunfeel$inspectSounds(ItemStack stack, CallbackInfoReturnable<BiConsumer<ItemStack, ItemGunBaseNT.LambdaContext>> cir) {
        if (cir.getReturnValue() == Orchestras.ORCHESTRA_G3) cir.setReturnValue(G3_SOUNDS);
        else if (cir.getReturnValue() == Orchestras.ORCHESTRA_SHREDDER_SEXY) cir.setReturnValue(SEXY_SOUNDS);
    }
}
