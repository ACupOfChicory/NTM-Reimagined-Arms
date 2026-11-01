package acup.gunfeel.compat;

import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import acup.gunfeel.GunFeel;

// port: 26.2's HbmMixinErrors. The last net for the mixins into NTM: one that fails while Mixin prepares or applies
// it, past NtmCheck (a target its refmap names wrong, as MixinHoldAim's onUpdate in X378.68), is reported and skipped
// instead of crashing the game, and its systems go off (/gunfeel status). Mixins of the other configs (the mod's into
// Minecraft, other mods') are left to Mixin as they are.
public final class HbmMixinErrors implements IMixinErrorHandler {
    static final String CONFIG = "mixins.ntmgunfeel.late.json";

    @Override
    public ErrorAction onPrepareError(IMixinConfig config, Throwable th, IMixinInfo mixin, ErrorAction action) {
        return handle(config != null ? config.getName() : null, th, mixin, action);
    }

    @Override
    public ErrorAction onApplyError(String targetClassName, Throwable th, IMixinInfo mixin, ErrorAction action) {
        return handle(mixin != null && mixin.getConfig() != null ? mixin.getConfig().getName() : null, th, mixin, action);
    }

    private static ErrorAction handle(String config, Throwable th, IMixinInfo mixin, ErrorAction action) {
        if (!CONFIG.equals(config) || mixin == null) return action;
        String name = mixin.getName();
        name = name.substring(name.lastIndexOf('.') + 1);
        GunFeel.LOGGER.error("Mixin {} into NTM failed, its systems are turned off", name, th);
        try {
            Compat.failedLate(name, th == null ? "failed" : String.valueOf(th.getMessage()));
        } catch (Throwable ignored) {
            // recording must never turn a skipped mixin into a crash
        }
        return ErrorAction.WARN;
    }
}
