package acup.gunfeel.smoke;

import static acup.gunfeel.smoke.SmokeGrid.OX;
import static acup.gunfeel.smoke.SmokeGrid.OY;
import static acup.gunfeel.smoke.SmokeGrid.OZ;
import static acup.gunfeel.smoke.SmokeGrid.SX;
import static acup.gunfeel.smoke.SmokeGrid.SY;
import static acup.gunfeel.smoke.SmokeGrid.SZ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

import org.junit.jupiter.api.Test;

import acup.gunfeel.gas.GasKind;
import net.minecraft.core.BlockPos;

/**
 * The wind over a cloud: it carries the smoke downwind and the box follows it, nothing is cut off by the box's side, a
 * roof keeps it off, a wall shelters the cells behind it, an open window airs a room out and a shut room keeps its gas,
 * and a client's copy (caught up late, or given the server's grid halfway) drifts the same, bit for bit.
 */
class SmokeWindTest {
    private static final SmokeGrid.Mask FIELD = (x, y, z) -> y < OY;
    // the cell (0, 0, 0) of every cloud here is the world's block (0, 0, 0) while its box has not moved
    private static final BlockPos ORIGIN = new BlockPos(OX, OY, OZ);
    private static final float CLEAR = SmokeWind.CLEAR / 20F, RAIN = SmokeWind.RAIN / 20F, STORM = SmokeWind.STORM / 20F;

    private static SmokeClouds.Cloud cloud(SmokeClouds all, SmokeKind kind, float wx, float wz) {
        return all.create(1, ORIGIN, kind, 77L, 1F, 0.2F, 1F, wx, wz, 0L);
    }

    private static void steps(SmokeClouds all, SmokeClouds.Cloud c, long to) {
        all.step(c, to, Integer.MAX_VALUE, null);
    }

    // the smoke's middle along x and z, world blocks
    private static double[] middle(SmokeClouds.Cloud c) {
        double sx = 0, sz = 0, m = 0;
        for (int y = 0; y < SY; y++) for (int z = 0; z < SZ; z++) for (int x = 0; x < SX; x++) {
            float d = c.grid.density(x, y, z);
            sx += d * (c.baseX() + x + 0.5);
            sz += d * (c.baseZ() + z + 0.5);
            m += d;
        }
        return new double[] {sx / m, sz / m};
    }

    @Test
    void theWindCarriesTheCloudAndTheBoxFollows() {
        SmokeClouds all = new SmokeClouds(c -> FIELD);
        SmokeClouds.Cloud calm = cloud(all, SmokeKind.SMOKE, 0F, 0F), wind = cloud(all, SmokeKind.SMOKE, CLEAR, 0F);
        steps(all, calm, 40);
        // the middle once let out: a little ahead of the burst, where the bomblets fanned
        double[] start = middle(calm);
        steps(all, calm, 400);
        steps(all, wind, 400);
        double[] still = middle(calm), went = middle(wind);
        System.out.println("calm moved " + (still[0] - start[0]) + ", clear wind " + (went[0] - still[0]) + " blocks further; box moved " + wind.grid.shiftX());
        assertTrue(Math.abs(still[0] - start[0]) < 1.0, "no wind, no drift: " + (still[0] - start[0]));
        assertEquals(0, calm.grid.shiftX());
        // half a block a second for 20 s is 10 blocks
        assertTrue(went[0] - still[0] > 7.0 && went[0] - still[0] < 12.0, "clear wind for 20 s: " + (went[0] - still[0]));
        assertEquals(still[1], went[1], 0.6, "the wind blows along x only");
        assertTrue(wind.grid.shiftX() > 0, "the box went downwind");
    }

    @Test
    void theBoxSideCutsNothingOff() {
        StringBuilder bad = new StringBuilder();
        for (float speed : new float[] {CLEAR, RAIN, STORM}) for (int way = 0; way < 4; way++) {
            float wx = way == 0 ? speed : way == 1 ? -speed : 0F, wz = way == 2 ? speed : way == 3 ? -speed : 0F;
            SmokeClouds all = new SmokeClouds(c -> FIELD);
            SmokeClouds.Cloud calm = cloud(all, SmokeKind.SMOKE, 0F, 0F), wind = cloud(all, SmokeKind.SMOKE, wx, wz);
            steps(all, calm, 380);
            steps(all, wind, 380);
            float kept = wind.grid.total() / calm.grid.total();
            System.out.println("wind " + wx + ", " + wz + ": kept " + kept + ", box moved " + wind.grid.shiftX() + ", " + wind.grid.shiftZ());
            // the wind thins a cloud a little (its thin trail goes); a thunderstorm stretches it longer than the box
            float least = speed == CLEAR ? KEPT_CLEAR : speed == RAIN ? KEPT_RAIN : KEPT_STORM;
            if (!(kept > least)) bad.append(" ").append(wx).append(",").append(wz).append(": ").append(kept);
        }
        assertTrue(bad.length() == 0, "cut:" + bad);
    }

