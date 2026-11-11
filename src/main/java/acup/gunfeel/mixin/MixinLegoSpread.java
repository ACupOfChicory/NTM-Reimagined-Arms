package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.factory.Lego;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import acup.gunfeel.Spread;
import net.minecraft.world.item.ItemStack;

// Server: NTM's bullet spread by the shooter's state (speed, stance, aiming, in the air, bloom from firing), see Spread.
// A return value modifier, not a cancelling callback: the shock's (MixinShockSpread) goes on top of it
@Mixin(value = Lego.class, remap = false)
public abstract class MixinLegoSpread {
    @ModifyReturnValue(method = "calcSpread", at = @At("RETURN"))
    private static float gunfeel$state(float spread, ItemGunBaseNT.LambdaContext ctx, ItemStack stack, Receiver primary, BulletConfig config,
            boolean calcWear, int index, boolean aim) {
        if (ctx == null || ctx.entity() == null) return spread;
        // a shot of an AI mob's, whatever its gun's magazine (AiShots)
        acup.gunfeel.ai.AiShots.spread(ctx.entity());
        return Spread.apply(ctx.entity(), stack, aim, spread);
    }
}
