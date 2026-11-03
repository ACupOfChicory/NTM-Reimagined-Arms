package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

// port: NTM CE's baked deco blocks whose collision box is more than their model (ShapesScenario.BLOCKS): the table has
// them, and some ray across the block misses the model, where the box alone would stop it
class CeDecoCellsTest {
    @Test
    void decoBlocksAreMetByTheirModel() throws Exception {
        Map<String, long[]> t = ModelCells.table();
        StringBuilder diag = new StringBuilder();
        for (String id : new String[] {"hbm:pole_satellite_receiver", "hbm:radiobox", "hbm:steel_roof"}) {
            int states = 0, open = 0;
            for (Map.Entry<String, long[]> e : t.entrySet()) {
                if (!e.getKey().startsWith(id + "#")) continue;
                states++;
                int miss = 0, all = 0;
                for (int i = 0; i < 16; i++) for (int j = 0; j < 16; j++) {
                    double a = (i + .5) / 16, b = (j + .5) / 16;
                    BlockRay.Hit in = BlockRay.clip(new double[] {0, 0, 0, 1, 1, 1}, 1, a, b, -1, a, b, 2, 0, 0, 0);
                    all++;
                    if (FenceCells.march(e.getValue(), in, 0, 0, 3, 64) == null) miss++;
                }
                diag.append(e.getKey()).append(' ').append(miss).append('/').append(all).append('\n');
                if (miss > 0) open++;
            }
            assertTrue(states > 0 && open > 0, id + ": " + states + " states, " + open + " with a ray through");
        }
        java.nio.file.Files.write(java.nio.file.Paths.get("build/deco-cells-diag.txt"), diag.toString().getBytes("UTF-8"));
    }
}
