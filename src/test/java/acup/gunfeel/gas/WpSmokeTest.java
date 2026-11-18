package acup.gunfeel.gas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import acup.gunfeel.gas.client.WpSparks;
import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeGrid;
import acup.gunfeel.smoke.SmokeKind;

class WpSmokeTest {
    private static final SmokeKind WP = SmokeKind.WP;

    private static SmokeGrid field() {
        SmokeGrid g = new SmokeGrid(WP, 5, 0F, 0F, 1F);
        g.scan((x, y, z) -> y < SmokeGrid.OY);
        g.place();
        return g;
    }

    private static void run(SmokeGrid g, int ticks) {
        for (int i = 0; i < ticks; i++) g.step();
    }

    // the dense part (above 0.3): {across x, across z, height above the burst cell}
    private static int[] dense(SmokeGrid g) {
        int x0 = 99, x1 = -1, z0 = 99, z1 = -1, top = -1;
        for (int y = 0; y < SmokeGrid.SY; y++) for (int z = 0; z < SmokeGrid.SZ; z++) for (int x = 0; x < SmokeGrid.SX; x++) {
            if (g.density(x, y, z) <= 0.3F) continue;
            x0 = Math.min(x0, x);
            x1 = Math.max(x1, x);
            z0 = Math.min(z0, z);
            z1 = Math.max(z1, z);
            top = Math.max(top, y - SmokeGrid.OY);
        }
        return new int[] {x1 - x0 + 1, z1 - z0 + 1, top};
    }

    @Test
    void aKindOfItsOwn() {
        assertSame(WP, SmokeKind.byId(WP.id()));
        assertSame(WP, SmokeKind.byName("wp"));
        // smoke, not a gas: it screens, takes holes and a second grenade feeds it
        assertFalse(GasKind.gas(WP));
        assertTrue(SmokeClouds.feeds(WP));
        assertEquals(SmokeKind.SMOKE.extinction(), WP.extinction());
        // the shells' is the same smoke under an id of its own (no sparks), and not one the grenade's feeds
        SmokeKind shell = SmokeKind.WP_SHELL;
        assertSame(shell, SmokeKind.byId(shell.id()));
        assertTrue(shell.id() != WP.id());
        assertEquals(WP.volume(), shell.volume());
        assertEquals(WP.hold(), shell.hold());
        assertEquals(WP.rgb(), shell.rgb());
        assertEquals(WP.maxAge(), shell.maxAge());
        assertFalse(GasKind.gas(shell));
    }

    @Test
    void theSizeOfNtmsHazes() {
        SmokeGrid g = field();
        run(g, WP.release() + 40);
        // NTM's three hazes lie some 20 blocks across; smoke's half sphere is 14
        int[] d = dense(g);
        assertTrue(d[0] >= 18 && d[1] >= 18, "across " + d[0] + " by " + d[1]);
        assertTrue(d[0] < SmokeGrid.SX && d[1] < SmokeGrid.SZ, "it reached the box's side: " + d[0] + " by " + d[1]);
        assertTrue(d[2] >= 4 && d[2] < SmokeGrid.SY - SmokeGrid.OY - 1, "height " + d[2]);
        assertEquals(WP.volume(), g.total(), WP.volume() * 1e-3F);
    }

    @Test
    void holdsWhileNtmsFireBurns() {
        SmokeGrid g = field();
        // NTM's fire on the ground: 600 ticks; its hazes are seen for 400
        run(g, 520);
        int[] d = dense(g);
        assertTrue(d[0] >= 18, "thinned before the fire is out: " + d[0]);
        run(g, WP.maxAge() - 520);
        assertTrue(g.done(), "gone at its most age");
    }

    @Test
    void sparksForEachGrenadeOfTheCloud() {
        SmokeGrid g = field();
        assertEquals(1, WpSparks.burning(g));
        run(g, 200);
        assertTrue(g.feed(SmokeGrid.OX + 3, SmokeGrid.OY, SmokeGrid.OZ, 0F, 0F, 1F));
        assertEquals(2, WpSparks.burning(g));
        run(g, WpSparks.TICKS - 200);
        // the first grenade's burnt out, the second's still goes
        assertEquals(1, WpSparks.burning(g));
        run(g, 200);
        assertEquals(0, WpSparks.burning(g));
    }
}
