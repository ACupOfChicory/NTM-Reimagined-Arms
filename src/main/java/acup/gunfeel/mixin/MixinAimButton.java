package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.client.Keybinds;

import acup.gunfeel.AimButton;

// aimOnRightButton (AimButton): every gun key NTM sends goes through gunChanged(index, down); the right and middle
// buttons are told to NTM swapped for a gun that aims
@Mixin(value = Keybinds.class, remap = false)
public abstract class MixinAimButton {
    @Shadow
    private static void gunChanged(int index, boolean current) {
    }

    private static boolean gunfeel$mapping;

    @Inject(method = "gunChanged", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$aimButton(int index, boolean current, CallbackInfo ci) {
        if (gunfeel$mapping) return;
        int mapped = AimButton.map(index, current);
        if (mapped == index) return;
        ci.cancel();
        gunfeel$mapping = true;
        try {
            gunChanged(mapped, current);
        } finally {
            gunfeel$mapping = false;
        }
    }
}
