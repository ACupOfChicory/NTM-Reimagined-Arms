package acup.gunfeel;

import acup.gunfeel.compat.Feature;
import acup.gunfeel.tacz.CrawlKey;
import acup.gunfeel.tacz.LocalPlayerCrawl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

// Client: the prone key and the local player's crawl (Prone has the rules and the server's part).
public final class ProneClient {
    // the local player's crawl, made again for a new player object (respawn, another dimension)
    private static LocalPlayerCrawl local;

    private ProneClient() {
    }

    public static LocalPlayerCrawl local(LocalPlayer player) {
        if (player == null) return null;
        if (local == null || local.player() != player) local = new LocalPlayerCrawl(player);
        return local;
    }

    // the server has the player standing: up, without telling it back
    public static void serverStood() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && local != null && local.player() == player) local.serverStood();
    }

    public static boolean localLying() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && local != null && local.player() == player && local.isCrawling();
    }

    public static void send(CustomPacketPayload p) {
        try {
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(p);
        } catch (RuntimeException ex) {
            // not connected to a server
        }
    }

    static void registerClient(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> {
            if (!Feature.AMMO_CHOICE.on() && !Feature.ARMS.on()) e.registerCategory(AmmoLockKey.CATEGORY);
            e.register(CrawlKey.CRAWL_KEY);
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> clientTick());
        // leaving the world: the last player and its level are not kept
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut.class, e -> local = null);
    }

    private static void clientTick() {
        LocalPlayerCrawl crawl = local(Minecraft.getInstance().player);
        if (crawl == null) {
            while (CrawlKey.CRAWL_KEY.consumeClick()) {
            }
            return;
        }
        crawl.tickCrawl();
        while (CrawlKey.CRAWL_KEY.consumeClick()) CrawlKey.onCrawlPress();
        CrawlKey.onCrawlHoldingInput();
    }
}
