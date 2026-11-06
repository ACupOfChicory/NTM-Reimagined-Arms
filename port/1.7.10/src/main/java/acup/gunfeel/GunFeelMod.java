package acup.gunfeel;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.relauncher.Side;

// The mod for Minecraft 1.7.10 Forge with NTM 1.7.10. Mixins into NTM come from LateMixins, into Minecraft from
// mixins.ntmgunfeel.json; the client's hooks from GunFeelClient.
@Mod(modid = GunFeelMod.MODID, name = "NTM Reimagined Arms " + BuildInfo.DISPLAY, version = BuildInfo.VERSION,
        acceptedMinecraftVersions = "[1.7.10]", dependencies = "required-after:hbm")
public final class GunFeelMod {
    public static final String MODID = "ntmgunfeel";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        DebugLog.setup(Boolean.TRUE.equals(net.minecraft.launchwrapper.Launch.blackboard.get("fml.deobfuscatedEnvironment")));
        // the one line of the mod's in the game's log at start (a logger of its own, past DebugLog's)
        org.apache.logging.log4j.LogManager.getLogger("NTM Reimagined Arms").info("NTM Reimagined Arms {} ({}) on Java {}: its own log in {}",
                BuildInfo.DISPLAY, BuildInfo.VERSION, System.getProperty("java.version"), DebugLog.FILE);
        boolean client = FMLCommonHandler.instance().getSide() == Side.CLIENT;
        acup.gunfeel.compat.Compat.setModVersion(BuildInfo.VERSION);
        if (client) GunFeelClient.init();
        TraceHooks.register(client);
        // the mod's one channel: hello and settings (0, 1), then each system's messages at the next free ids
        Net.init(client);
        int id = TracerNet.register(Net.SETTINGS + 1); // 2-4: the bullets' word to the clients' tracers
        id = AmmoNet.register(Net.CHANNEL, id); // 5, 6: the ammo key's and menu's choice to the server
        ModelHitNet.register(id, client); // 7, 8: the client's poses and meshes of the model hitboxes
        BulletCheck.registerIfAsked();
        WaterCheck.registerIfAsked();
        ModelCells.preload();
    }

    // not optional: the mod is needed on both sides (26.2: the registrar). A side without it is turned away; other
    // builds of it come in, and the hello then kicks a player of another build with a text that names both (Net).
    // Without this FML would ask for the very same version on both sides, with a bare "Mod rejections" for a text.
    @cpw.mods.fml.common.network.NetworkCheckHandler
    public boolean remote(java.util.Map<String, String> mods, Side side) {
        return mods.containsKey(MODID);
    }

    // /gunaim (HOLD_AIM): how early the held aim comes back after a reload or an inspect
    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        if (acup.gunfeel.compat.Feature.HOLD_AIM.on()) event.registerServerCommand(new ReaimCommand());
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
