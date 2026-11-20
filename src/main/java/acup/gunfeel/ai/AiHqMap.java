package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;

/**
 * A side's staff's map of the land (stage 7, C1): core's AiHqMapBase, made from what a side's report (AiIntel) gathers
 * here: its soldiers' middle, the enemies' places as the side knows them, the level's tiles (AiTerrain). The staff's
 * ways go over it (AiCommander).
 */
public final class AiHqMap extends AiHqMapBase {
    private AiHqMap() {
    }

    public static void forget(ServerLevel level) {
        forgetLevel(level);
    }

    /** the side's map as last made, null before its first */
    static @Nullable HqGrid of(ServerLevel level, String team) {
        return grid(level, team);
    }

    /**
     * At a side's report (server thread): its map made again round its soldiers' middle, off the thread; the old one
     * read till then. One at a time a side. The enemies' places as the side knows them: each may be anywhere in its
     * circle, a cell seen as much as the part of the circle that sees it (HqGrid.sight).
     */
    static void report(ServerLevel level, String team, List<AiBot> own, List<AiIntel.Group> groups) {
        if (own.isEmpty() || !AiTerrain.ON) return;
        Side side = side(level, team);
        if (side.building) return;
        double mx = 0, mz = 0;
        for (AiBot b : own) {
            mx += b.mob.getX() / own.size();
            mz += b.mob.getZ() / own.size();
        }
        List<double[]> foes = new ArrayList<>();
        for (AiIntel.Group g : groups) if (g.alive()) foes.add(new double[] {g.x(), g.z(), g.r()});
        AiTerrain terrain = AiTerrain.of(level);
        submit(side, terrain, mx, mz, foes);
    }
}
