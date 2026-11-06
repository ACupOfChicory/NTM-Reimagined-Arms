package acup.gunfeel.smoke;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The server's side of the smoke's network: a cloud's events go to the players watching the chunk it was born in;
 * a player who starts watching that chunk later (joined, came near, the chunk loaded) gets the cloud's {@code Spawn}
 * and catches the simulation up from its birth; every two seconds the cloud's hash goes out, and a client whose copy
 * differs asks for the grid.
 */
public final class SmokeNet {
    /** Ticks between the hash checks of one cloud. */
    public static final int CHECK_EVERY = 40;
    private static final Map<UUID, Long> LAST_RESYNC = new HashMap<>();

    private SmokeNet() {
    }

    /** The client-bound packets on a dedicated server (the client registers them with its handlers). */
    public static void payloads(net.neoforged.neoforge.network.registration.PayloadRegistrar r) {
        r.playToClient(SmokePayloads.Spawn.TYPE, SmokePayloads.Spawn.CODEC);
        r.playToClient(SmokePayloads.End.TYPE, SmokePayloads.End.CODEC);
        r.playToClient(SmokePayloads.Disturb.TYPE, SmokePayloads.Disturb.CODEC);
        r.playToClient(SmokePayloads.Check.TYPE, SmokePayloads.Check.CODEC);
        r.playToClient(SmokePayloads.Grid.TYPE, SmokePayloads.Grid.CODEC);
    }

    static ChunkPos chunk(SmokeClouds.Cloud c) {
        return ChunkPos.containing(c.origin);
    }

    static void spawned(ServerLevel level, SmokeClouds.Cloud c) {
        PacketDistributor.sendToPlayersTrackingChunk(level, chunk(c), SmokePayloads.Spawn.of(c));
    }

    public static void ended(ServerLevel level, SmokeClouds.Cloud c) {
        PacketDistributor.sendToPlayersTrackingChunk(level, chunk(c), new SmokePayloads.End(c.id));
    }

    static void disturbed(ServerLevel level, SmokeClouds.Cloud c, float x, float y, float z, float radius) {
        PacketDistributor.sendToPlayersTrackingChunk(level, chunk(c), new SmokePayloads.Disturb(c.id, x, y, z, radius, c.grid.age()));
    }

    // after the step: the hash of the clouds whose turn it is (spread over the ticks by id)
    static void checks(ServerLevel level, SmokeClouds all) {
        for (SmokeClouds.Cloud c : all.clouds()) {
            if ((c.grid.age() + c.id * 7) % CHECK_EVERY != 0) continue;
            PacketDistributor.sendToPlayersTrackingChunk(level, chunk(c), new SmokePayloads.Check(c.id, c.grid.age(), c.grid.hash()));
        }
    }

    public static void watched(ChunkWatchEvent.Sent e) {
        SmokeClouds all = SmokeClouds.get(e.getLevel());
        if (all == null || all.isEmpty()) return;
        for (SmokeClouds.Cloud c : all.clouds()) if (chunk(c).equals(e.getPos())) PacketDistributor.sendToPlayer(e.getPlayer(), SmokePayloads.Spawn.of(c));
    }

    public static void unwatched(ChunkWatchEvent.UnWatch e) {
        SmokeClouds all = SmokeClouds.get(e.getLevel());
        if (all == null || all.isEmpty()) return;
        for (SmokeClouds.Cloud c : all.clouds()) if (chunk(c).equals(e.getPos())) PacketDistributor.sendToPlayer(e.getPlayer(), new SmokePayloads.End(c.id));
    }

    /** A client asked for a cloud's grid; at most once a second a player. */
    public static void resync(ServerPlayer player, SmokePayloads.Resync p) {
        SmokeClouds all = SmokeClouds.get(player.level());
        SmokeClouds.Cloud c = all == null ? null : all.cloud(p.id());
        if (c == null) {
            PacketDistributor.sendToPlayer(player, new SmokePayloads.End(p.id()));
            return;
        }
        long now = player.level().getGameTime();
        Long last = LAST_RESYNC.get(player.getUUID());
        if (last != null && now - last < 20 && now >= last) return;
        LAST_RESYNC.put(player.getUUID(), now);
        if (LAST_RESYNC.size() > 256) LAST_RESYNC.clear();
        try {
            PacketDistributor.sendToPlayer(player, new SmokePayloads.Grid(c.id, encode(c.grid)));
        } catch (IOException ex) {
            // a grid that does not pack is not sent: the client keeps its copy
        }
    }

    public static byte[] encode(SmokeGrid g) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            g.write(out);
        }
        return bytes.toByteArray();
    }

    public static void decode(SmokeGrid g, byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
            g.read(in);
        }
    }
}
