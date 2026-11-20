package acup.gunfeel.ai;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.LevelMap;

/**
 * A side's staff's map of the land (stage 7, C1): an {@link HqGrid} round the side, made again off the server thread
 * at each of the side's reports from the lie of the land (the terrain's tiles: heights, leaves at a standing look's
 * height, water) and from where the side thinks its enemies are (what they see). The staff's ways go over it.
 *
 * <p>Kept per level (LevelMap: the level is the version's object) and per side. The version gathers what a report
 * needs (its soldiers' middle, the enemies' circles, the level's tiles) and hands it to {@link #submit}; 26.2's AiHqMap.
 */
class AiHqMapBase {
    /** the time the maps took to make, off the server thread, ns; maps made */
    static final AtomicLong buildNanos = new AtomicLong(), built = new AtomicLong();

    static final class Side {
        volatile @Nullable HqGrid grid;
        volatile boolean building;
    }

    private static final LevelMap<Map<String, Side>> LEVELS = new LevelMap<>();

    AiHqMapBase() {
    }

    /** everything kept of the level gone */
    static void forgetLevel(Object level) {
        LEVELS.remove(level);
    }

    /** the side's map as last made, null before its first */
    static @Nullable HqGrid grid(Object level, String team) {
        Map<String, Side> sides = LEVELS.get(level);
        Side s = sides == null ? null : sides.get(team);
        return s == null ? null : s.grid;
    }

    /** the side's place on the level, made the first time it is asked for */
    static Side side(Object level, String team) {
        return LEVELS.computeIfAbsent(level, k -> new HashMap<>()).computeIfAbsent(team, t -> new Side());
    }

    /**
     * The side's map made again round (cx, cz) off the server thread, the old one read till then; the caller has
     * checked it is not building already. The enemies' places as the side knows them, {x, z, r} each: each may be
     * anywhere in its circle, a cell seen as much as the part of the circle that sees it (HqGrid.sight).
     */
    static void submit(Side side, Skyline.Tiles terrain, double cx, double cz, List<double[]> foes) {
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
    static HqGrid build(Skyline.Tiles terrain, double cx, double cz) {
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
