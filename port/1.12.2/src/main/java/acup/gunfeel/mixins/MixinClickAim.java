package acup.gunfeel.mixins;

// port: 26.2's mixin/MixinClickAim (e809204). There every gun key goes through Keybinds.gunChanged; NTM CE sends each
// of its keys that changed from HbmKeybinds.handleProps (KeybindPacket through PacketDispatcher.wrapper). Just before
// that send the turn the player sees goes first. AimButton's swapped keys go through handleProps as well.

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.handler.HbmKeybinds;

import acup.gunfeel.ClickAim;

@Mixin(value = HbmKeybinds.class, remap = false)
public abstract class MixinClickAim {
    @Inject(method = "handleProps", at = @At(value = "INVOKE",
            target = "Lcom/hbm/main/NetworkHandler;sendToServer(Lnet/minecraftforge/fml/common/network/simpleimpl/IMessage;)V"))
    private static void gunfeel$clickAim(boolean state, int keyCode, CallbackInfo ci) {
        ClickAim.gunKey();
    }
}
