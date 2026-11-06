package acup.gunfeel.smoke;

import static acup.gunfeel.smoke.SmokeGrid.OX;
import static acup.gunfeel.smoke.SmokeGrid.OY;
import static acup.gunfeel.smoke.SmokeGrid.OZ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** The store of clouds, the client's catch-up and the optical depth, over a mask instead of a level. */
class SmokeCloudsTest {
    private static final SmokeKind SMOKE = SmokeKind.SMOKE;
    private static final SmokeGrid.Mask FIELD = (x, y, z) -> y < OY;
    // the cell (0, 0, 0) of every cloud here is the world's block (0, 0, 0)
    private static final BlockPos ORIGIN = new BlockPos(OX, OY, OZ);

    private static SmokeClouds clouds() {
        return new SmokeClouds(c -> FIELD);
    }

    private static SmokeClouds.Cloud born(SmokeClouds all, int id, long seed, long born) {
        return all.create(id, ORIGIN, SMOKE, seed, 1F, 0.2F, 1F, born);
    }

    // ---- catching up

    @Test
    void catchingUpAllAtOnceGivesTheHashOfOneStepAtATime() {
        Map<Integer, Long> once = new HashMap<>(), slowly = new HashMap<>();
        SmokeClouds a = clouds(), b = clouds();
        SmokeClouds.Cloud ca = born(a, 3, 99, 0), cb = born(b, 3, 99, 0);
        a.insert(ca, null);
        b.insert(cb, null);
        // a: 400 ticks late, in turns of a budget; b: every tick as it came
        long time = 400;
        while (ca.grid.age() < time) a.catchUp(time, 90, 25, (c, g) -> {
            if (SmokeNet.checkDue(c.id, g.age())) once.put(g.age(), g.hash());
        });
        for (long t = 1; t <= time; t++) b.catchUp(t, 200, 25, (c, g) -> {
            if (SmokeNet.checkDue(c.id, g.age())) slowly.put(g.age(), g.hash());
        });
        assertEquals(400, ca.grid.age());
        assertEquals(400, cb.grid.age());
        assertEquals(ca.grid.hash(), cb.grid.hash());
        // a check every 40 ticks, not a hash a step
        assertEquals(10, once.size());
        assertEquals(once, slowly);
    }

