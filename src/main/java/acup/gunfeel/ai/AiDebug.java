package acup.gunfeel.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * /armsai debug: the players who watch the tactical map. Twice a second each gets the cells around them (the map
 * keeps building them while someone watches); the client draws them ({@code AiDebugClient}).
 */
public final class AiDebug {
    private AiDebug() {
    }

    /** player -> radius across, in blocks */
    private static final Map<UUID, Integer> WATCHERS = new HashMap<>();
    static final int HEIGHT = 6;

    public static boolean toggle(ServerPlayer player, int radius) {
        if (WATCHERS.remove(player.getUUID()) != null) {
            PacketDistributor.sendToPlayer(player, new AiCellsPayload(player.blockPosition(), new byte[0], new int[0], new byte[0]));
            PacketDistributor.sendToPlayer(player, new AiBotsPayload(java.util.List.of()));
            return false;
        }
        WATCHERS.put(player.getUUID(), radius);
        return true;
    }

    public static void forget(UUID player) {
        WATCHERS.remove(player);
    }

    public static void tick(ServerLevel level) {
        if (WATCHERS.isEmpty() || level.getGameTime() % 10 != 0) return;
        for (ServerPlayer p : level.players()) {
            Integer radius = WATCHERS.get(p.getUUID());
            if (radius == null) continue;
            TacticalMap map = TacticalMap.of(level);
            BlockPos at = p.blockPosition();
            map.want(at.getX(), at.getY(), at.getZ(), radius + 16, HEIGHT + 16);
            PacketDistributor.sendToPlayer(p, cells(map, at, radius));
            PacketDistributor.sendToPlayer(p, bots(level, at, radius + 32));
        }
    }

    static AiBotsPayload bots(ServerLevel level, BlockPos at, int radius) {
        java.util.List<AiBotsPayload.Bot> out = new java.util.ArrayList<>();
        for (AiBot b : AiBots.all(level)) {
            if (out.size() >= AiBotsPayload.LIMIT) break;
            if (b.mob.blockPosition().distManhattan(at) > radius * 2) continue;
            String text = b.active() ? (b.brain instanceof AiSoldier s ? s.describe(b) : "driven") : "asleep";
            net.minecraft.world.phys.Vec3 w = b.brain instanceof AiSoldier s ? s.watch() : null;
            CoverQuery.Spot spot = b.brain instanceof AiSoldier s ? s.spot() : null;
            out.add(new AiBotsPayload.Bot(b.mob.getId(), text, w == null ? Double.NaN : w.x, w == null ? Double.NaN : w.y,
                    w == null ? Double.NaN : w.z, spot == null ? Long.MIN_VALUE : spot.cell(), spot == null ? Long.MIN_VALUE : spot.peek(),
                    b.mover.path()));
        }
        return new AiBotsPayload(out);
    }

    static AiCellsPayload cells(TacticalMap map, BlockPos at, int radius) {
        int n = 0;
        byte[] offsets = new byte[3 * 1024];
        int[] cover = new int[1024];
        byte[] flags = new byte[1024];
        int x0 = at.getX() - radius, x1 = at.getX() + radius, z0 = at.getZ() - radius, z1 = at.getZ() + radius;
        int y0 = at.getY() - HEIGHT, y1 = at.getY() + HEIGHT;
        for (int sx = x0 >> 4; sx <= x1 >> 4; sx++)
            for (int sz = z0 >> 4; sz <= z1 >> 4; sz++)
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    SectionCells c = map.cells(SectionPos.asLong(sx, sy, sz));
                    if (c == null) continue;
                    for (int i = 0; i < c.count(); i++) {
                        int x = (sx << 4) + c.x(i), y = (sy << 4) + c.y(i), z = (sz << 4) + c.z(i);
                        if (x < x0 || x > x1 || y < y0 || y > y1 || z < z0 || z > z1) continue;
                        if (n == AiCellsPayload.LIMIT) break;
                        if (n == cover.length) {
                            offsets = java.util.Arrays.copyOf(offsets, n * 6);
                            cover = java.util.Arrays.copyOf(cover, n * 2);
                            flags = java.util.Arrays.copyOf(flags, n * 2);
                        }
                        offsets[3 * n] = (byte) (x - at.getX());
                        offsets[3 * n + 1] = (byte) (y - at.getY());
                        offsets[3 * n + 2] = (byte) (z - at.getZ());
                        cover[n] = c.cover(i);
                        flags[n] = (byte) c.flags(i);
                        n++;
                    }
                }
        return new AiCellsPayload(at, java.util.Arrays.copyOf(offsets, 3 * n), java.util.Arrays.copyOf(cover, n),
                java.util.Arrays.copyOf(flags, n));
    }
}
