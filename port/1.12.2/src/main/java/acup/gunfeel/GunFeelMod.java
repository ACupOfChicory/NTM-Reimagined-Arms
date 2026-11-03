package acup.gunfeel;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.relauncher.Side;

// The mod for Minecraft 1.12.2 Forge (or Cleanroom) with NTM CE. Mixins (MixinBooter) into NTM come from LateMixins,
// into Minecraft from mixins.ntmgunfeel.json (ArmsCore); the client's hooks from GunFeelClient.
@Mod(modid = GunFeelMod.MODID, name = "NTM Reimagined Arms " + BuildInfo.DISPLAY, version = BuildInfo.VERSION,
        acceptedMinecraftVersions = "[1.12.2]", dependencies = "required-after:hbm;required-after:mixinbooter")
public final class GunFeelMod {
    public static final String MODID = "ntmgunfeel";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        GunFeel.LOGGER.info("NTM Reimagined Arms {} ({}) on Java {}", BuildInfo.DISPLAY, BuildInfo.VERSION, System.getProperty("java.version"));
        boolean client = FMLCommonHandler.instance().getSide() == Side.CLIENT;
        acup.gunfeel.compat.Compat.setModVersion(BuildInfo.VERSION);
        if (client) GunFeelClient.init();
        TraceHooks.register(client);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new Sounds());
        // the mod's one channel: hello and settings (0, 1), then each system's messages at the next free ids
        Net.init(client);
        int id = TracerNet.register(Net.SETTINGS + 1); // 2-4: the bullets' word to the clients' tracers
        id = AmmoNet.register(Net.CHANNEL, id); // 5, 6: the ammo key's and menu's choice to the server
        ModelHitNet.register(id, client); // 7, 8: the client's poses and meshes of the model hitboxes
        BulletCheck.registerIfAsked();
        ModelCells.preload();
    }

    // not optional: the mod is needed on both sides (26.2: the registrar's protocol). A side without it is turned away;
    // other versions of it come in, and the hello then compares their protocols (Net). Without this FML would ask for
    // the very same version on both sides.
    @net.minecraftforge.fml.common.network.NetworkCheckHandler
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
        if (!net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance().isDedicatedServer() || !new java.io.File("autostop").exists()) return;
        GunFeel.LOGGER.info("autostop file found: stopping the server");
        net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance().initiateShutdown();
    }

    // port: 1.12.2 plays a sound on the server only as a registered SoundEvent (1.7.10: by its name): the mod's sounds,
    // each name in its sounds.json
    public static final class Sounds {
        @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
        public void register(net.minecraftforge.event.RegistryEvent.Register<net.minecraft.util.SoundEvent> event) {
            int n = 0;
            for (String name : names()) {
                net.minecraft.util.ResourceLocation id = new net.minecraft.util.ResourceLocation(MODID, name);
                event.getRegistry().register(new net.minecraft.util.SoundEvent(id).setRegistryName(id));
                n++;
            }
            GunFeel.LOGGER.info("Sounds registered: {}", n);
        }

        static java.util.List<String> names() {
            java.util.List<String> out = new java.util.ArrayList<String>();
            try (java.io.InputStream in = GunFeelMod.class.getResourceAsStream("/assets/" + MODID + "/sounds.json")) {
                if (in == null) return out;
                com.google.gson.JsonObject o = new com.google.gson.JsonParser()
                        .parse(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) out.add(e.getKey());
            } catch (java.io.IOException | RuntimeException e) {
                GunFeel.LOGGER.warn("sounds.json not read", e);
            }
            return out;
        }
    }
}
