package acup.gunfeel;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

// Client: the server's settings (ServerSettings) count while connected to another game's server, and are forgotten on
// leaving it. In single player and on a LAN host this game is the server and reads the same files, so they are left
// alone; LAN guests and players on a dedicated server go by the server's.
public final class ServerSettingsClient {
    private ServerSettingsClient() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> {
            ServerSettings.useRemote(null);
            if (TestLog.client) TestLog.c(-1, "settings", "source", "own", "why", "left");
        });
    }

    public static void receive(ServerSettingsPayload p) {
        if (Minecraft.getInstance().hasSingleplayerServer()) {
            if (TestLog.client) TestLog.c(-1, "settings", "source", "own", "why", "this game is the server");
            return;
        }
        try {
            ServerSettings.Snapshot s = ServerSettings.decode(p.data());
            ServerSettings.useRemote(s);
            if (TestLog.client) TestLog.c(-1, "settings", "source", "server", "ruleLines", s.bullets().lines().filter(l -> !l.isBlank() && !l.trim().startsWith("#")).count(),
                    "serverKeys", s.server().size(), "shapes", s.shapes().size(), "bytes", p.data().length);
        } catch (Exception e) {
            GunFeel.LOGGER.warn("Could not read the server's settings; going by this game's own", e);
            if (TestLog.client) TestLog.c(-1, "settings", "source", "own", "error", e.toString());
        }
    }
}
