package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.handler.HbmKeybinds;
import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.main.ServerProxy;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.SpectatorGuns;
import net.minecraft.client.Minecraft;

// port: 26.2's MixinSpectatorKeys. NTM CE's client sends each of its keys that changed (HbmKeybinds.handleProps): a
// spectator's gun key counts as not pressed, so its press is not sent; letting go of one held as the mode changed is
// still sent, the server lets it go quietly (MixinSpectatorGun).
@Mixin(value = HbmKeybinds.class, remap = false)
public abstract class MixinSpectatorKeys {
    @WrapOperation(method = "handleProps", at = @At(value = "INVOKE",
            target = "Lcom/hbm/main/ServerProxy;getIsKeyPressed(Lcom/hbm/handler/HbmKeybinds$EnumKeybind;)Z"))
    private static boolean gunfeel$spectator(ServerProxy proxy, EnumKeybind key, Operation<Boolean> original) {
        boolean pressed = original.call(proxy, key);
        if (!pressed) return false;
        boolean gun = key == EnumKeybind.GUN_PRIMARY || key == EnumKeybind.GUN_SECONDARY || key == EnumKeybind.GUN_TERTIARY || key == EnumKeybind.RELOAD;
        return !(gun && SpectatorGuns.blocks(Minecraft.getMinecraft().player));
    }
}
