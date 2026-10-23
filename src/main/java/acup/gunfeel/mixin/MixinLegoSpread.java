package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.factory.Lego;

import acup.gunfeel.Spread;
import net.minecraft.world.item.ItemStack;

// Server: NTM's bullet spread by the shooter's state (speed, stance, aiming, in the air, bloom from firing), see Spread
@Mixin(value = Lego.class, remap = false)
public abstract class MixinLegoSpread {
    @Inject(method = "calcSpread", at = @At("RETURN"), cancellable = true)
    private static void gunfeel$state(ItemGunBaseNT.LambdaContext ctx, ItemStack stack, Receiver primary, BulletConfig config,
            boolean calcWear, int index, boolean aim, CallbackInfoReturnable<Float> cir) {
        if (ctx == null || ctx.entity() == null) return;
        cir.setReturnValue(Spread.apply(ctx.entity(), stack, aim, cir.getReturnValue()));
    }
}
