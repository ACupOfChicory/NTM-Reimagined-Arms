package acup.gunfeel.nuke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.hbm.packet.toclient.HazePayload;
import com.hbm.packet.toclient.MukePayload;

class MiniNukeCloudTest {
    @Test
    void takesOnlyTheMukePacket() {
        assertTrue(MiniNukeCloud.takes(new MukePayload(0, 64, 0, false, false)));
        assertTrue(MiniNukeCloud.takes(new MukePayload(0, 64, 0, true, true)));
        assertFalse(MiniNukeCloud.takes(new HazePayload(0, 64, 0)));
        assertFalse(MiniNukeCloud.takes(null));
        assertFalse(MiniNukeCloud.takes("muke"));
    }

    @Test
    void scaledAsNtmScalesABombByItsYield() {
        // NTM: EntityNukeTorex.statFac(yield) -> yield * 0.01; the Fat Man bomb's 175 -> 1.75
        assertEquals(1.75F, MiniNukeCloud.scale(175F), 1e-6);
        assertEquals(0.1F, MiniNukeCloud.scale(10F), 1e-6);
        assertEquals(0.08F, MiniNukeCloud.scale(8F), 1e-6);
        assertEquals(0.2F, MiniNukeCloud.scale(20F), 1e-6);
        assertEquals(5F, MiniNukeCloud.scale(10000F), 1e-6);
        assertTrue(MiniNukeCloud.scale(0F) > 0F);
    }

    @Test
    void aSmallCloudIsHotAtFirstAsABombIs() {
        // NTM: maxHeat = (int) (50 * s * s), heat = maxHeat - (maxHeat * age / maxAge)^0.6; at s 1 the same
        for (int age : new int[] {0, 100, 450, 899}) {
            double ntm = 50 - Math.pow(50.0 * age / 900, 0.6);
            assertEquals(ntm, MiniNukeCloud.heat(1, age, 900), 1e-9);
        }
        // a radius 10 blast: NTM's heat is (int) 0.5 = 0, grey from the start; here a bomb's scaled by s squared
        double s = MiniNukeCloud.scale(10F);
        int maxAge = (int) (900 * s);
        double hot = MiniNukeCloud.heat(s, 0, maxAge), end = MiniNukeCloud.heat(s, maxAge, maxAge);
        assertEquals(0.5, hot, 1e-6);
        assertTrue(end > 0 && end < hot, "cools, never to nothing or below");
        assertEquals(MiniNukeCloud.heat(1, 30, 90) * s * s, MiniNukeCloud.heat(s, 30, 90), 1e-9);
    }

    @Test
    void sizeFromTheBlastAtThePointThisTick() {
        Object level = new Object(), other = new Object();
        MiniNukeCloud.mark(level, 100, 64, 100, 15F, 50);
        assertEquals(15F, MiniNukeCloud.sizeAt(level, 100, 64.5, 100, 50));
        assertEquals(MiniNukeCloud.DEFAULT_SIZE, MiniNukeCloud.sizeAt(level, 100, 64.5, 100, 51), "another tick");
        assertEquals(MiniNukeCloud.DEFAULT_SIZE, MiniNukeCloud.sizeAt(level, 110, 64.5, 100, 50), "another point");
        assertEquals(MiniNukeCloud.DEFAULT_SIZE, MiniNukeCloud.sizeAt(other, 100, 64.5, 100, 50), "another level");
        MiniNukeCloud.mark(level, 0, 64, 0, 0F, 60);
        assertEquals(MiniNukeCloud.DEFAULT_SIZE, MiniNukeCloud.sizeAt(level, 0, 64, 0, 60), "no radius, no hint");
    }

    @Test
    void aTinyTotVolleyMakesOneCloud() {
        Object level = new Object();
        assertFalse(MiniNukeCloud.joinsVolley(level, 0, 64, 0, 1000));
        for (int i = 1; i < 8; i++) assertTrue(MiniNukeCloud.joinsVolley(level, i * 1.5, 64, -i, 1000 + i % 2));
        assertFalse(MiniNukeCloud.joinsVolley(level, 40, 64, 0, 1001), "far off: a cloud of its own");
        assertFalse(MiniNukeCloud.joinsVolley(level, 40, 64, 0, 1010), "a later volley");
    }

    @Test
    void shockRingFitsTheBlast() {
        // a bomb's: 150 ticks, 100 puffs a tick; a Fat Mine's (scale 0.1) shorter and thinner
        assertEquals(150, MiniNukeCloud.shockTicks(5));
        assertEquals(100, MiniNukeCloud.shockPuffs(1));
        // out to three radii (where the waste ends) at a bomb's 1.5 blocks a tick, puffs enough and big enough to read as a ring
        assertEquals(20, MiniNukeCloud.shockTicks(0.1));
        assertEquals(MiniNukeCloud.wasteRadius(10F), MiniNukeCloud.shockTicks(0.1) * 1.5, 1e-6);
        assertEquals(60, MiniNukeCloud.shockPuffs(0.1));
        assertEquals(2.5F, MiniNukeCloud.shockWidth(0.5F));
        assertEquals(1F, MiniNukeCloud.shockHeight(0.2F));
        assertEquals(5F, MiniNukeCloud.shockWidth(5F), "a bigger one keeps NTM's");
        // NTM's puff goes out at the scale times 0.25 times its motion: at the ring's 1.5 a tick
        assertEquals(1.5, MiniNukeCloud.shockMotion(0.1) * 0.1 * 0.25, 1e-9);
        assertTrue(MiniNukeCloud.shockTicks(0.08) < MiniNukeCloud.shockTicks(0.2));
        assertTrue(MiniNukeCloud.shockTicks(0.001) >= 8 && MiniNukeCloud.shockPuffs(0.001) >= 8);
    }

    @Test
    void theRingLeavesSmokeLyingAsBefore() {
        // NTM's own puffs as many a tick as before the ring (40 at a Fat Mine's), out to four radii (27 ticks)
        assertEquals(40, MiniNukeCloud.smokePuffs(0.1));
        assertEquals(27, MiniNukeCloud.smokeTicks(0.1));
        assertTrue(MiniNukeCloud.smokeTicks(0.1) >= MiniNukeCloud.shockTicks(0.1), "the smoke as far as the ring goes");
        // of a tick's 60 + 40 the ring's 60, spread through it
        int ring = 0, run = 0, most = 0;
        for (long n = 0; n < 100; n++) {
            boolean r = MiniNukeCloud.ringPuff(n, 60, 40);
            if (r) ring++;
            run = r ? run + 1 : 0;
            most = Math.max(most, run);
        }
        assertEquals(60, ring);
        assertTrue(most <= 2, "spread, not in a lump");
        assertTrue(MiniNukeCloud.ringPuff(5, 60, 0), "no smoke: all the ring's");
    }
}
