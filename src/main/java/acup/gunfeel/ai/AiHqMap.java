package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;

/**
 * A side's staff's map of the land (stage 7, C1): an {@link HqGrid} round the side, made again off the server thread
 * at each of the side's reports (AiIntel) from the lie of the land (AiTerrain's tiles: heights, leaves at a standing look's height, water) and
 * from where the side thinks its enemies are (what they see). The staff's ways go over it (AiCommander).
 */
public final class AiHqMap {
    /** the time the maps took to make, off the server thread, ns; maps made */
    static final AtomicLong buildNanos = new AtomicLong(), built = new AtomicLong();

    private static final class Side {
        volatile @Nullable HqGrid grid;
        volatile boolean building;
    }

    private static final Map<ServerLevel, Map<String, Side>> LEVELS = new WeakHashMap<>();

    private AiHqMap() {
    }

    public static void forget(ServerLevel level) {
        LEVELS.remove(level);
    }

    /** the side's map as last made, null before its first */
    static @Nullable HqGrid of(ServerLevel level, String team) {
        Map<String, Side> sides = LEVELS.get(level);
        Side s = sides == null ? null : sides.get(team);
        return s == null ? null : s.grid;
    }

    /**
     * At a side's report (server thread): its map made again round its soldiers' middle, off the thread; the old one
     * read till then. One at a time a side. The enemies' places as the side knows them: each may be anywhere in its
     * circle, a cell seen as much as the part of the circle that sees it (HqGrid.sight).
     */
    static void report(ServerLevel level, String team, List<AiBot> own, List<AiIntel.Group> groups) {
        if (own.isEmpty() || !AiTerrain.ON) return;
        Side side = LEVELS.computeIfAbsent(level, k -> new HashMap<>()).computeIfAbsent(team, t -> new Side());
        if (side.building) return;
        double mx = 0, mz = 0;
        for (AiBot b : own) {
            mx += b.mob.getX() / own.size();
            mz += b.mob.getZ() / own.size();
        }
        List<double[]> foes = new ArrayList<>();
        for (AiIntel.Group g : groups) if (g.alive()) foes.add(new double[] {g.x(), g.z(), g.r()});
        AiTerrain terrain = AiTerrain.of(level);
        double cx = mx, cz = mz;
        side.building = true;
        try {
            AiThreads.submit(() -> {
                try {
                    long t0 = System.nanoTime();
                    HqGrid grid = build(terrain, cx, cz);
                    for (double[] f : foes) grid.sight(f[0], f[1], f[2]);
                    side.grid = grid;
                    buildNanos.addAndGet(System.nanoTime() - t0);
                    built.incrementAndGet();
                } finally {
                    side.building = false;
                }
            });
        } catch (RuntimeException x) {
            side.building = false;
            throw x;
        }
    }

    /** the cells from the tiles kept: four coarse cells of the tiles to a cell; one with none of them kept is not known */
    static HqGrid build(AiTerrain terrain, double cx, double cz) {
        HqGrid g = HqGrid.around(cx, cz);
        int q = TerrainTile.CELL;
        for (int i = 0; i < HqGrid.N; i++)
            for (int k = 0; k < HqGrid.N; k++) {
                int bx = g.x0 + i * HqGrid.CELL, bz = g.z0 + k * HqGrid.CELL;
                double h = 0, crown = 0, wet = 0, deep = 0;
                int n = 0;
                for (int a = 0; a < HqGrid.CELL; a += q)
                    for (int b = 0; b < HqGrid.CELL; b += q) {
                        TerrainTile t = terrain.tile(bx + a, bz + b);
                        if (t == null) continue;
                        h += t.mid(bx + a, bz + b);
                        // leaves a look standing on the ground goes through (underwood, low crowns), not the
                        // crowns one sees under
                        crown += t.brushed(bx + a, bz + b);
                        wet += t.wet(bx + a, bz + b);
                        deep += t.deep(bx + a, bz + b);
                        n++;
                    }
                if (n > 0) g.set(i, k, h / n, crown / n, wet / n, deep / n);
            }
        return g;
    }

    /** /armsai info */
    static String info() {
        long n = built.get();
        return String.format(java.util.Locale.ROOT, "staff's maps: %d made, %.2f ms each (off the server thread)", n, n == 0 ? 0 : buildNanos.get() / 1e6 / n);
    }
}
