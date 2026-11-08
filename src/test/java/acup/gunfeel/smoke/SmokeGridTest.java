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
        float against = 0F;
        for (int t = 0; t < SMOKE.maxAge(); t++) {
            g.step();
            // the smoke piles up against the wall (the cell in front of it), and not a trace of it gets through
            against = Math.max(against, g.density(wall - 1, OY, OZ));
            for (int y = 0; y < SY; y++) for (int z = 0; z < SZ; z++) for (int x = wall; x < SX; x++)
                assertEquals(0F, g.density(x, y, z), "smoke at " + x + "," + y + "," + z + " behind the wall, tick " + t);
        }
        assertTrue(against > 0.3F, "the smoke never reached the wall: " + against);
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
        b.step();
        assertNotEquals(a.hash(), b.hash());
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
    void cloudsOnlyStepTheirOwnBox() {
        // the cost of a step is the occupied box (and one cell around it): in the dense phase it stays a small part of
        // the grid, so eight clouds step quickly (a bound on the work, not on the clock, so it never flickers)
        for (int i = 0; i < 8; i++) {
            SmokeGrid g = grid(i, FIELD);
            run(g, 300);
            int[] b = g.box();
            long box = (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
            assertTrue(box < SmokeGrid.CELLS * 0.4, "cloud " + i + " fills " + box + " of " + SmokeGrid.CELLS + " cells");
        }
    }

    @Test
    void cloudLivesAboutAsLongAsTheTextsSay() {
        // the changelog and the settings say how long a cloud lasts: hold 20 s after the release, then it is gone
        // 6 to 10 s after it starts to thin
        int thinFrom = SMOKE.release() + SMOKE.hold();
        for (long seed = 1; seed <= 4; seed++) {
            SmokeGrid g = grid(seed, FIELD);
            int gone = -1;
            for (int t = 0; t < SMOKE.maxAge() + 50 && gone < 0; t++) {
                g.step();
                if (g.done()) gone = g.age();
            }
            assertTrue(gone > thinFrom + 6 * 20 && gone < thinFrom + 10 * 20, "seed " + seed + ": gone at tick " + gone + ", thinning from " + thinFrom);
            assertTrue(gone <= SMOKE.maxAge(), "maxAge " + SMOKE.maxAge() + " is below the real end " + gone);
        }
    }

    // ---- the grid read from bytes

    private static byte[] packet(int age, int ax, int ay, int az, int bx, int by, int bz, float... cells) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(age);
        for (int v : new int[] {ax, ay, az, bx, by, bz}) out.writeByte(v);
        for (float c : cells) out.writeFloat(c);
        // a grid with cells says whether a blast holds smoke in them: none here
        if (ax != SX) out.writeBoolean(false);
        return bytes.toByteArray();
    }

    private static float[] fill(int n, float v) {
        float[] a = new float[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    @Test
    void readRefusesABadGridAndChangesNothing() throws Exception {
        SmokeGrid g = grid(21, FIELD);
        run(g, 150);
        long hash = g.hash();
        int[] box = g.box();
        float total = g.total();
        int age = g.age();
        byte[][] bad = {
            // an empty side in x with y and z far outside the grid: the box that would blow up the next step
            packet(5, 10, -100, 3, 4, 100, 3),
            packet(5, 10, 0, 0, 4, 12, 24),
            // every corner must be in the grid, and no side backwards
            packet(5, -1, 0, 0, 3, 3, 3, fill(5 * 4 * 4, 0.1F)),
            packet(5, 0, 0, 0, SX, 3, 3, fill(26 * 4 * 4, 0.1F)),
            packet(5, 0, 0, 0, 3, SY, 3, fill(4 * 14 * 4, 0.1F)),
            packet(5, 0, 0, 0, 3, 3, SZ, fill(4 * 4 * 26, 0.1F)),
            packet(5, 3, 0, 0, 2, 3, 3),
            packet(5, 0, 3, 0, 3, 2, 3),
            packet(5, 0, 0, 3, 3, 3, 2),
            // a box that is not the empty one by its corners alone
            packet(5, SX, SY, SZ, -1, -1, 0),
            // a density that is not one
            packet(5, 0, 0, 0, 1, 1, 1, 0.1F, 0.1F, 0.1F, 0.1F, 0.1F, 0.1F, Float.NaN, 0.1F),
            packet(5, 0, 0, 0, 1, 1, 1, 0.1F, 0.1F, 0.1F, 0.1F, 0.1F, 0.1F, -0.5F, 0.1F),
            packet(5, 0, 0, 0, 1, 1, 1, 0.1F, 0.1F, 0.1F, 0.1F, 0.1F, 0.1F, 0.1F, 500F),
            // an age no cloud of this kind reaches
            packet(SMOKE.maxAge() + 5, 0, 0, 0, 1, 1, 1, fill(8, 0.1F)),
            // cut short: in the middle of the cells, in the middle of the header, nothing at all
            java.util.Arrays.copyOf(packet(5, 0, 0, 0, 3, 3, 3, fill(64, 0.1F)), 20),
            java.util.Arrays.copyOf(packet(5, 0, 0, 0, 3, 3, 3, fill(64, 0.1F)), 4),
            new byte[0],
        };
        for (int i = 0; i < bad.length; i++) {
            final int n = i;
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> g.read(new DataInputStream(new ByteArrayInputStream(bad[n]))), "packet " + n);
            assertEquals(hash, g.hash(), "packet " + i + " changed the cells");
            assertEquals(age, g.age(), "packet " + i + " changed the age");
            assertEquals(java.util.Arrays.toString(box), java.util.Arrays.toString(g.box()), "packet " + i + " changed the box");
            assertEquals(total, g.total(), "packet " + i);
        }
        // and a cloud that came through a bad grid goes on stepping without an index out of range
        run(g, 50);
    }

    @Test
    void readTakesTheEmptyGridAndAGoodBox() throws Exception {
        SmokeGrid g = grid(22, FIELD);
        run(g, 100);
        g.read(new DataInputStream(new ByteArrayInputStream(packet(77, SX, SY, SZ, -1, -1, -1))));
        assertEquals(77, g.age());
        assertTrue(!g.occupied());
        assertEquals(0F, g.total());
        g.read(new DataInputStream(new ByteArrayInputStream(packet(78, 2, 3, 4, 3, 4, 5, fill(8, 0.25F)))));
        assertEquals(78, g.age());
        assertEquals(2F, g.total(), 1e-5F);
        assertEquals(0.25F, g.density(3, 4, 5));
        assertEquals(0F, g.density(4, 4, 5));
    }
}
