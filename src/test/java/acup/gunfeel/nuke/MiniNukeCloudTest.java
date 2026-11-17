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
        assertEquals(27, MiniNukeCloud.shockTicks(0.1));
        assertEquals(40, MiniNukeCloud.shockPuffs(0.1));
        assertTrue(MiniNukeCloud.shockTicks(0.08) < MiniNukeCloud.shockTicks(0.2));
        assertTrue(MiniNukeCloud.shockTicks(0.001) >= 8 && MiniNukeCloud.shockPuffs(0.001) >= 8);
    }
}
