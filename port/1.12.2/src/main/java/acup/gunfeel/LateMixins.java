package acup.gunfeel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;

// Mixins into NTM must be "late" (MixinBooter's ILateMixinLoader, acup.gunfeel.core.ArmsLateMixins): NTM's jar is on
// the class path only after FML's mod discovery. The client's change how it draws and turns the camera; the common ones
// go on both sides: bullets on the server (their path through blocks, piercing, breaking) and the ammo lock and choice
// (they change the reload). They are listed here only (mixins.ntmgunfeel.late.json names none and has this class as its
// plugin): with an NTM not tested NtmCheck leaves out the ones that do not fit.
public class LateMixins implements IMixinConfigPlugin {
    static final List<String> CLIENT = Arrays.asList("MixinModEventHandlerClient", "MixinItemRenderWeaponBase",
            "MixinHbmAnimations", "MixinWavefrontObject", "MixinVaoModel", "MixinGroupHandle",
            "MixinRenderBulletMK4", "MixinLegoClient", "MixinSmokeNodes",
            "MixinGunKeysRadial", "MixinGunHud", "MixinHbmAnimationPacket", "MixinG3Inspect", "MixinClock", "MixinAimButton", "MixinClickAim",
            "MixinSpectatorKeys", "MixinSpectatorHud");

    static final List<String> COMMON = Arrays.asList("MixinAmmoLock", "MixinAmmoLockBelt", "MixinBulletServer",
            "MixinThrowableClip", "ThrowableAccess", "MixinBeamHits", "MixinGunConfigAnims", "MixinHeadDamage",
            "MixinRicochetSound", "MixinExplosiveBullet", "MixinExplosionContext", "MixinExplosiveProcessor", "MixinExplosiveDirect",
            "MixinLegoSpread", "MixinHoldAim", "MixinSprintFireClick", "MixinSprintFireDecider", "MixinSprintFireGun",
            "MixinSpectatorGun", "MixinSpectatorOldGun");

    @Override
    public List<String> getMixins() {
        boolean hbm = Loader.isModLoaded("hbm");
        // a mixin into NTM that still fails is skipped, its systems off, the game not stopped (HbmMixinErrors)
        try {
            org.spongepowered.asm.mixin.Mixins.registerErrorHandlerClass(acup.gunfeel.compat.HbmMixinErrors.class.getName());
        } catch (Throwable t) {
            GunFeel.LOGGER.warn("Could not register the handler of failed mixins into NTM", t);
        }
        List<String> list = new ArrayList<String>();
        if (hbm) list.addAll(COMMON);
        if (hbm && FMLLaunchHandler.side().isClient()) list.addAll(CLIENT);
        // an NTM not tested with this version: only the mixins that fit it (Compat, NtmCheck)
        if (!list.isEmpty()) list = acup.gunfeel.compat.NtmCheck.filter(list, PortPaths.hbmVersion());
        GunFeel.LOGGER.info("Mixins into NTM {} ({}): {}{}", PortPaths.hbmVersion(), acup.gunfeel.compat.Compat.inRange() ? "tested" : "not tested",
                list.isEmpty() ? "none (no NTM)" : list, FMLLaunchHandler.side().isClient() ? "" : " (server)");
        return list;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
