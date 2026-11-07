package acup.gunfeel.mixin;

import java.util.function.BooleanSupplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.factory.GunStateDecider;

import acup.gunfeel.ProneFire;
import net.minecraft.world.item.ItemStack;

// Server: a held burst stops once the shooter crawls (the gun goes idle; a new click fires again lying still), instead
// of firing, dry-firing or reloading (proneCrawlNoFire, see ProneFire)
@Mixin(value = GunStateDecider.class, remap = false)
public abstract class MixinProneFireDecider {
    @Inject(method = "deciderAutoRefire", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$crawl(ItemStack stack, ItemGunBaseNT.LambdaContext ctx, ItemGunBaseNT.GunState lastState, int recIndex,
            int gunIndex, BooleanSupplier refireCondition, CallbackInfo ci) {
        // the cheap test first: this runs every cooldown tick of every gun
        if (lastState != ItemGunBaseNT.GunState.COOLDOWN || ctx == null || !ProneFire.blocks(ctx.entity(), stack)) return;
        Receiver rec = ctx.config().getReceivers(stack)[recIndex];
        if (!rec.getRefireOnHold(stack) || !refireCondition.getAsBoolean()) return;
        ProneFire.stopBurst(stack, gunIndex);
        ci.cancel();
    }
}
