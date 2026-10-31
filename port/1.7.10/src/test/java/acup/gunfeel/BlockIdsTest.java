package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

// 1.7.10 block ids under the names the 26.2 rules use (BlockIds, ntmgunfeel-ids-1.7.10.txt)
class BlockIdsTest {
    @Test
    void ntmTilePrefixGoes() {
        assertEquals("hbm:steel_wall", BlockIds.canonical("hbm:tile.steel_wall"));
        assertEquals("minecraft:stone", BlockIds.canonical("stone"));
        assertEquals("minecraft:air", BlockIds.canonical(null));
        assertEquals("hbm:steel_wall", BlockIds.id("hbm:tile.steel_wall", 2));
    }

    @Test
    void metadataNamesOf26_2() {
        assertEquals("minecraft:oak_planks", BlockIds.id("minecraft:planks", 0));
        assertEquals("minecraft:birch_planks", BlockIds.id("minecraft:planks", 2));
        assertEquals("minecraft:oak_slab", BlockIds.id("minecraft:wooden_slab", 8));
        assertEquals(Arrays.asList("minecraft:birch_planks", "minecraft:planks", "minecraft:planks:2"), BlockIds.names("minecraft:planks", 2));
        assertEquals(Arrays.asList("hbm:sandbags", "hbm:sandbags:0"), BlockIds.names("hbm:tile.sandbags", 0));
    }

    @Test
    void stateAsTheLogWritesIt() {
        assertEquals("hbm:steel_wall[meta=2]", BlockIds.state("hbm:tile.steel_wall", 2));
        assertEquals("minecraft:glass", BlockIds.state("minecraft:glass", 0));
    }

    @Test
    void fileParses() {
        assertEquals("b", BlockIds.parse(Arrays.asList("# c", "", "x:tile.a/3 = b")).get("x:a/3"));
        // the shipped file: metas 0-15
        for (String k : BlockIds.aliases().keySet()) {
            int s = k.indexOf('/');
            if (s < 0) continue;
            int m = Integer.parseInt(k.substring(s + 1));
            assertTrue(m >= 0 && m < 16, k);
        }
        assertTrue(BlockIds.aliases().size() > 100);
    }
}
