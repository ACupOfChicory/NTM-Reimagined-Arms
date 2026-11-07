package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.client.Keybinds;

import acup.gunfeel.gas.client.GasClient;

// Chlorine: while the player coughs (and the server's gasCoughBlocksAim is on) NTM's third gun key, the aim, is not
// pressed, and an aim already held is let go when a cough starts (GasClient). A later priority than MixinAimButton's, so it sees the key after the right button was turned into it.
@Mixin(value = Keybinds.class, remap = false, priority = 1100)
public abstract class MixinGasAim {
    @Inject(method = "gunChanged", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$coughNoAim(int index, boolean current, CallbackInfo ci) {
        if (index != 2) return;
        if (current && GasClient.aimBlocked()) ci.cancel();
        else GasClient.aimKey(current);
    }
}
