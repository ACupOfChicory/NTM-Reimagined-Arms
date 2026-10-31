package acup.gunfeel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

import cpw.mods.fml.relauncher.FMLLaunchHandler;

// Mixins into NTM must be "late" (GTNHMixins, part of UniMixins): NTM's jar is on the class path only after FML's mod
// discovery. The client's change how it draws and turns the camera; the common ones (bullets on the server: their
// path through blocks, piercing, breaking) go on both sides.
@LateMixin
public class LateMixins implements ILateMixinLoader {
    static final List<String> CLIENT = Arrays.asList("MixinModEventHandlerClient", "MixinItemRenderWeaponBase",
            "MixinHbmAnimations", "MixinWavefrontObject", "MixinWavefrontObjectVBO");
    static final List<String> COMMON = Arrays.asList("MixinBulletServer", "MixinThrowableClip");

    @Override
    public String getMixinConfig() {
        return "mixins.ntmgunfeel.late.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        List<String> list = new ArrayList<String>();
        if (loadedMods.contains("hbm")) list.addAll(COMMON);
        if (loadedMods.contains("hbm") && FMLLaunchHandler.side().isClient()) list.addAll(CLIENT);
        GunFeel.LOGGER.info("Mixins into NTM: {}{}", list.isEmpty() ? "none (no NTM)" : list, FMLLaunchHandler.side().isClient() ? "" : " (server)");
        return list;
    }
}
