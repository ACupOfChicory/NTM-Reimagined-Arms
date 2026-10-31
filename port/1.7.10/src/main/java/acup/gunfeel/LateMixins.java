package acup.gunfeel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

import cpw.mods.fml.relauncher.FMLLaunchHandler;

// Mixins into NTM must be "late" (GTNHMixins, part of UniMixins): NTM's jar is on the class path only after FML's mod
// discovery. The client's change how it draws and turns the camera; the common ones go on both sides: bullets on the
// server (their path through blocks, piercing, breaking) and the ammo lock and choice (they change the reload). They
// are listed here only (the json names none): with an NTM not tested NtmCheck leaves out the ones that do not fit.
@LateMixin
public class LateMixins implements ILateMixinLoader {
    static final List<String> CLIENT = Arrays.asList("MixinModEventHandlerClient", "MixinItemRenderWeaponBase",
            "MixinHbmAnimations", "MixinWavefrontObject", "MixinWavefrontObjectVBO",
            "MixinRenderBulletMK4", "MixinLegoClient", "MixinSmokeNodes",
            "MixinGunKeysRadial", "MixinGunHud", "MixinHbmAnimationPacket", "MixinG3Inspect", "MixinClock");

    static final List<String> COMMON = Arrays.asList("MixinAmmoLock", "MixinAmmoLockBelt", "MixinBulletServer",
            "MixinThrowableClip", "MixinBeamHits", "MixinGunConfigAnims", "MixinHeadDamage",
            "MixinRicochetSound");

    @Override
    public String getMixinConfig() {
        return "mixins.ntmgunfeel.late.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        List<String> list = new ArrayList<String>();
        if (loadedMods.contains("hbm")) list.addAll(COMMON);
        if (loadedMods.contains("hbm") && FMLLaunchHandler.side().isClient()) list.addAll(CLIENT);
        // an NTM not tested with this version: only the mixins that fit it (Compat, NtmCheck)
        if (!list.isEmpty()) list = acup.gunfeel.compat.NtmCheck.filter(list, PortPaths.hbmVersion());
        GunFeel.LOGGER.info("Mixins into NTM {} ({}): {}{}", PortPaths.hbmVersion(), acup.gunfeel.compat.Compat.inRange() ? "tested" : "not tested",
                list.isEmpty() ? "none (no NTM)" : list, FMLLaunchHandler.side().isClient() ? "" : " (server)");
        return list;
    }
}
