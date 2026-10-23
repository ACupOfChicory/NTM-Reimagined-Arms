package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

// server -> client: the server's settings (ServerSettings), packed. Sent when a player joins and to everyone when the
// server's files or shapes change (checked every two seconds: a few file dates, nothing read until one changes).
public record ServerSettingsPayload(byte[] data) implements CustomPacketPayload {
    public static final Type<ServerSettingsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "server_settings"));
    public static final StreamCodec<ByteBuf, ServerSettingsPayload> CODEC = ByteBufCodecs.byteArray(ServerSettings.MAX_BYTES + 4096)
            .map(ServerSettingsPayload::new, ServerSettingsPayload::data);
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ntm-gunfeel");

    private static String sentStamp = "";
    private static ServerSettingsPayload sent;
    private static int ticks;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void join(ServerPlayer player) {
        ServerSettingsPayload p = current();
        if (p != null) PacketDistributor.sendToPlayer(player, p);
    }

    public static void tick(MinecraftServer server) {
        if (++ticks < 40) return;
        ticks = 0;
        if (server.getPlayerList().getPlayerCount() == 0 || stamp().equals(sentStamp)) return;
        ServerSettingsPayload p = current();
        if (p != null) PacketDistributor.sendToAllPlayers(p);
    }

    // the payload for the files as they are now, packed again only when one changed
    static synchronized ServerSettingsPayload current() {
        String now = stamp();
        if (sent != null && now.equals(sentStamp)) return sent;
        try {
            Path rules = FMLPaths.CONFIGDIR.get().resolve(BulletRules.FILE);
            String bullets = Files.exists(rules) ? Files.readString(rules) : "";
            sent = new ServerSettingsPayload(ServerSettings.encodeFitting(
                    new ServerSettings.Snapshot(bullets, ReaimTimes.values(), PassableClip.extraShapes())));
            sentStamp = now;
            return sent;
        } catch (Exception e) {
            LOGGER.warn("Could not pack the server settings for the players", e);
            return sent;
        }
    }

    private static String stamp() {
        Path dir = FMLPaths.CONFIGDIR.get();
        return modified(dir.resolve(BulletRules.FILE)) + "|" + modified(dir.resolve(ReaimTimes.FILE)) + "|" + PassableClip.localVersion();
    }

    private static long modified(Path f) {
        try {
            return Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : 0L;
        } catch (Exception e) {
            return -1L;
        }
    }
}
