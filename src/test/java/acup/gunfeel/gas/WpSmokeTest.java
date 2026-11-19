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
        return at((x, y, z) -> y < WP.below(), 0F);
    }

    // the grenade on the ground in these blocks, in a wind of this many blocks a second along x
    private static SmokeGrid at(SmokeGrid.Mask mask, float wind) {
        SmokeGrid g = new SmokeGrid(WP, 5, 0F, 0F, 1F, wind / 20F, 0F);
        g.scan(mask);
        g.place();
        return g;
    }

    private static void run(SmokeGrid g, int ticks) {
        for (int i = 0; i < ticks; i++) g.step();
    }

    // the dense part (above 0.3): {across x, across z, height above the burst cell}
    private static int[] dense(SmokeGrid g) {
        return above(g, 0.3F);
    }

    private static int[] above(SmokeGrid g, float least) {
        int x0 = 99, x1 = -1, z0 = 99, z1 = -1, top = -1;
        for (int y = 0; y < SmokeGrid.SY; y++) for (int z = 0; z < SmokeGrid.SZ; z++) for (int x = 0; x < SmokeGrid.SX; x++) {
            if (g.density(x, y, z) <= least) continue;
            x0 = Math.min(x0, x);
            x1 = Math.max(x1, x);
            z0 = Math.min(z0, z);
            z1 = Math.max(z1, z);
            top = Math.max(top, y - WP.below());
        }
        return new int[] {x1 - x0 + 1, z1 - z0 + 1, top};
    }

    @Test
    void aKindOfItsOwn() {
        assertSame(WP, SmokeKind.byId(WP.id()));
        assertSame(WP, SmokeKind.byName("wp"));
        // not a gas (it takes holes), but a haze, not a screen: a mob sees through it as the player does, the whole
        // of it at its fill far short of the depth that hides (SmokeSight: 3), and a second grenade's haze lies in the
        // first one's instead of feeding it out to the grid's sides
        assertFalse(GasKind.gas(WP));
        assertFalse(SmokeClouds.feeds(WP));
        assertEquals(WP.look(), WP.extinction());
        assertTrue(WP.extinction() * WP.fill() * 17 < 1F, "seen through: " + WP.extinction());
        assertTrue(WP.lift() < 0F && WP.rise() < 0F, "lies low");
        // the shells' is the same haze under an id of its own (no sparks)
        SmokeKind shell = SmokeKind.WP_SHELL;
        assertSame(shell, SmokeKind.byId(shell.id()));
        assertTrue(shell.id() != WP.id());
        assertEquals(WP.volume(), shell.volume());
        assertEquals(WP.hold(), shell.hold());
        assertEquals(WP.rgb(), shell.rgb());
        assertEquals(WP.maxAge(), shell.maxAge());
        assertFalse(GasKind.gas(shell));
        assertFalse(SmokeClouds.feeds(shell));
    }

    @Test
    void aLowFlatHaze() {
        SmokeGrid g = field();
        run(g, WP.release() + 160);
        // some 15 to 17 blocks across (NTM's three hazes some 20, the smoke screen it was 21) and 3 high (it was 8)
        int[] d = dense(g), any = above(g, 0F);
        assertTrue(d[0] >= 13 && d[0] <= 19 && d[1] >= 13 && d[1] <= 19, "across " + d[0] + " by " + d[1]);
        assertTrue(d[2] <= 2, "height " + d[2]);
        // nowhere near the grid's sides or top: no wall where the grid ends
        assertTrue(any[0] <= SmokeGrid.SX - 4 && any[1] <= SmokeGrid.SZ - 4, "to the sides: " + any[0] + " by " + any[1]);
        assertTrue(any[2] < SmokeGrid.SY - WP.below() - 1, "to the top: " + any[2]);
        assertEquals(WP.volume(), g.total(), WP.volume() * 1e-3F);
    }

    // the most smoke in a cell on the grid's sides, top and bottom over the haze's life: where it stands as a flat wall
    private static float atTheSides(SmokeGrid g, SmokeGrid.Mask mask) {
        float most = 0F;
        int sx = SmokeGrid.SX - 1, sy = SmokeGrid.SY - 1, sz = SmokeGrid.SZ - 1;
        while (!g.done()) {
            g.step();
            g.drift(mask);
            for (int a = 0; a <= sx; a++) for (int b = 0; b <= sy; b++)
                most = Math.max(most, Math.max(Math.max(g.density(a, b, 0), g.density(a, b, sz)), Math.max(g.density(0, b, a), g.density(sx, b, a))));
            for (int a = 0; a <= sx; a++) for (int b = 0; b <= sz; b++) most = Math.max(most, Math.max(g.density(a, 0, b), g.density(a, sy, b)));
        }
        return most;
    }

    @Test
    void noWallWhereTheGridEnds() {
        int oy = WP.below(), ox = SmokeGrid.OX;
        // a wall 3 blocks off, a street 7 wide, the edge of a drop 6 deep: it goes round them and keeps off the grid's sides
        SmokeGrid.Mask wall = (x, y, z) -> y < oy || x >= ox + 3, street = (x, y, z) -> y < oy || Math.abs(x - ox) >= 4,
                drop = (x, y, z) -> y < (x > ox + 1 ? oy - 6 : oy);
        for (SmokeGrid.Mask m : new SmokeGrid.Mask[] {wall, street, drop}) assertEquals(0F, atTheSides(at(m, 0F), m), 0.02F);
        // the wind draws it out and its grid goes with it a cell at a time: what is left at its side is thin (it was as
        // dense as the smoke inside, 0.97)
        SmokeGrid.Mask field = (x, y, z) -> y < oy;
        for (float wind : new float[] {0.5F, 1F, 2F}) {
            float most = atTheSides(at(field, wind), field);
            assertTrue(most < 0.3F, "in a wind of " + wind + ": " + most);
        }
    }

    @Test
    void holdsWhileNtmsFireBurns() {
        SmokeGrid g = field();
        // NTM's fire on the ground: 600 ticks; its hazes are seen for 400
        run(g, 520);
        int[] d = dense(g);
        assertTrue(d[0] >= 13, "thinned before the fire is out: " + d[0]);
        run(g, WP.maxAge() - 520);
        assertTrue(g.done(), "gone at its most age");
    }

    @Test
    void sparksForEachGrenadeOfTheCloud() {
        SmokeGrid g = field();
        assertEquals(1, WpSparks.burning(g));
        run(g, 200);
        // a feed (none come from SmokeClouds for this kind, but the grid's count holds for any)
        assertTrue(g.feed(SmokeGrid.OX + 3, WP.below(), SmokeGrid.OZ, 0F, 0F, 1F));
        assertEquals(2, WpSparks.burning(g));
        run(g, WpSparks.TICKS - 200);
        // the first grenade's burnt out, the second's still goes
        assertEquals(1, WpSparks.burning(g));
        run(g, 200);
        assertEquals(0, WpSparks.burning(g));
    }
}
