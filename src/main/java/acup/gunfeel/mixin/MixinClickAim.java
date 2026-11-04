package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.client.Keybinds;

import acup.gunfeel.ClickAim;

// clickAim (ClickAim): every gun key NTM sends goes through gunChanged, which sends NTM's KeybindPayload. At its head
// the turn the player sees goes first. MixinAimButton calls gunChanged again for the key it sends: the turn is then
// already sent and is not sent twice.
@Mixin(value = Keybinds.class, remap = false)
public abstract class MixinClickAim {
    @Inject(method = "gunChanged", at = @At("HEAD"))
    private static void gunfeel$clickAim(int index, boolean current, CallbackInfo ci) {
        ClickAim.gunKey();
    }
}
