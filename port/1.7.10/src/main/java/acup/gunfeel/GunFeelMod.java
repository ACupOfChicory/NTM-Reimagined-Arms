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
        AmmoNet.init(); // the ammo key's and menu's messages (AmmoNet.register: onto a common channel later)
        if (client) GunFeelClient.init();
        TraceHooks.register(client);
        ModelHitNet.init(client);
        Net.init(client);
        TracerNet.register(Net.SETTINGS + 1); // ids 2 and 3: the bullets' word to the clients' tracers
        BulletCheck.registerIfAsked();
    }

    // not optional: the mod is needed on both sides (26.2: the registrar's protocol). A side without it is turned away;
    // other versions of it come in, and the hello then compares their protocols (Net). Without this FML would ask for
    // the very same version on both sides.
    @cpw.mods.fml.common.network.NetworkCheckHandler
    public boolean remote(java.util.Map<String, String> mods, Side side) {
        return mods.containsKey(MODID);
    }

    // dev runs (runServer): a file "autostop" in the run folder stops the dedicated test server once it is up (the
    // client's own server in single player stays)
    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        AmmoCheck.maybeRun(); // dev: a file "ammocheck" in the run folder
        if (!net.minecraft.server.MinecraftServer.getServer().isDedicatedServer() || !new java.io.File("autostop").exists()) return;
        GunFeel.LOGGER.info("autostop file found: stopping the server");
        net.minecraft.server.MinecraftServer.getServer().initiateShutdown();
    }
}
