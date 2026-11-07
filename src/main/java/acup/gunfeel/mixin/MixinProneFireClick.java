package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.Lego;

import acup.gunfeel.ProneFire;
import net.minecraft.world.item.ItemStack;

// Server: a click on an idle gun while crawling does nothing, not even a dry fire (proneCrawlNoFire, see ProneFire)
@Mixin(value = Lego.class, remap = false)
public abstract class MixinProneFireClick {
    @Inject(method = "clickReceiver", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$crawl(ItemStack stack, ItemGunBaseNT.LambdaContext ctx, int receiver, CallbackInfo ci) {
        if (ProneFire.blocksClick(stack, ctx)) ci.cancel();
    }
}
