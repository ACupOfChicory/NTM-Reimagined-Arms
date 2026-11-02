package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.client.HbmKeybindsClient;
import com.hbm.handler.HbmKeybinds.EnumKeybind;

import acup.gunfeel.AimButton;

// port/continuum: MixinAimButton on NTM Continuum. Continuum reports every gun key from its physical key, each tick and
// on each mouse button (HbmKeybindsClient.report, which sends only changes). A report of the right or middle button
// (GUN_SECONDARY, GUN_TERTIARY) is turned into NTM's second and third keys as AimButton has them, and both are
// reported. MixinHbmKeysRadial sees the reports made here.
@Mixin(value = HbmKeybindsClient.class, remap = false)
public abstract class MixinHbmAimButton {
    @Unique
    private static boolean gunfeel$reporting;

    @Shadow
    private static void report(EnumKeybind keybind, boolean state) {
        throw new AssertionError();
    }

    @Inject(method = "report", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$aimButton(EnumKeybind keybind, boolean state, CallbackInfo ci) {
        if (gunfeel$reporting || keybind != EnumKeybind.GUN_SECONDARY && keybind != EnumKeybind.GUN_TERTIARY) return;
        ci.cancel();
        boolean[] keys = AimButton.update(keybind == EnumKeybind.GUN_SECONDARY ? 0 : 1, state);
        gunfeel$reporting = true;
        try {
            report(EnumKeybind.GUN_SECONDARY, keys[0]);
            report(EnumKeybind.GUN_TERTIARY, keys[1]);
        } finally {
            gunfeel$reporting = false;
        }
    }
}