    @Test
    void catchUpSharesOneBudgetBetweenTheClouds() {
        SmokeClouds all = clouds();
        List<SmokeClouds.Cloud> cs = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            SmokeClouds.Cloud c = born(all, i, i, 0);
            all.insert(c, null);
            cs.add(c);
        }
        int taken = all.catchUp(400, 200, 25, null);
        // 8 first steps and then the rest of the 200 in turns: nowhere near 8 x 400, and nobody was left out
        assertTrue(taken >= 200 && taken <= 200 + 8, "steps " + taken);
        int sum = 0;
        for (SmokeClouds.Cloud c : cs) {
            assertTrue(c.grid.age() >= 15, "a cloud got " + c.grid.age() + " steps");
            assertTrue(c.catchingUp(400));
            sum += c.grid.age();
        }
        assertEquals(taken, sum);
        // a few more ticks and they are all there
        int ticks = 1;
        while (cs.stream().anyMatch(c -> c.catchingUp(400))) {
            all.catchUp(400, 200, 25, null);
            assertTrue(++ticks < 40, "still catching up after " + ticks + " ticks");
        }
        assertTrue(ticks >= 14, "3200 steps cannot take " + ticks + " ticks of 200");
    }

    @Test
    void aCloudOnTimeTakesOneStepATick() {
        SmokeClouds all = clouds();
        SmokeClouds.Cloud c = born(all, 1, 5, 0);
        all.insert(c, null);
        for (long t = 1; t <= 30; t++) {
            assertEquals(1, all.catchUp(t, 200, 25, null));
            assertTrue(!c.catchingUp(t));
        }
    }

    // ---- the limit

    @Test
    void aCloudPastTheLimitPushesTheOldestOutAndIsToldAbout() {
        SmokeClouds all = clouds();
        List<Integer> gone = new ArrayList<>();
        for (int i = 1; i <= SmokeClouds.LIMIT; i++) all.insert(born(all, i, i, 0), c -> gone.add(c.id));
        assertEquals(SmokeClouds.LIMIT, all.clouds().size());
        assertTrue(gone.isEmpty());
        // the same id again replaces: nobody is pushed out
        all.insert(born(all, 5, 77, 0), c -> gone.add(c.id));
        assertTrue(gone.isEmpty());
        all.insert(born(all, 100, 1, 0), c -> gone.add(c.id));
        all.insert(born(all, 101, 1, 0), c -> gone.add(c.id));
        assertEquals(List.of(1, 2), gone);
        assertEquals(SmokeClouds.LIMIT, all.clouds().size());
        assertEquals(null, all.cloud(1));
    }

    @Test
    void theListOfCloudsIsAFixedSnapshot() {
        SmokeClouds all = clouds();
        all.insert(born(all, 1, 1, 0), null);
        List<SmokeClouds.Cloud> before = all.clouds();
        assertSame(before, all.clouds());
        all.insert(born(all, 2, 2, 0), null);
        assertEquals(1, before.size());
        assertEquals(2, all.clouds().size());
        all.remove(1);
        assertEquals(1, all.clouds().size());
    }

    // ---- the geometry of the optical depth

    private static byte[] box(int ax, int ay, int az, int bx, int by, int bz, float[] cells) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(0);
        for (int v : new int[] {ax, ay, az, bx, by, bz}) out.writeByte(v);
        for (float c : cells) out.writeFloat(c);
        return bytes.toByteArray();
    }

    // a cloud whose cell (0, 0, 0) is the block (0, 0, 0), a box of cells with the given densities
    private static SmokeClouds.Cloud cloudOf(SmokeGrid.Mask mask, int ax, int ay, int az, int bx, int by, int bz, float[] cells) throws Exception {
        SmokeGrid g = new SmokeGrid(SMOKE, 1, 0F, 1F, 1F);
        g.scan(mask);
        g.read(new DataInputStream(new ByteArrayInputStream(box(ax, ay, az, bx, by, bz, cells))));
        return new SmokeClouds.Cloud(1, g, ORIGIN, 0, 0F, 1F, 1F);
    }

    private static float[] uniform(int n, float v) {
        float[] a = new float[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    @Test
    void walkThroughAUniformBox() throws Exception {
        // cells x 8..16, y 3..5, z 8..16 at 0.5
        SmokeClouds.Cloud c = cloudOf((x, y, z) -> false, 8, 3, 8, 16, 5, 16, uniform(9 * 3 * 9, 0.5F));
        // straight through along x: 9 blocks of 0.5
        assertEquals(4.5F, SmokeClouds.walk(c, new Vec3(-5, 4.5, 12.5), new Vec3(30, 4.5, 12.5)), 1e-3F);
        // the same the other way
        assertEquals(4.5F, SmokeClouds.walk(c, new Vec3(30, 4.5, 12.5), new Vec3(-5, 4.5, 12.5)), 1e-3F);
        // along y and z too (an axis-parallel ray: the other two components are zero)
        assertEquals(1.5F, SmokeClouds.walk(c, new Vec3(12.5, -4, 12.5), new Vec3(12.5, 20, 12.5)), 1e-3F);
        assertEquals(4.5F, SmokeClouds.walk(c, new Vec3(12.5, 4.5, 0), new Vec3(12.5, 4.5, 40)), 1e-3F);
        // starting inside: only what is left of it
        assertEquals(2.5F, SmokeClouds.walk(c, new Vec3(12, 4.5, 12.5), new Vec3(30, 4.5, 12.5)), 1e-3F);
        // ending inside
        assertEquals(1.0F, SmokeClouds.walk(c, new Vec3(-5, 4.5, 12.5), new Vec3(10, 4.5, 12.5)), 1e-3F);
        // a segment that stops short of the box, one beside it, one over it, and a point
        assertEquals(0F, SmokeClouds.walk(c, new Vec3(-5, 4.5, 12.5), new Vec3(7.9, 4.5, 12.5)));
        assertEquals(0F, SmokeClouds.walk(c, new Vec3(-5, 4.5, 20.5), new Vec3(30, 4.5, 20.5)));
        assertEquals(0F, SmokeClouds.walk(c, new Vec3(-5, 10.5, 12.5), new Vec3(30, 10.5, 12.5)));
        assertEquals(0F, SmokeClouds.walk(c, new Vec3(12.5, 4.5, 12.5), new Vec3(12.5, 4.5, 12.5)));
        // a ray that only grazes the box's edge
        assertEquals(0F, SmokeClouds.walk(c, new Vec3(-5, 4.5, 7.99), new Vec3(30, 4.5, 7.99)));
    }

    @Test
    void walkAgreesWithSamplingTheCellsAlongADiagonal() throws Exception {
        int ax = 4, ay = 2, az = 6, bx = 20, by = 9, bz = 18;
        float[] cells = new float[(bx - ax + 1) * (by - ay + 1) * (bz - az + 1)];
        java.util.Random r = new java.util.Random(5);
        for (int i = 0; i < cells.length; i++) cells[i] = r.nextFloat() * 1.1F;
        SmokeClouds.Cloud c = cloudOf((x, y, z) -> false, ax, ay, az, bx, by, bz, cells);
        Vec3[][] rays = {
            {new Vec3(-3, 0.5, 5), new Vec3(25, 11, 22)},
            {new Vec3(24, 10, 3), new Vec3(1, 3, 21)},
            {new Vec3(12.2, 5.1, 12.7), new Vec3(30, 1, 0)},
        };
        for (Vec3[] ray : rays) {
            int n = 200_000;
            double length = ray[0].distanceTo(ray[1]), sum = 0;
            for (int i = 0; i < n; i++) {
                double t = (i + 0.5) / n;
                Vec3 p = ray[0].add(ray[1].subtract(ray[0]).scale(t));
                sum += c.cell((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z)) * length / n;
            }
            assertEquals(sum, SmokeClouds.walk(c, ray[0], ray[1]), Math.max(0.01, sum * 0.002), "ray from " + ray[0] + " to " + ray[1]);
        }
    }

    @Test
    void densityBetweenCellsLeavesTheClosedOnesOut() throws Exception {
        // every cell 0.5, the cell (12, 4, 12) closed and holding 9
        float[] cells = uniform(3 * 3 * 3, 0.5F);
        cells[1 + 3 + 9] = 9F;
        SmokeClouds.Cloud c = cloudOf((x, y, z) -> x == 12 && y == 4 && z == 12, 11, 3, 11, 13, 5, 13, cells);
        // between the closed cell's centre and the open one beside it: only the open one counts
        assertEquals(0.5F, c.density(12.9, 4.5, 12.5), 1e-5F);
        // between open cells, plain interpolation
        assertEquals(0.5F, c.density(11.9, 4.5, 11.5), 1e-5F);
        // all around closed: nothing
        SmokeClouds.Cloud shut = cloudOf((x, y, z) -> true, 11, 3, 11, 13, 5, 13, uniform(27, 0.5F));
        assertEquals(0F, shut.density(12.5, 4.5, 12.5));
    }

    @Test
    void checksFallOnTheSameAgesForServerAndClient() {
        // 40 ticks apart, shifted by id
        for (int id = 1; id <= 6; id++) {
            int first = -1;
            for (int age = 0; age < 200; age++) if (SmokeNet.checkDue(id, age)) {
                if (first < 0) first = age;
                else assertEquals(0, (age - first) % SmokeNet.CHECK_EVERY);
            }
            assertTrue(first >= 0);
        }
    }
}
