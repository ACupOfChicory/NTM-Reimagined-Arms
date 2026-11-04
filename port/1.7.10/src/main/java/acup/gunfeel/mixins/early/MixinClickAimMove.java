package acup.gunfeel.mixins.early;

// port: new for 1.7.10 (ClickAim). When ClickAim sent the tick's move packet early, the tick's own sendMotionUpdates
// sends nothing: 1.7.10's server runs a tick of the player for every move packet. What vanilla would have sent (the
// movement, sprinting, sneaking) it sends a tick later, from fields it has not updated.

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.ClickAim;
import net.minecraft.client.entity.EntityClientPlayerMP;

@Mixin(EntityClientPlayerMP.class)
public abstract class MixinClickAimMove {
    @Inject(method = "sendMotionUpdates", at = @At("HEAD"), cancellable = true)
    private void gunfeel$clickAim(CallbackInfo ci) {
        if (!ClickAim.sendMove()) ci.cancel();
    }
}
