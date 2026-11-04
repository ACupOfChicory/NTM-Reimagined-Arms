package acup.gunfeel.mixin;

import java.util.function.BooleanSupplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.factory.GunStateDecider;

import acup.gunfeel.SprintFire;
import net.minecraft.world.item.ItemStack;

// Server: a held burst right after a sprint waits in NTM's cooldown (its timer is out, so NTM asks again next tick)
// instead of firing, dry-firing or going idle (sprintFireDelay, see SprintFire)
@Mixin(value = GunStateDecider.class, remap = false)
public abstract class MixinSprintFireDecider {
    @Inject(method = "deciderAutoRefire", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$sprint(ItemStack stack, ItemGunBaseNT.LambdaContext ctx, ItemGunBaseNT.GunState lastState, int recIndex,
            int gunIndex, BooleanSupplier refireCondition, CallbackInfo ci) {
        if (lastState != ItemGunBaseNT.GunState.COOLDOWN || ctx == null || ctx.entity() == null) return;
        Receiver rec = ctx.config().getReceivers(stack)[recIndex];
        if (!rec.getRefireOnHold(stack) || !refireCondition.getAsBoolean()) return;
        if (!SprintFire.allowRefire(stack, ctx)) ci.cancel();
    }
}
