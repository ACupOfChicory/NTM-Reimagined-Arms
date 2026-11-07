package acup.gunfeel.gas;

import static acup.gunfeel.smoke.SmokeGrid.OX;
import static acup.gunfeel.smoke.SmokeGrid.OY;
import static acup.gunfeel.smoke.SmokeGrid.OZ;
import static acup.gunfeel.smoke.SmokeGrid.SX;
import static acup.gunfeel.smoke.SmokeGrid.SY;
import static acup.gunfeel.smoke.SmokeGrid.SZ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import acup.gunfeel.smoke.SmokeGrid;
import acup.gunfeel.smoke.SmokeKind;

// The gases in the smoke's grid: how far each spreads, that a heavy one sinks into a pit and a neutral one does not, that the
// walls hold it and that every kind is found again by its id.
class GasGridTest {
    // ground under the burst cell
    private static final SmokeGrid.Mask FIELD = (x, y, z) -> y < OY;

    private static SmokeGrid grid(SmokeKind kind, SmokeGrid.Mask mask) {
        SmokeGrid g = new SmokeGrid(kind, 5, 1F, 0F, 1F);
        g.scan(mask);
        g.place();
        return g;
    }

    private static void run(SmokeGrid g, int ticks) {
        for (int i = 0; i < ticks; i++) g.step();
    }

    // blocks along +x from the burst cell with C above a level
    private static int reach(SmokeGrid g, float level) {
        int r = 0;
        for (int x = OX; x < SX; x++) if (g.density(x, OY, OZ) > level) r = x - OX;
        return r;
    }

    private static int cells(SmokeGrid g, float level) {
        int n = 0;
        for (int i = 0; i < SmokeGrid.CELLS; i++) if (g.density(i) > level) n++;
        return n;
    }

    @Test
    void kindsAreFoundByIdAndName() {
        for (GasKind k : GasKind.values()) {
            assertEquals(k.smoke().id(), SmokeKind.byId(k.smoke().id()).id());
            assertEquals(k, GasKind.of(SmokeKind.byId(k.smoke().id())));
            assertEquals(k.smoke(), SmokeKind.byName(k.key));
            assertTrue(k.smoke().id() > 0);
        }
        assertNull(GasKind.of(SmokeKind.SMOKE));
        assertNotNull(SmokeKind.byName("smoke"));
    }

    @Test
    void everyGasReachesAboutItsRadiusInTheOpen() {
        // the owner's radii: chlorine 5, phosgene 4.5, mustard 4, acid 5 (where the gas is still thick, above C 0.5)
        float[] want = {5F, 4.5F, 4F, 5F};
        for (GasKind k : GasKind.values()) {
            SmokeGrid g = grid(k.smoke(), FIELD);
            int peak = 0;
            float core = 0F;
            for (int t = 0; t < k.smoke().maxAge(); t++) {
                g.step();
                if (t == 100) peak = reach(g, 0.5F);
                core = Math.max(core, g.density(OX, OY, OZ));
            }
            System.out.println("gas " + k.key + " reach(C>0.5)=" + peak + " cells(C>0.5)@100=? core max " + core);
            assertTrue(Math.abs(peak - want[k.ordinal()]) <= 1.5F, k.key + " reaches " + peak + " blocks, wants about " + want[k.ordinal()]);
        }
    }

    @Test
    void theCloudHoldsThenThinsToNothing() {
        for (GasKind k : GasKind.values()) {
            SmokeGrid g = grid(k.smoke(), FIELD);
            int holdEnd = (int) (k.holdSeconds * 20);
            run(g, holdEnd - 1);
            assertTrue(cells(g, 0.5F) > 20, k.key + " thinned before its hold was over");
            run(g, k.smoke().maxAge());
            assertTrue(g.done(), k.key + " never went");
        }
    }

    @Test
    void phosgeneIsAlmostClearAndTheOthersAreNot() {
        assertTrue(GasKind.PHOSGENE.alpha <= 0.25F);
        for (GasKind k : GasKind.values()) {
            float oneBlock = 1F - (float) Math.exp(-k.smoke().extinction());
            assertEquals(k.alpha, oneBlock, 0.001F, k.key);
        }
        assertTrue(GasKind.MUSTARD.alpha > GasKind.CHLORINE.alpha);
    }

    // a pit 3 deep and 5 wide beside the burst, ground level with the burst cell
    private static final SmokeGrid.Mask PIT = (x, y, z) -> y < OY - 3 || y < OY && !(x >= OX + 3 && x <= OX + 7 && Math.abs(z - OZ) <= 2);

    private static float inPit(SmokeGrid g) {
        float sum = 0F;
        for (int y = OY - 3; y < OY; y++) for (int z = OZ - 2; z <= OZ + 2; z++) for (int x = OX + 3; x <= OX + 7; x++) sum += g.density(x, y, z);
        return sum;
    }

    @Test
    void aHeavyGasFlowsDownIntoAPitAndAnAcidDoesNotAsMuch() {
        SmokeGrid chlorine = grid(GasKind.CHLORINE.smoke(), PIT), acid = grid(GasKind.ACID.smoke(), PIT);
        run(chlorine, 200);
        run(acid, 200);
        float c = inPit(chlorine), a = inPit(acid);
        System.out.println("pit: chlorine " + c + " acid " + a);
        assertTrue(c > 5F, "chlorine in the pit: " + c);
        assertTrue(c > a * 1.2F, "chlorine " + c + " against acid " + a);
    }

    @Test
    void aHeavyGasGoesDownFasterThanUpAndASmokeNot() {
        SmokeKind heavy = GasKind.CHLORINE.smoke();
        assertEquals(-0.5F, heavy.rise());
        assertTrue(heavy.lift() < 0F);
        assertEquals(0F, GasKind.ACID.smoke().rise());
        // the grid's up is (1 + rise) and down (1 - rise): 0.5 and 1.5 of the plain spread
        assertEquals(0.5F, 1F + heavy.rise());
        assertEquals(1.5F, 1F - heavy.rise());
    }

    @Test
    void aHeavyGasSettlesLowerThanAnAcid() {
        // the mean height of the gas after the release, in an open box with a floor
        float[] h = new float[2];
        GasKind[] pair = {GasKind.CHLORINE, GasKind.ACID};
        for (int i = 0; i < 2; i++) {
            SmokeGrid g = grid(pair[i].smoke(), FIELD);
            run(g, 160);
            double sum = 0, mass = 0;
            for (int y = 0; y < SY; y++) for (int z = 0; z < SZ; z++) for (int x = 0; x < SX; x++) {
                float d = g.density(x, y, z);
                sum += d * y;
                mass += d;
            }
            h[i] = (float) (sum / mass);
        }
        System.out.println("mean height: chlorine " + h[0] + " acid " + h[1]);
        assertTrue(h[0] < h[1], "chlorine " + h[0] + " acid " + h[1]);
    }

    @Test
    void aWallStopsTheGas() {
        int wall = OX + 3;
        SmokeGrid g = grid(GasKind.PHOSGENE.smoke(), (x, y, z) -> y < OY || x == wall);
        for (int t = 0; t < GasKind.PHOSGENE.smoke().maxAge(); t++) {
            g.step();
            for (int y = 0; y < SY; y++) for (int z = 0; z < SZ; z++) for (int x = wall; x < SX; x++) assertEquals(0F, g.density(x, y, z));
        }
    }
}
