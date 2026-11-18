package acup.gunfeel.gas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeGrid;
import acup.gunfeel.smoke.SmokeKind;

// NTM's gases as cloud kinds: their ids go over the network, so none may meet another kind's; steam goes up, the
// fluorides and cold oxygen go down, waste gas stays.
class NtmGasTest {
    @Test
    void everyKindHasItsOwnIdAndIsFoundByIt() {
        Set<Integer> ids = new HashSet<>();
        assertTrue(ids.add(SmokeKind.SMOKE.id()));
        for (GasKind g : GasKind.values()) assertTrue(ids.add(g.smoke().id()), g + " id");
        for (NtmGas g : NtmGas.values()) {
            assertTrue(ids.add(g.smoke().id()), g + " id");
            assertSame(g.smoke(), SmokeKind.byId(g.smoke().id()));
            assertSame(g.smoke(), SmokeKind.byName(g.smoke().name()));
            assertSame(g, NtmGas.byFluid(g.fluid));
            assertSame(g, NtmGas.of(g.smoke()));
            assertTrue(GasKind.gas(g.smoke()), g + " is a gas");
            assertFalse(SmokeClouds.feeds(g.smoke()), g + ": a grenade does not feed a gas");
        }
        assertFalse(GasKind.gas(SmokeKind.SMOKE));
        assertTrue(GasKind.gas(GasKind.CHLORINE.smoke()));
    }

    @Test
    void steamRisesTheFluoridesSinkWasteGasStays() {
        float steam = height(NtmGas.STEAM), uf6 = height(NtmGas.UF6), oxygen = height(NtmGas.OXYGEN), waste = height(NtmGas.WASTE_GAS);
        System.out.println("middle of the gas above the burst after 3 s: steam " + steam + ", waste gas " + waste + ", UF6 " + uf6 + ", oxygen " + oxygen);
        assertTrue(steam > 1F, "steam rises: " + steam);
        assertTrue(uf6 < 0.5F && oxygen < 0.5F, "heavy ones stay low: " + uf6 + ", " + oxygen);
        assertTrue(waste < steam && waste > uf6, "waste gas between: " + waste);
    }

    @Test
    void steamIsGoneSoonerThanTheFluorides() {
        assertTrue(NtmGas.STEAM.smoke().maxAge() < NtmGas.UF6.smoke().maxAge());
        assertEquals(NtmGas.VOLUME, NtmGas.HOT_STEAM.smoke().volume());
    }

    // the gas's middle above the burst cell after 60 ticks, over a floor just under the burst
    private static float height(NtmGas g) {
        SmokeGrid grid = new SmokeGrid(g.smoke(), 7L, 0F, 0F, 1F);
        int floor = grid.oy() - 1;
        grid.scan((x, y, z) -> y < floor);
        grid.place();
        for (int t = 0; t < 60; t++) grid.step();
        double sum = 0, w = 0;
        for (int y = 0; y < SmokeGrid.SY; y++) for (int z = 0; z < SmokeGrid.SZ; z++) for (int x = 0; x < SmokeGrid.SX; x++) {
            float d = grid.density(x, y, z);
            sum += d * (y - grid.oy());
            w += d;
        }
        return (float) (sum / w);
    }
}
