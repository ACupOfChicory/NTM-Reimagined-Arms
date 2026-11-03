package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Properties;

import org.junit.jupiter.api.Test;

// The hole limits and life as the port's Tracers applies them, from the config as the game reads it: the quality
// presets set holeMax, holeMaxMachines and holeLife, a key changed by hand keeps its value (26.2's ConfigTest checks
// the layering itself; here the numbers it gives go through HoleLimits and HoleMath like the holes in game).
class HoleConfigTest {
    static final class Hole {
        final long born;

        Hole(long born) {
            this.born = born;
        }
    }

    static Properties builtin() {
        Properties p = new Properties();
        for (String k : Config.keys()) p.setProperty(k, Config.codeValue(k));
        return p;
    }

    static Properties file(String... kv) {
        Properties p = builtin();
        for (int i = 0; i < kv.length; i += 2) p.setProperty(kv[i], kv[i + 1]);
        return p;
    }

    // Tracers.trimHoles and holes(): the block limit, then the life
    static ArrayDeque<Hole> shoot(Properties p, int hits, long gapNanos, long now) {
        int max = Math.round(Float.parseFloat(p.getProperty("holeMax")));
        ArrayDeque<Hole> holes = new ArrayDeque<>();
        for (int i = 0; i < hits; i++) {
            holes.add(new Hole(i * gapNanos));
            HoleLimits.trim(holes, h -> false, h -> h.born, false, max);
        }
        HoleMath.expire(holes, now, HoleMath.lifeNanos(Float.parseFloat(p.getProperty("holeLife"))), h -> h.born);
        return holes;
    }

    @Test
    void qualityPresetsLimitTheHoles() {
        // high (the built-in defaults): 500 hits on blocks
        Properties high = Config.layered(builtin(), file("quality", "high"));
        assertEquals(500, shoot(high, 800, 1, 800).size());
        // low: 100, the oldest go first
        Properties low = Config.layered(builtin(), file("quality", "low"));
        ArrayDeque<Hole> holes = shoot(low, 150, 1, 150);
        assertEquals(100, holes.size());
        assertEquals(50, holes.peekFirst().born);
        // realism: 2500
        assertEquals(2500, shoot(Config.layered(builtin(), file("quality", "realism")), 3000, 1, 3000).size());
        // holeMax set by hand beside a preset keeps its value
        assertEquals(321, shoot(Config.layered(builtin(), file("quality", "low", "holeMax", "321")), 400, 1, 400).size());
    }

    @Test
    void qualityPresetsSetTheLife() {
        long second = 1_000_000_000L;
        // low keeps holes 10 s: of one hit a second for 30 s, the last ten stay (the one made exactly 10 s ago too)
        Properties low = Config.layered(builtin(), file("quality", "low"));
        assertEquals("10", low.getProperty("holeLife"));
        ArrayDeque<Hole> holes = shoot(low, 30, second, 29 * second);
        assertEquals(11, holes.size());
        assertTrue(holes.peekFirst().born >= 19 * second);
        // high: 30 s, all 30 stay
        assertEquals(30, shoot(Config.layered(builtin(), file("quality", "high")), 30, second, 29 * second).size());
        // the machine limit is its own key in every preset
        assertEquals("30", low.getProperty("holeMaxMachines"));
        assertEquals("750", Config.layered(builtin(), file("quality", "realism")).getProperty("holeMaxMachines"));
    }
}
