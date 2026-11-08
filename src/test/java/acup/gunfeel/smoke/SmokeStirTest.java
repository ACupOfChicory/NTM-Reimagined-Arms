package acup.gunfeel.smoke;

import static acup.gunfeel.smoke.SmokeGrid.OX;
import static acup.gunfeel.smoke.SmokeGrid.OY;
import static acup.gunfeel.smoke.SmokeGrid.OZ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import acup.gunfeel.gas.GasKind;
import acup.gunfeel.gas.GasStir;
import net.minecraft.core.BlockPos;

/** Bullets and blasts stirring a gas cloud: how much they take, and that a client's copy stirred late or early ends up the same. */
class SmokeStirTest {
    private static final SmokeGrid.Mask FIELD = (x, y, z) -> y < OY;
    private static final BlockPos ORIGIN = new BlockPos(OX, OY, OZ);
    private static final SmokeKind GAS = GasKind.CHLORINE.smoke();

    private static SmokeClouds.Cloud cloud(SmokeClouds all) {
        return all.create(1, ORIGIN, GAS, 77L, 1F, 0F, 1F, 0L);
    }

    private static void steps(SmokeClouds all, SmokeClouds.Cloud c, long to) {
        all.step(c, to, Integer.MAX_VALUE, null);
    }

    // a shot along x through the cloud's middle, a block over the ground
    private static SmokeClouds.Stir shot(int age) {
        return new SmokeClouds.Stir(age, false, -2, OY + 0.5, OZ + 0.5, 30, OY + 0.5, OZ + 0.5, GasStir.LINE_KEEP, GasStir.LINE_KEEP_NEAR);
    }

    @Test
    void aBulletThinsItsLineAndABurstClearsIt() {
        SmokeClouds all = new SmokeClouds(c -> FIELD);
        SmokeClouds.Cloud c = cloud(all);
        steps(all, c, 60);
        float before = c.grid.density(OX + 2, OY, OZ), beside = c.grid.density(OX + 2, OY, OZ + 1), off = c.grid.density(OX + 2, OY, OZ + 3);
        assertTrue(before > 0.5F, "the line is in the gas: " + before);
        c.stir(shot(c.grid.age()));
        assertEquals(before * GasStir.LINE_KEEP, c.grid.density(OX + 2, OY, OZ), 1e-6F);
        assertEquals(beside * GasStir.LINE_KEEP_NEAR, c.grid.density(OX + 2, OY, OZ + 1), 1e-6F);
        assertEquals(off, c.grid.density(OX + 2, OY, OZ + 3), 0F, "two blocks off the line is left as it was");
        for (int i = 1; i < 10; i++) c.stir(shot(c.grid.age()));
        assertTrue(c.grid.density(OX + 2, OY, OZ) < before * 0.12F, "ten shots leave about a tenth: " + c.grid.density(OX + 2, OY, OZ));
    }

    @Test
    void aGrenadesBlastClearsMostOfTheCloud() {
        SmokeClouds all = new SmokeClouds(c -> FIELD);
        SmokeClouds.Cloud c = cloud(all);
        steps(all, c, 60);
        float before = c.grid.total();
        double reach = Math.min(GasStir.BLAST_REACH_MAX, acup.gunfeel.gl54.Gl54Grenade.RADIUS * GasStir.BLAST_REACH);
        c.stir(new SmokeClouds.Stir(c.grid.age(), true, OX + 0.5, OY + 0.5, OZ + 0.5, reach, 0, 0, GasStir.BLAST_MOST, 0F));
        float after = c.grid.total();
        assertTrue(after < before * 0.45F, "a GL-54 HE blast in the middle takes more than half: " + after / before);
        assertTrue(after > 0F, "the far edge keeps some");
        assertTrue(c.grid.density(OX, OY, OZ) <= before * 0.05F);
    }

