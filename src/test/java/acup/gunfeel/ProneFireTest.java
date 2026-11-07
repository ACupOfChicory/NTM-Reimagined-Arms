package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class ProneFireTest {
    // the mod ships the rule on for every gun but the drill and the charge thrower (tools), at 0.25 blocks a second: a
    // server turns it off with proneCrawlNoFire = false
    @Test
    void onByDefault() throws Exception {
        Properties p = new Properties();
        try (var in = ProneFire.class.getResourceAsStream("/ntmgunfeel-server.properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        assertEquals("true", p.getProperty(ProneFire.KEY));
        assertEquals(ProneFire.DEFAULT_SPEED, Float.parseFloat(p.getProperty(ProneFire.SPEED)));
        java.util.Set<String> own = new java.util.TreeSet<>();
        for (String key : p.stringPropertyNames()) if (key.endsWith("." + ProneFire.KEY)) own.add(key + " = " + p.getProperty(key));
        assertEquals("[hbm:gun_charge_thrower.proneCrawlNoFire = false, hbm:gun_drill.proneCrawlNoFire = false]", own.toString());
    }

    // crawling along (about 1.3 blocks a second, 0.065 a tick) is over the line in any direction; lying still, or the
    // drift a few ticks after the key is let go, is not
    @Test
    void crawlsOverTheLine() {
        double line = ProneFire.DEFAULT_SPEED / 20.0;
        assertTrue(ProneFire.crawls(0.065, 0, line));
        assertTrue(ProneFire.crawls(-0.046, 0.046, line));
        assertFalse(ProneFire.crawls(0, 0, line));
        assertFalse(ProneFire.crawls(0.0125, 0, line));
        // vanilla's ground friction (0.6 x 0.91 a tick) brings a crawl under the line on the third tick
        double v = 0.065;
        int ticks = 0;
        while (ProneFire.crawls(v, 0, line)) {
            v *= 0.546;
            ticks++;
        }
        assertEquals(3, ticks);
    }
}
