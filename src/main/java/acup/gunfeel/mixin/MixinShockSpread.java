package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.factory.Lego;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import acup.gunfeel.shock.ShockServer;
import net.minecraft.world.item.ItemStack;

// Server: a shocked shooter's shots spread wide (ShockServer), on top of all the rest: a return value modifier applied after
// MixinLegoSpread (priority): the spread of the shooter's state first, this on top
@Mixin(value = Lego.class, remap = false, priority = 1100)
public abstract class MixinShockSpread {
    @ModifyReturnValue(method = "calcSpread", at = @At("RETURN"), require = 1)
    private static float gunfeel$shock(float spread, ItemGunBaseNT.LambdaContext ctx, ItemStack stack, Receiver primary, BulletConfig config,
            boolean calcWear, int index, boolean aim) {
        if (ctx == null || ctx.entity() == null) return spread;
        return spread + ShockServer.spread(ctx.entity(), config);
    }
}