    @Test
    void aCopyStirredWhenItGetsThereMatchesTheServer() {
        SmokeClouds server = new SmokeClouds(c -> FIELD), client = new SmokeClouds(c -> FIELD), late = new SmokeClouds(c -> FIELD);
        SmokeClouds.Cloud s = cloud(server), k = cloud(client), l = cloud(late);
        steps(server, s, 50);
        steps(client, k, 30);
        steps(late, l, 50);
        SmokeClouds.Stir st = shot(s.grid.age());
        s.stir(st);
        // a client behind the server keeps it for its age; one there already takes it at once
        k.stir(st);
        l.stir(st);
        assertNotEquals(s.grid.hash(), new SmokeClouds(c -> FIELD).create(1, ORIGIN, GAS, 77L, 1F, 0F, 1F, 0L).grid.hash());
        steps(server, s, 120);
        steps(client, k, 120);
        steps(late, l, 120);
        assertEquals(s.grid.hash(), k.grid.hash(), "the one behind took it at the same age");
        assertEquals(s.grid.hash(), l.grid.hash());
        // and one that never had it differs
        SmokeClouds none = new SmokeClouds(c -> FIELD);
        SmokeClouds.Cloud n = cloud(none);
        steps(none, n, 120);
        assertNotEquals(s.grid.hash(), n.grid.hash());
    }

    @Test
    void aStirSetAheadReachesAClientALittleBehindInTime() {
        // the server stirs for STIR_AHEAD ticks on; a client a tick or two behind gets it before then
        SmokeClouds server = new SmokeClouds(c -> FIELD), client = new SmokeClouds(c -> FIELD);
        SmokeClouds.Cloud s = cloud(server), k = cloud(client);
        steps(server, s, 50);
        steps(client, k, 52);
        SmokeClouds.Stir st = shot(s.grid.age() + SmokeClouds.STIR_AHEAD);
        s.stir(st);
        k.stir(st);
        steps(server, s, 120);
        steps(client, k, 120);
        assertEquals(s.grid.hash(), k.grid.hash());
    }

    @Test
    void aGridFromTheServerDropsTheStirsItHasBeenThrough() {
        SmokeClouds all = new SmokeClouds(c -> FIELD);
        SmokeClouds.Cloud c = cloud(all);
        steps(all, c, 40);
        c.stir(shot(42));
        c.stir(shot(45));
        // a grid at age 44 came: the one for 42 is in it, the one for 45 not yet
        SmokeClouds other = new SmokeClouds(c2 -> FIELD);
        SmokeClouds.Cloud o = cloud(other);
        steps(other, o, 42);
        o.stir(shot(42));
        steps(other, o, 44);
        try {
            SmokeNet.decode(c.grid, SmokeNet.encode(o.grid));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        c.stepped();
        o.stir(shot(45));
        steps(all, c, 100);
        steps(other, o, 100);
        assertEquals(o.grid.hash(), c.grid.hash(), "the stir for 42 was not taken twice");
    }

    @Test
    void stirsWaitInOrderAndNotForEver() {
        SmokeClouds all = new SmokeClouds(c -> FIELD);
        SmokeClouds.Cloud c = cloud(all);
        steps(all, c, 10);
        for (int i = 0; i < SmokeClouds.PENDING_MAX; i++) assertTrue(c.stir(shot(500 - i)));
        assertFalse(c.stir(shot(20)), "past the most waiting");
    }

    @Test
    void aStirFromTheNetworkIsChecked() {
        assertTrue(SmokeClouds.sane(shot(3)));
        assertFalse(SmokeClouds.sane(new SmokeClouds.Stir(3, false, Double.NaN, 0, 0, 1, 1, 1, 0.8F, 0.9F)));
        assertFalse(SmokeClouds.sane(new SmokeClouds.Stir(3, true, 0, 0, 0, 1000, 0, 0, 0.9F, 0F)));
        assertFalse(SmokeClouds.sane(new SmokeClouds.Stir(3, false, 0, 0, 0, 1, 1, 1, 1.5F, 0.9F)));
        assertFalse(SmokeClouds.sane(new SmokeClouds.Stir(-1, false, 0, 0, 0, 1, 1, 1, 0.8F, 0.9F)));
    }

    @Test
    void theNearTestFindsALineThroughTheGasOnly() {
        SmokeClouds all = new SmokeClouds(c -> FIELD);
        SmokeClouds.Cloud c = cloud(all);
        steps(all, c, 60);
        assertTrue(c.near(-2, OY + 0.5, OZ + 0.5, 30, OY + 0.5, OZ + 0.5, 1.0));
        assertFalse(c.near(-2, OY + 9.5, OZ + 0.5, 30, OY + 9.5, OZ + 0.5, 1.0), "high over the cloud");
    }
}
