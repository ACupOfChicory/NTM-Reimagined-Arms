package acup.gunfeel.smoke;

import static acup.gunfeel.smoke.SmokeGrid.OX;
import static acup.gunfeel.smoke.SmokeGrid.OY;
import static acup.gunfeel.smoke.SmokeGrid.OZ;
import static acup.gunfeel.smoke.SmokeGrid.SX;
import static acup.gunfeel.smoke.SmokeGrid.SY;
import static acup.gunfeel.smoke.SmokeGrid.SZ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

import org.junit.jupiter.api.Test;

class SmokeGridTest {
    private static final SmokeKind SMOKE = SmokeKind.SMOKE;
    // ground under the burst cell
    private static final SmokeGrid.Mask FIELD = (x, y, z) -> y < OY;

    private static SmokeGrid grid(long seed, SmokeGrid.Mask mask) {
        SmokeGrid g = new SmokeGrid(SMOKE, seed, 1F, 0.2F, 1F);
        g.scan(mask);
        g.place();
        return g;
    }

    private static void run(SmokeGrid g, int ticks) {
        for (int i = 0; i < ticks; i++) g.step();
    }

    // how far along +x from the burst cell the smoke is dense (above 0.3)
    private static int reachX(SmokeGrid g) {
        int r = 0;
        for (int x = OX; x < SX; x++) if (g.density(x, OY, OZ) > 0.3F) r = x - OX;
        return r;
    }

    @Test
    void wallKeepsTheSmokeOut() {
        int wall = OX + 3;
        SmokeGrid g = grid(7, (x, y, z) -> y < OY || x == wall);
        for (int t = 0; t < SMOKE.maxAge(); t++) {
            g.step();
            for (int y = 0; y < SY; y++) for (int z = 0; z < SZ; z++) for (int x = wall; x < SX; x++)
                assertEquals(0F, g.density(x, y, z), "smoke at " + x + "," + y + "," + z + " behind the wall, tick " + t);
        }
        assertTrue(reachX(grid(7, FIELD)) >= 0);
    }

    @Test
    void volumeStaysUntilItThins() {
        SmokeGrid g = grid(11, FIELD);
        run(g, SMOKE.release());
        assertEquals(SMOKE.volume(), g.total(), SMOKE.volume() * 1e-4F);
        run(g, SMOKE.hold() - 1);
        // nothing reached the edge of the box in an open field
        assertEquals(SMOKE.volume(), g.total(), SMOKE.volume() * 1e-3F);
        int[] b = g.box();
        assertTrue(b[0] > 0 && b[2] > 0 && b[3] < SX - 1 && b[5] < SZ - 1 && b[4] < SY - 1, "the cloud reached the box's side: " + java.util.Arrays.toString(b));
        run(g, SMOKE.maxAge());
        assertTrue(g.done(), "the cloud thinned away");
        assertEquals(0F, g.total());
    }

    @Test
    void fieldGivesADenseHalfSphere() {
        SmokeGrid g = grid(3, FIELD);
        run(g, SMOKE.release() + 100);
        int dense = 0;
        float most = 0F;
        for (int y = 0; y < SY; y++) for (int z = 0; z < SZ; z++) for (int x = 0; x < SX; x++) {
            float v = g.density(x, y, z);
            assertTrue(v >= 0F);
            if (v > 0.3F) dense++;
            most = Math.max(most, v);
        }
        // ~380 / 0.7 cells of dense smoke: a half sphere of about 6.5 blocks
        assertTrue(dense > 400 && dense < 700, "dense cells " + dense);
        assertTrue(most < 1.2F, "the core spread out: " + most);
        assertTrue(g.density(OX, OY, OZ) > 0.6F, "the core is dense");
        assertTrue(reachX(g) >= 4 && reachX(g) <= 9, "reach " + reachX(g));
    }

    @Test
    void sameSeedSameCloud() {
        SmokeGrid a = grid(42, FIELD), b = grid(42, FIELD), c = grid(43, FIELD);
        boolean differs = false;
        for (int t = 0; t < SMOKE.maxAge(); t++) {
            a.step();
            b.step();
            c.step();
            assertEquals(a.hash(), b.hash(), "tick " + t);
            if (a.hash() != c.hash()) differs = true;
        }
        assertTrue(differs, "another seed makes another cloud");
    }

