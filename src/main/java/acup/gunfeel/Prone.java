package acup.gunfeel;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import acup.gunfeel.compat.Feature;
import acup.gunfeel.tacz.LivingEntityCrawl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
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
 * crawl_recoil_multiplier). The spread while lying is spreadCrawl (Spread). The client's part (the key, the local
 * player's crawl) is ProneClient; this class is loaded on a dedicated server too.
 *
 * <p>The key is X, not TaCZ's C: NTM's jetpack switch is on C, its magnet on Z. On X vanilla has only the creative
 * hotbar's load (X and a number), which lies down too, as TaCZ's C with the save.
 */
public final class Prone {
    // the server's crawls by player (server thread only); one made for an older player object (before a respawn) is
    // dropped when the new one is met, and the player's is dropped on leaving
    private static final Map<UUID, LivingEntityCrawl> SERVER = new HashMap<>();

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

    // ---- server

    private static LivingEntityCrawl crawlOf(Player player) {
        LivingEntityCrawl c = SERVER.get(player.getUUID());
        return c != null && c.shooter() == player ? c : null;
    }

    public static void receive(ServerPlayer player, boolean crawl) {
        // 进入条件由服务端趴下逻辑统一验证；禁用功能后仍允许退出。
        LivingEntityCrawl c = crawlOf(player);
        if (c == null) SERVER.put(player.getUUID(), c = new LivingEntityCrawl(player));
        c.crawl(crawl);
        // turned down: the client, which lay down already, gets up too
        if (crawl && !c.isCrawling()) stand(player);
        if (TestLog.server) TestLog.s(-1, "prone", "asked", crawl, "lying", c.isCrawling(), "pose", player.getPose().name(),
                "height", player.getBbHeight(), "eye", player.getEyeHeight());
    }

    // tell the client its player stands (the server did not let it lie down, or got it up)
    private static void stand(ServerPlayer player) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, ProneStandPayload.INSTANCE);
    }

    public static boolean serverLying(Player player) {
        LivingEntityCrawl c = crawlOf(player);
        return c != null && c.isCrawling();
    }

    static void registerServer() {
        // another dimension: the client makes a new player, standing, and its crawl starts again; so does the server's
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer sp) {
                LivingEntityCrawl c = crawlOf(sp);
                if (c != null && c.isCrawling()) {
                    c.crawl(false);
                    stand(sp);
                }
            }
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent.class,
                e -> SERVER.remove(e.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener(PlayerTickEvent.Post.class, e -> {
            if (!(e.getEntity() instanceof ServerPlayer sp)) return;
            LivingEntityCrawl c = SERVER.get(sp.getUUID());
            if (c == null) return;
            if (c.shooter() != sp) {
                SERVER.remove(sp.getUUID());
                return;
            }
            boolean was = c.isCrawling();
            c.tickCrawling();
            if (was && !c.isCrawling()) stand(sp);
        });
    }
}
