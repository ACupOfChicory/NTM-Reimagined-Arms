package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.client.HbmKeybindsClient;
import com.hbm.handler.HbmKeybinds.EnumKeybind;

import acup.gunfeel.ClickAim;

// port/continuum: src/main/java MixinClickAim on NTM Continuum (ClickAim). Every gun key Continuum sends goes through
// HbmKeybindsClient.report, which sends a KeybindPayload only when the key changed: the turn goes right before that
// send. MixinHbmAimButton reports both buttons it remaps through report again; the second finds the turn already sent.
@Mixin(value = HbmKeybindsClient.class, remap = false)
public abstract class MixinClickAim {
    @Inject(method = "report", at = @At(value = "INVOKE",
            target = "Ldev/golem/core/net/GolemNetwork;sendToServer(Ldev/golem/core/net/GolemCodec;Ldev/golem/core/net/GolemPayload;)V"))
    private static void gunfeel$clickAim(EnumKeybind keybind, boolean state, CallbackInfo ci) {
        ClickAim.gunKey();
    }
}
