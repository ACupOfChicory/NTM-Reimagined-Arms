package acup.gunfeel;

import java.util.Map;
import java.util.WeakHashMap;

import acup.gunfeel.compat.Feature;
import acup.gunfeel.tacz.CrawlKey;
import acup.gunfeel.tacz.LivingEntityCrawl;
import acup.gunfeel.tacz.LocalPlayerCrawl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Lying down on the prone key (X), as TaCZ's crawl (acup.gunfeel.tacz: CrawlKey, LocalPlayerCrawl, LivingEntityCrawl):
 * the player takes vanilla's crawling pose (the one under a trapdoor: a 0.6 box, eyes at 0.4, a third of the walking
 * speed), with an NTM gun or, by proneWithoutGun, without one. The client lies down and tells the server, which checks
 * the same rules and holds the pose; either side stands up when a rule no longer holds (off the ground, jumping, in
 * water, riding, asleep, gliding, dead, a spectator).
 *
 * <p>Server keys (ntm-gunfeel-server.properties): prone, proneWithoutGun, proneMaxUp and proneMaxDown (how far the
 * view turns up and down while lying, TaCZ's CrawlMaxUpAngle and CrawlMaxDownAngle). Client keys: proneHold (hold the
 * key instead of a press each to lie down and get up), proneRecoil (all recoil while lying, TaCZ's
 * crawl_recoil_multiplier). The spread while lying is spreadCrawl (Spread).
 *
 * <p>The key is X, not TaCZ's C: NTM's jetpack switch is on C, its magnet on Z. On X vanilla has only the creative
 * hotbar's load (X and a number), which lies down too, as TaCZ's C with the save.
 */
public final class Prone {
    // the local player's crawl, made again for a new player object (respawn, another dimension)
    private static LocalPlayerCrawl local;
    private static final Map<Player, LivingEntityCrawl> SERVER = new WeakHashMap<>();

    private Prone() {
    }

    // ---- settings

    /** Lying down is allowed (the server's file, or its copy on a connected client). */
    public static boolean enabled() {
        return Feature.PRONE.on() && ReaimTimes.flag("prone", true);
    }

    public static boolean withoutGun() {
        return ReaimTimes.flag("proneWithoutGun", true);
    }

    public static float maxUp() {
        return Math.clamp(ReaimTimes.number("proneMaxUp", 45F), 0F, 90F);
    }

    public static float maxDown() {
        return Math.clamp(ReaimTimes.number("proneMaxDown", 30F), 0F, 90F);
    }

    /** Lying as TaCZ tells it: the crawling pose out of water (vanilla's crawl under a trapdoor too). */
    public static boolean lying(LivingEntity e) {
        return e != null && !e.isSwimming() && e.getPose() == Pose.SWIMMING;
    }

    // ---- recoil (client)

    /** The recoil multiplier of the gun model and NTM's recoil animation: power armour and lying down. */
    public static float recoil(Player player) {
        return PowerArmor.recoil(player) * factor(Feature.PRONE.on() && lying(player), Config.proneRecoil);
    }

    /**
     * The camera's: the same, but on a server that sets a camera minimum (cameraScaleMin) together no lower than half,
     * as powerArmorRecoil alone.
     */
    public static float cameraRecoil(Player player) {
        float f = recoil(player);
        return f < 1F && ReaimTimes.number("cameraScaleMin", 0F) > 0F ? Math.max(0.5F, f) : f;
    }

    static float factor(boolean lying, float key) {
        if (!lying || Float.isNaN(key)) return 1F;
        return Math.clamp(key, 0F, 1F);
    }

    // ---- client

    public static LocalPlayerCrawl local(LocalPlayer player) {
        if (player == null) return null;
        if (local == null || local.player() != player) local = new LocalPlayerCrawl(player);
        return local;
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

    // ---- server

    public static void receive(ServerPlayer player, boolean crawl) {
        // 进入条件由服务端趴下逻辑统一验证；禁用功能后仍允许退出。
        LivingEntityCrawl c = SERVER.computeIfAbsent(player, LivingEntityCrawl::new);
        c.crawl(crawl);
        if (TestLog.server) TestLog.s(-1, "prone", "asked", crawl, "lying", c.isCrawling(), "pose", player.getPose().name(),
                "height", player.getBbHeight(), "eye", player.getEyeHeight());
    }

    public static boolean serverLying(Player player) {
        LivingEntityCrawl c = SERVER.get(player);
        return c != null && c.isCrawling();
    }

    static void registerServer() {
        NeoForge.EVENT_BUS.addListener(PlayerTickEvent.Post.class, e -> {
            if (e.getEntity() instanceof ServerPlayer sp) {
                LivingEntityCrawl c = SERVER.get(sp);
                if (c != null) c.tickCrawling();
            }
        });
    }
}
