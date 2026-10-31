package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.client.HbmKeybindsClient;
import com.hbm.handler.HbmKeybinds.EnumKeybind;

import acup.gunfeel.RadialMenu;
import dev.golem.core.client.KeyMappings;
import net.minecraft.client.Minecraft;

// port/continuum: MixinGunKeysRadial on NTM Continuum. Continuum reads the gun keys (fire, aim, tertiary/inspect,
// reload) from the physical keys each tick and on each mouse button and reports changes to the server
// (HbmKeybindsClient.report). While the radial ammo menu is open they count as let go: the server gets the release,
// and a key held through the menu stays up until it is let go and pressed again.
@Mixin(value = HbmKeybindsClient.class, remap = false)
public abstract class MixinHbmKeysRadial {
    @Shadow
    @Final
    private static boolean[] LAST_STATE;
    // held through the menu: reported as up until let go
    @Unique
    private static final boolean[] gunfeel$swallowed = new boolean[EnumKeybind.values().length];
    @Unique
    private static boolean gunfeel$releasing;

    @Shadow
    private static void report(EnumKeybind keybind, boolean state) {
        throw new AssertionError();
    }

    @Inject(method = "report", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$radial(EnumKeybind keybind, boolean state, CallbackInfo ci) {
        if (gunfeel$releasing || !gunfeel$gunKey(keybind)) return;
        int i = keybind.ordinal();
        if (RadialMenu.isOpen()) gunfeel$swallowed[i] = true;
        else if (!state) gunfeel$swallowed[i] = false;
        if (!state || !gunfeel$swallowed[i]) return;
        ci.cancel();
        if (!LAST_STATE[i]) return;
        gunfeel$releasing = true;
        try {
            report(keybind, false);
        } finally {
            gunfeel$releasing = false;
        }
    }

    // a left click in the open menu takes the hovered ammo (AmmoLockKey reads the attack key's click): Continuum would
    // drain that click here, before the tick
    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$radialClick(int button, boolean pressed, CallbackInfo ci) {
        if (RadialMenu.isOpen() && KeyMappings.matchesMouse(Minecraft.getInstance().options.keyAttack, button)) ci.cancel();
    }

    @Unique
    private static boolean gunfeel$gunKey(EnumKeybind k) {
        return k == EnumKeybind.GUN_PRIMARY || k == EnumKeybind.GUN_SECONDARY || k == EnumKeybind.GUN_TERTIARY || k == EnumKeybind.RELOAD;
    }
}