    @Test
    void corridorTakesItFartherThanAField() {
        SmokeGrid field = grid(5, FIELD);
        // a corridor 1 wide and 2 high along x
        SmokeGrid corridor = grid(5, (x, y, z) -> y < OY || y > OY + 1 || z != OZ);
        run(field, SMOKE.release() + 100);
        run(corridor, SMOKE.release() + 100);
        assertTrue(reachX(corridor) > reachX(field) + 3, "corridor " + reachX(corridor) + ", field " + reachX(field));
    }

    @Test
    void doorOpensAndTheSmokeGoesIn() {
        // a room east of x = OX + 3 behind a wall with a door cell at (OX + 3, OY, OZ)
        int wall = OX + 3;
        boolean[] open = {false};
        SmokeGrid.Mask mask = (x, y, z) -> y < OY || x == wall && !(open[0] && y == OY && z == OZ);
        SmokeGrid g = grid(9, mask);
        run(g, 80);
        assertEquals(0F, g.density(wall + 1, OY, OZ));
        open[0] = true;
        for (int t = 0; t < 120; t++) {
            g.refresh(mask, g.age() % SmokeMask.PARTS, SmokeMask.PARTS);
            g.step();
        }
        assertTrue(g.density(wall + 1, OY, OZ) > 0.1F, "smoke came through the door: " + g.density(wall + 1, OY, OZ));
        // and shut again: the doorway cell loses its smoke
        open[0] = false;
        for (int p = 0; p < SmokeMask.PARTS; p++) g.refresh(mask, p, SmokeMask.PARTS);
        assertEquals(0F, g.density(wall, OY, OZ));
    }

    @Test
    void burstInAWallComesOutNextToIt() {
        SmokeGrid g = grid(1, (x, y, z) -> y <= OY);
        assertEquals(SMOKE.bomblets(), g.emitters());
        run(g, SMOKE.release());
        assertEquals(0F, g.density(OX, OY, OZ));
        assertTrue(g.density(OX, OY + 1, OZ) > 0F);
        SmokeGrid shut = grid(1, (x, y, z) -> true);
        assertEquals(0, shut.emitters());
        run(shut, SMOKE.release() + 1);
        assertTrue(shut.done());
    }

    @Test
    void writtenStateGoesOnTheSame() throws Exception {
        SmokeGrid a = grid(77, FIELD), b = grid(77, FIELD);
        run(a, 200);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        a.write(new DataOutputStream(bytes));
        b.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(a.hash(), b.hash());
        assertEquals(a.age(), b.age());
        for (int t = 0; t < 300; t++) {
            a.step();
            b.step();
        }
        assertEquals(a.hash(), b.hash());
        b.disturb(OX + 0.5F, OY + 0.5F, OZ + 0.5F, 2F);
        assertNotEquals(a.hash(), b.hash());
        assertEquals(0F, b.density(OX, OY, OZ));
    }

    @Test
    void gridPacksSmallForAResync() throws Exception {
        SmokeGrid a = grid(13, FIELD), b = grid(13, FIELD);
        run(a, 250);
        byte[] packed = SmokeNet.encode(a);
        // the occupied box's floats, deflated: well inside one packet
        assertTrue(packed.length < 16_000, packed.length + " bytes");
        SmokeNet.decode(b, packed);
        assertEquals(a.hash(), b.hash());
    }

    @Test
    void eightCloudsStepQuickly() {
        SmokeGrid[] gs = new SmokeGrid[8];
        for (int i = 0; i < gs.length; i++) gs[i] = grid(i, FIELD);
        // warm up, then the dense part of their lives
        for (SmokeGrid g : gs) run(g, 100);
        long t0 = System.nanoTime();
        for (int t = 0; t < 200; t++) for (SmokeGrid g : gs) g.step();
        double ms = (System.nanoTime() - t0) / 1e6 / 200;
        // ~0.1 ms on the dev machine; a loose bound for slow test machines
        assertTrue(ms < 3.0, "8 clouds take " + ms + " ms a tick");
    }
}
