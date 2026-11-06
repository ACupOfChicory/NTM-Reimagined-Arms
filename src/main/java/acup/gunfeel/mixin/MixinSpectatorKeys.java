package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.hbm.client.Keybinds;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import acup.gunfeel.SpectatorGuns;
import net.minecraft.client.Minecraft;

// Client: a spectator's press of a gun key is not sent (spectatorNoGuns, see SpectatorGuns), so a server without the
// mod does not fire either. A release still goes, for a key pressed before the mode changed. The whole method is
// wrapped, so the mod's own hooks in it (MixinAimButton, MixinClickAim) do not see the press.
@Mixin(value = Keybinds.class, remap = false)
public abstract class MixinSpectatorKeys {
    @WrapMethod(method = "gunChanged")
    private static void gunfeel$spectator(int index, boolean current, Operation<Void> original) {
        if (current && SpectatorGuns.blocks(Minecraft.getInstance().player)) return;
        original.call(index, current);
    }
}
