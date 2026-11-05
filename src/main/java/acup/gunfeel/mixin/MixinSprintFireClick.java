package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.Lego;

import acup.gunfeel.SprintFire;
import net.minecraft.world.item.ItemStack;

// Server: a click on an idle gun right after a sprint does not fire yet (sprintFireDelay, see SprintFire)
@Mixin(value = Lego.class, remap = false)
public abstract class MixinSprintFireClick {
    @Inject(method = "clickReceiver", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$sprint(ItemStack stack, ItemGunBaseNT.LambdaContext ctx, int receiver, CallbackInfo ci) {
        if (ctx == null || ctx.entity() == null) return;
        if (ItemGunBaseNT.getState(stack, ctx.configIndex()) != ItemGunBaseNT.GunState.IDLE) return;
        if (!SprintFire.allowClick(stack, ctx, receiver)) ci.cancel();
    }
}
