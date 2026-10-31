package acup.gunfeel;

import java.util.concurrent.ConcurrentLinkedQueue;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

// port: from 26.2's ServerSettingsClient (0762086) and the hello part of compat.CompatClient. Messages come in on the
// network thread (Net); leaving is FML's ClientDisconnectionFromServerEvent (26.2: ClientPlayerNetworkEvent.LoggingOut);
// "this game is the server" is isIntegratedServerRunning (26.2: hasSingleplayerServer); the chat lines wait for the
// player in a client tick, and are plain English (the port has no language files yet).
// Client: the server's settings (ServerSettings) count while connected to another game's server, and are forgotten on
// leaving it. In single player and on a LAN host this game is the server and reads the same files, so they are left
// alone; LAN guests and players on a dedicated server go by the server's.
public final class ServerSettingsClient {
    private ServerSettingsClient() {
    }

    // the server's hello, null before one (26.2: CompatClient.serverHello)
    private static volatile Net.Hello server;
    private static final ConcurrentLinkedQueue<String> CHAT = new ConcurrentLinkedQueue<String>();

    static void init() {
        FMLCommonHandler.instance().bus().register(new ServerSettingsClient());
    }

    public static Net.Hello serverHello() {
        return server;
    }

    @SubscribeEvent
    public void left(FMLNetworkEvent.ClientDisconnectionFromServerEvent e) {
        ServerSettings.useRemote(null);
        server = null;
        CHAT.clear();
        if (TestLog.client) TestLog.c(-1, "settings", "source", "own", "why", "left");
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || CHAT.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        for (String s; (s = CHAT.poll()) != null;) {
            ChatComponentText t = new ChatComponentText(s);
            t.getChatStyle().setColor(EnumChatFormatting.GOLD);
            mc.thePlayer.addChatMessage(t);
        }
    }

    // the server's versions (26.2: CompatClient.hello); the answer with ours goes back from Net
    static void hello(Net.Hello p) {
        server = p;
        String mod = BuildInfo.VERSION, hbm = PortPaths.hbmVersion();
        if (TestLog.client) TestLog.c(-1, "hello", "serverMod", p.mod, "serverNtm", p.hbm, "mod", mod, "ntm", hbm, "sameMod", p.mod.equals(mod),
                "sameNtm", p.hbm.equals(hbm), "protocol", p.protocol, "sameProtocol", p.protocol.equals(Net.PROTOCOL));
        GunFeel.LOGGER.info("Server: NTM Reimagined Arms {} (protocol {}), NTM {}", p.mod, p.protocol, p.hbm);
        if (!p.protocol.equals(Net.PROTOCOL)) {
            CHAT.add("NTM Reimagined Arms: the server's version " + p.mod + " does not play with this one (" + mod + "): protocol " + p.protocol
                    + " here " + Net.PROTOCOL + ".");
        } else if (!p.mod.equals(mod)) {
            CHAT.add("NTM Reimagined Arms: the server has " + p.mod + ", this game " + mod + ".");
        }
        if (!p.hbm.equals(hbm)) CHAT.add("NTM Reimagined Arms: the server has NTM " + p.hbm + ", this game " + hbm + ".");
    }

    static void receive(byte[] data) {
        if (Minecraft.getMinecraft().isIntegratedServerRunning()) {
            if (TestLog.client) TestLog.c(-1, "settings", "source", "own", "why", "this game is the server");
            return;
        }
        try {
            ServerSettings.Snapshot s = ServerSettings.decode(data);
            ServerSettings.useRemote(s);
            if (TestLog.client) {
                int lines = 0;
                for (String l : s.bullets().split("\\R")) if (!l.trim().isEmpty() && !l.trim().startsWith("#")) lines++;
                TestLog.c(-1, "settings", "source", "server", "ruleLines", lines, "serverKeys", s.server().size(), "shapes", s.shapes().size(),
                        "bytes", data.length);
            }
        } catch (Exception e) {
            GunFeel.LOGGER.warn("Could not read the server's settings; going by this game's own", e);
            if (TestLog.client) TestLog.c(-1, "settings", "source", "own", "error", e.toString());
        }
    }
}
