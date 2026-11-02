package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.client.Keybinds;

import acup.gunfeel.AimButton;

// aimOnRightButton (AimButton): every gun key NTM sends goes through gunChanged(index, down). A change of the right or
// middle button (index 1, 2) is turned into NTM's second and third keys as AimButton has them, and each of those that
// changed is sent.
@Mixin(value = Keybinds.class, remap = false)
public abstract class MixinAimButton {
    @Shadow
    private static void gunChanged(int index, boolean current) {
    }

    @Unique
    private static boolean gunfeel$sending;
    @Unique
    private static final boolean[] gunfeel$sent = new boolean[2];

    @Inject(method = "gunChanged", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$aimButton(int index, boolean current, CallbackInfo ci) {
        if (gunfeel$sending || index != 1 && index != 2) return;
        ci.cancel();
        boolean[] keys = AimButton.update(index - 1, current);
        gunfeel$sending = true;
        try {
            for (int k = 0; k < 2; k++) {
                if (keys[k] == gunfeel$sent[k]) continue;
                gunfeel$sent[k] = keys[k];
                gunChanged(k + 1, keys[k]);
            }
        } finally {
            gunfeel$sending = false;
        }
    }
}
