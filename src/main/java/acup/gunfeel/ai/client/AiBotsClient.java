package acup.gunfeel.ai.client;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ai.AiBotsPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Draws what /armsai debug says the AI mobs round the player have in mind: a line of text over each (what it does,
 * its rounds in the gun and besides), a yellow line from its eyes to where it watches, its path in blue, its cover
 * cell in green and its peek in cyan. Per-tick gizmos, with or without F3+B.
 */
public final class AiBotsClient {
    private AiBotsClient() {
    }

    private static @Nullable AiBotsPayload bots;

    public static void receive(AiBotsPayload p) {
        bots = p.bots().isEmpty() ? null : p;
    }

    static void draw() {
        AiBotsPayload p = bots;
        Minecraft mc = Minecraft.getInstance();
        if (p == null || mc.level == null) return;
        for (AiBotsPayload.Bot b : p.bots()) {
            Entity e = mc.level.getEntity(b.entity());
            if (e != null) {
                Gizmos.billboardTextOverMob(e, 0, b.text(), 0xFFFFE080, 0.6F);
                if (!Double.isNaN(b.wx())) Gizmos.line(e.getEyePosition(), new Vec3(b.wx(), b.wy(), b.wz()), 0xC0F0D020, 2F);
            }
            long[] path = b.path();
            for (int i = 1; i < path.length; i++)
                Gizmos.line(centre(path[i - 1]), centre(path[i]), 0xC03070F0, 2F);
            if (b.spot() != Long.MIN_VALUE) box(b.spot(), 0x6020C040);
            if (b.peek() != Long.MIN_VALUE && b.peek() != b.spot()) box(b.peek(), 0x6020C0C0);
        }
    }

    private static Vec3 centre(long cell) {
        return new Vec3(BlockPos.getX(cell) + 0.5, BlockPos.getY(cell) + 0.1, BlockPos.getZ(cell) + 0.5);
    }

    private static void box(long cell, int colour) {
        int x = BlockPos.getX(cell), y = BlockPos.getY(cell), z = BlockPos.getZ(cell);
        Gizmos.cuboid(new AABB(x + 0.2, y, z + 0.2, x + 0.8, y + 0.15, z + 0.8), GizmoStyle.fill(colour));
    }
}
