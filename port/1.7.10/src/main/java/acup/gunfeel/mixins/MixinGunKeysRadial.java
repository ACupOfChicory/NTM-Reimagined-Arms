package acup.gunfeel.mixins;

// port: 26.2's mixin/MixinGunKeysRadial.java (0762086) for NTM 1.7.10. 26.2 sets NTM's gun keys up in
// Keybinds$GameplayKey.refresh; NTM 1.7.10 reads them through ClientProxy.getIsKeyPressed (HbmKeybinds.handleProps on
// every input event), so the answer is changed there (AmmoLockKey.gunKey). Client only.

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.main.ClientProxy;

import acup.gunfeel.AmmoLockKey;

// While the radial ammo menu is open NTM's gun keys (fire, aim, tertiary/inspect, reload) count as let go: NTM sends
// the release, and a key held through the menu stays up until it is pressed again
@Mixin(value = ClientProxy.class, remap = false)
public abstract class MixinGunKeysRadial {
    @Inject(method = "getIsKeyPressed", at = @At("RETURN"), cancellable = true)
    private void gunfeel$radial(EnumKeybind key, CallbackInfoReturnable<Boolean> cir) {
        boolean down = cir.getReturnValueZ();
        boolean now = AmmoLockKey.gunKey(key, down);
        if (now != down) cir.setReturnValue(now);
    }
}
