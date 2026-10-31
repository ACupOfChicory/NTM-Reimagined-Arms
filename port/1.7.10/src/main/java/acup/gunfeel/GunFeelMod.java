package acup.gunfeel;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.relauncher.Side;

// The mod for Minecraft 1.7.10 Forge with NTM 1.7.10. Mixins into NTM come from LateMixins, into Minecraft from
// mixins.ntmgunfeel.json; the client's hooks from GunFeelClient.
@Mod(modid = GunFeelMod.MODID, name = "NTM Reimagined Arms " + BuildInfo.DISPLAY, version = BuildInfo.VERSION,
        acceptedMinecraftVersions = "[1.7.10]", dependencies = "required-after:hbm")
public final class GunFeelMod {
    public static final String MODID = "ntmgunfeel";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        GunFeel.LOGGER.info("NTM Reimagined Arms {} ({}) on Java {}", BuildInfo.DISPLAY, BuildInfo.VERSION, System.getProperty("java.version"));
        boolean client = FMLCommonHandler.instance().getSide() == Side.CLIENT;
        if (client) GunFeelClient.init();
        TraceHooks.register(client);
    }

    // dev runs (runServer): a file "autostop" in the run folder stops the test server once it is up
    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        if (!new java.io.File("autostop").exists()) return;
        GunFeel.LOGGER.info("autostop file found: stopping the server");
        net.minecraft.server.MinecraftServer.getServer().initiateShutdown();
    }
}
