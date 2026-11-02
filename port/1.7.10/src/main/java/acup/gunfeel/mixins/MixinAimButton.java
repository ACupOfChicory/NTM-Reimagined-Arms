package acup.gunfeel.mixins;

// port: 26.2's mixin/MixinAimButton.java (f982b26) for NTM 1.7.10: there it wraps Keybinds.gunChanged; here every gun
// key's state is read through ClientProxy.getIsKeyPressed (HbmKeybinds.handleProps), so the swap is made on the states.

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.handler.HbmKeybinds;
import com.hbm.main.ClientProxy;

import acup.gunfeel.AimButton;

@Mixin(value = ClientProxy.class, remap = false)
public abstract class MixinAimButton {
    @Inject(method = "getIsKeyPressed", at = @At("HEAD"), cancellable = true)
    private void gunfeel$aimButton(HbmKeybinds.EnumKeybind key, CallbackInfoReturnable<Boolean> cir) {
        if (key != HbmKeybinds.EnumKeybind.GUN_SECONDARY && key != HbmKeybinds.EnumKeybind.GUN_TERTIARY) return;
        // cancelled here, the method's RETURN handlers do not run: the radial menu's (MixinGunKeysRadial) is applied here
        cir.setReturnValue(acup.gunfeel.AmmoLockKey.gunKey(key, AimButton.pressed(key == HbmKeybinds.EnumKeybind.GUN_SECONDARY)));
    }
}