    private static final float KEPT_CLEAR = 0.95F, KEPT_RAIN = 0.92F, KEPT_STORM = 0.8F;

    @Test
    void aClientCaughtUpLateDriftsTheSame() {
        SmokeClouds server = new SmokeClouds(c -> FIELD), client = new SmokeClouds(c -> FIELD);
        SmokeClouds.Cloud s = cloud(server, SmokeKind.SMOKE, RAIN * 0.8F, -RAIN * 0.6F), c = cloud(client, SmokeKind.SMOKE, RAIN * 0.8F, -RAIN * 0.6F);
        server.insert(s, null);
        client.insert(c, null);
        for (long t = 1; t <= 450; t++) {
            server.tick(t, 1);
            if (SmokeNet.checkDue(1, s.grid.age()) && t < 300) assertTrue(s.grid.shiftX() >= 0);
        }
        // the client got the cloud 450 ticks late and catches it up in turns
        while (c.grid.age() < 450) client.catchUp(450, 90, 25, null);
        assertEquals(s.grid.hash(), c.grid.hash());
        assertEquals(s.grid.shiftX(), c.grid.shiftX());
        assertEquals(s.grid.shiftZ(), c.grid.shiftZ());
        assertTrue(s.grid.shiftX() > 0 && s.grid.shiftZ() < 0, "the box went with the wind: " + s.grid.shiftX() + ", " + s.grid.shiftZ());
    }

    @Test
    void aGridFromTheServerHalfwayGoesOnTheSame() throws Exception {
        SmokeClouds server = new SmokeClouds(c -> FIELD), client = new SmokeClouds(c -> FIELD);
        SmokeClouds.Cloud s = cloud(server, SmokeKind.SMOKE, STORM, 0F), c = cloud(client, SmokeKind.SMOKE, STORM, 0F);
        // a second grenade upwind, its cells left behind by the box later
        SmokeClouds.Feed feed = new SmokeClouds.Feed(20, OX - 3, OY, OZ, 1F, 0F, 1F);
        s.stir(feed);
        c.stir(feed);
        steps(server, s, 260);
        // the client's copy went apart (it missed ticks) and is behind: the server's grid puts it right
        steps(client, c, 30);
        assertTrue(s.grid.shiftX() > c.grid.shiftX(), "the server's box is further on: " + s.grid.shiftX() + " against " + c.grid.shiftX());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        s.grid.write(new DataOutputStream(bytes));
        c.grid.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        c.stepped();
        assertEquals(s.grid.hash(), c.grid.hash());
        steps(server, s, 500);
        steps(client, c, 500);
        assertEquals(s.grid.hash(), c.grid.hash(), "the walls read again after the grid came, the copy drifts on with the server");
    }

    @Test
    void aRoofKeepsTheWindOff() {
        // a roof three blocks over the ground everywhere: nothing under it has wind or a draught
        SmokeGrid.Mask shed = (x, y, z) -> y < OY || y == OY + 3;
        SmokeGrid calm = new SmokeGrid(SmokeKind.SMOKE, 9L, 1F, 0F, 1F), wind = new SmokeGrid(SmokeKind.SMOKE, 9L, 1F, 0F, 1F, STORM, STORM);
        for (SmokeGrid g : new SmokeGrid[] {calm, wind}) {
            g.scan(shed);
            g.place();
        }
        for (int t = 0; t < 300; t++) {
            calm.step();
            wind.step();
            wind.drift(shed);
        }
        assertEquals(calm.hash(), wind.hash(), "under a roof the wind changes nothing");
        assertEquals(0F, wind.air(OX, OY, OZ));
        // and a roof above the box, from the mask's columns, does the same
        SmokeGrid.Mask high = new SmokeGrid.Mask() {
            @Override
            public boolean closed(int x, int y, int z) {
                return y < OY;
            }

            @Override
            public boolean roofed(int x, int z) {
                return true;
            }
        };
        SmokeGrid hall = new SmokeGrid(SmokeKind.SMOKE, 9L, 1F, 0F, 1F, STORM, 0F);
        hall.scan(high);
        hall.place();
        hall.step();
        assertEquals(0F, hall.air(OX, OY + 5, OZ));
    }

    @Test
    void aWallSheltersTheCellsBehindIt() {
        // a wall three high just upwind of the burst cell, the wind along +x
        int wall = OX - 1;
        SmokeGrid.Mask m = (x, y, z) -> y < OY || x == wall && y <= OY + 2;
        SmokeGrid g = new SmokeGrid(SmokeKind.SMOKE, 9L, 1F, 0F, 1F, CLEAR, 0F);
        g.scan(m);
        g.place();
        for (int k = 1; k <= SmokeGrid.LEE; k++) assertEquals(k / (float) (SmokeGrid.LEE + 1), g.air(wall + k, OY, OZ), 1e-6F, k + " behind the wall");
        assertEquals(1F, g.air(wall + SmokeGrid.LEE + 1, OY, OZ));
        assertEquals(1F, g.air(wall + 1, OY + 3, OZ), "over the wall's top");
        assertEquals(1F, g.air(wall - 1, OY, OZ), "before the wall");
    }

