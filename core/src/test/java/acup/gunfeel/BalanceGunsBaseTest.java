package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

// The gun table's sums without the game: the floor by tier. (Reading the table needs the config folder, GameDirs,
// which core's own tests do not have.)
class BalanceGunsBaseTest {
    @Test
    void floorByTier() {
        assertEquals(1F, BalanceGunsBase.floorOf(null, 5));
        assertEquals(1F, BalanceGunsBase.floorOf(new float[] {0F, 0F, 0.5F}, 5));
        // the pair's floor 0.5 from tier 3: none below it
        assertEquals(0.5F, BalanceGunsBase.floorOf(new float[] {0F, 0F, 0.5F, 3F}, 4));
        assertEquals(1F, BalanceGunsBase.floorOf(new float[] {0F, 0F, 0.5F, 3F}, 2));
        assertEquals("2:4:0.5 5:8:0.5", BalanceGunsBase.tierFloorRules());
    }
}
