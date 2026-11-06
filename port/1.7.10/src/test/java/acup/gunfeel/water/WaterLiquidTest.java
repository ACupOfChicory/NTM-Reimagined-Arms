package acup.gunfeel.water;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

// port: 1.7.10's liquid heights as WaterPath reads them: a block's own height from its metadata, as
// BlockLiquid.getLiquidHeightPercent has it (a source 8/9, the last step of a flow 1/9; falling, meta 8 and over, as
// full as a source)
class WaterLiquidTest {
    @Test
    void aSourceIsEightNinthsAndEachStepOfAFlowANinthLess() {
        assertEquals(8 / 9F, WaterPath.ownHeight(0), 1e-6);
        for (int meta = 1; meta < 8; meta++) assertEquals((8 - meta) / 9F, WaterPath.ownHeight(meta), 1e-6, "meta " + meta);
    }

    @Test
    void fallingLiquidIsAsFullAsASource() {
        for (int meta = 8; meta < 16; meta++) assertEquals(8 / 9F, WaterPath.ownHeight(meta), 1e-6, "meta " + meta);
    }
}