    // a room 7 by 7 and 3 high round the burst cell, its roof on top; a window in the +x wall when asked
    private static SmokeGrid.Mask room(boolean window) {
        return (x, y, z) -> {
            if (y < OY) return true;
            boolean in = Math.abs(x - OX) <= 4 && Math.abs(z - OZ) <= 4;
            if (!in || y > OY + 3) return false;
            if (y == OY + 3) return true;
            boolean wallCell = Math.abs(x - OX) == 4 || Math.abs(z - OZ) == 4;
            if (window && x == OX + 4 && Math.abs(z - OZ) <= 1 && y <= OY + 1) return false;
            return wallCell;
        };
    }

    // the gas in the room, wherever the box went
    private static float inRoom(SmokeGrid g) {
        float sum = 0F;
        for (int y = OY; y < OY + 3; y++) for (int z = OZ - 3; z <= OZ + 3; z++) for (int x = OX - 3; x <= OX + 3; x++)
            sum += g.density(x - g.shiftX(), y, z - g.shiftZ());
        return sum;
    }

    private static SmokeGrid gas(SmokeGrid.Mask m, float wx, float wz, int ticks) {
        SmokeGrid g = new SmokeGrid(GasKind.ACID.smoke(), 9L, 1F, 0F, 0.4F, wx, wz);
        g.scan(m);
        g.place();
        for (int t = 0; t < ticks; t++) {
            g.step();
            g.drift(m);
        }
        return g;
    }

    @Test
    void theStrongerTheWindTheFasterARoomAirsOut() {
        // what is left in a room with a window after 10 s against a calm day: about 60% in the clear, a third in the
        // rain, a sixth in a thunderstorm
        float[] most = {0.7F, 0.45F, 0.25F}, least = {0.5F, 0.25F, 0.05F};
        float[] speeds = {CLEAR, RAIN, STORM};
        for (int k = 0; k < 3; k++) {
            float left = inRoom(gas(room(true), speeds[k], 0F, 200)) / inRoom(gas(room(true), 0F, 0F, 200));
            System.out.println("window, wind " + speeds[k] * 20F + " blocks/s: " + left + " left after 10 s");
            assertTrue(left < most[k] && left > least[k], speeds[k] * 20F + " blocks/s leaves " + left);
        }
    }

    @Test
    void anOpenWindowAirsTheRoomOutAndAShutRoomKeepsItsGas() {
        int t = 200;
        SmokeGrid shutCalm = gas(room(false), 0F, 0F, t), shutWind = gas(room(false), RAIN, 0F, t);
        SmokeGrid openCalm = gas(room(true), 0F, 0F, t), openWind = gas(room(true), RAIN, 0F, t), openWindAcross = gas(room(true), 0F, RAIN, t);
        System.out.println("room after " + t + " ticks: shut " + inRoom(shutCalm) + " / with wind " + inRoom(shutWind) + "; window "
                + inRoom(openCalm) + " / wind at it " + inRoom(openWind) + " / wind along the wall " + inRoom(openWindAcross));
        assertEquals(shutCalm.hash(), shutWind.hash(), "a shut room keeps its gas whatever the wind");
        assertTrue(inRoom(openWind) < inRoom(openCalm) * 0.75F, "the wind airs a room with an open window: " + inRoom(openWind) + " against " + inRoom(openCalm));
        assertTrue(inRoom(openWindAcross) < inRoom(openCalm) * 0.75F, "whichever way it blows");
    }

    @Test
    void aHeavyGasRunsDownADeepPit() {
        // a pit 6 deep beside the burst: a heavy gas's box reaches 8 below it, smoke's only 4
        int below = GasKind.CHLORINE.smoke().below();
        assertEquals(SmokeGrid.OY_HEAVY, below);
        assertEquals(OY, SmokeKind.SMOKE.below());
        assertEquals(OY, GasKind.ACID.smoke().below());
        SmokeGrid.Mask pit = (x, y, z) -> y < below - 6 || y < below && !(x >= OX + 2 && x <= OX + 5 && Math.abs(z - OZ) <= 2);
        SmokeGrid g = new SmokeGrid(GasKind.CHLORINE.smoke(), 5L, 1F, 0F, 1F);
        g.scan(pit);
        g.place();
        for (int t = 0; t < 200; t++) g.step();
        for (int y = below - 6; y <= below; y++) System.out.println("pit layer " + (y - below) + ": " + g.density(OX + 3, y, OZ));
        // the gas goes down as far as its densest cells sink: five blocks here in 10 s, where smoke's box ends at four
        float deep = g.density(OX + 3, below - 5, OZ);
        assertTrue(deep > 0.05F, "the gas lies five blocks down the pit: " + deep);
        assertTrue(g.density(OX + 3, below - 1, OZ) > 0.5F);
    }
}
