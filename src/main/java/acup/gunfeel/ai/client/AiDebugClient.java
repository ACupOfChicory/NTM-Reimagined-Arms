package acup.gunfeel.ai.client;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ai.AiCellsPayload;
import acup.gunfeel.ai.Dirs;
import acup.gunfeel.ai.SectionCells;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Draws the cells /armsai debug sends: a square on the floor of each cell, its colour the best cover it has (green:
 * high and stops bullets, blue: high but only hides, yellow: low, grey: none), and a short bar at the edge toward
 * every covered direction, at the height it covers (red: stops bullets, white: only hides). Drawn as the game's
 * per-tick gizmos, so they show with or without F3+B.
 */
public final class AiDebugClient {
    private AiDebugClient() {
    }

    private static @Nullable AiCellsPayload cells;

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> draw());
    }

    public static void receive(AiCellsPayload p) {
        cells = p.count() == 0 ? null : p;
    }

    private static void draw() {
        AiCellsPayload p = cells;
        if (p == null) return;
        BlockPos o = p.origin();
        byte[] off = p.offsets();
        for (int i = 0; i < p.count(); i++) {
            int x = o.getX() + off[3 * i], y = o.getY() + off[3 * i + 1], z = o.getZ() + off[3 * i + 2];
            int cover = p.cover()[i];
            int best = 0;
            boolean bestHard = false;
            for (int d = 0; d < 8; d++) {
                int h = SectionCells.height(cover, d);
                boolean hard = SectionCells.hard(cover, d);
                if (h > best || h == best && hard && !bestHard) {
                    best = h;
                    bestHard = hard;
                }
            }
            int fill = best == 2 ? (bestHard ? 0x6020C040 : 0x602060E0) : best == 1 ? 0x60E0C020 : 0x30808080;
            if ((p.flags()[i] & SectionCells.WATER) != 0) fill = 0x6020C0C0;
            Gizmos.rect(new Vec3(x + 0.08, y + 0.02, z + 0.08), new Vec3(x + 0.92, y + 0.03, z + 0.92), Direction.UP,
                    GizmoStyle.fill(fill));
            for (int d = 0; d < 8; d++) {
                int h = SectionCells.height(cover, d);
                if (h == 0) continue;
                double at = h == 2 ? y + 1.5 : y + 0.5;
                double cx = x + 0.5, cz = z + 0.5;
                Vec3 a = new Vec3(cx + Dirs.DX[d] * 0.3, at, cz + Dirs.DZ[d] * 0.3);
                Vec3 b = new Vec3(cx + Dirs.DX[d] * 0.45, at, cz + Dirs.DZ[d] * 0.45);
                Gizmos.line(a, b, SectionCells.hard(cover, d) ? 0xFFE03030 : 0xFFF0F0F0, 3F);
            }
        }
    }
}
