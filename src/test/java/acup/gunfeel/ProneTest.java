package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import acup.gunfeel.tacz.CrawlKey;

class ProneTest {
    // the mod ships lying down on: allowed with and without a gun, the view held 45 up and 30 down as TaCZ, the spread
    // halved as TaCZ's lie against its stand
    @Test
    void serverDefaults() throws Exception {
        Properties p = new Properties();
        try (var in = Prone.class.getResourceAsStream("/ntmgunfeel-server.properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        assertEquals("true", p.getProperty("prone"));
        assertEquals("true", p.getProperty("proneWithoutGun"));
        assertEquals("45", p.getProperty("proneMaxUp"));
        assertEquals("30", p.getProperty("proneMaxDown"));
        assertEquals("0.5", p.getProperty("spreadCrawl"));
    }

    // a press each to lie down and get up, half the recoil lying (TaCZ's crawl_recoil_multiplier)
    @Test
    void clientDefaults() {
        assertEquals(false, Config.proneHold);
        assertEquals(0.5F, Config.proneRecoil);
    }

    // the multiplier counts only lying, within 0..1, and a broken value is no change
    @Test
    void factor() {
        assertEquals(1F, Prone.factor(false, 0.5F));
        assertEquals(0.5F, Prone.factor(true, 0.5F));
        assertEquals(0F, Prone.factor(true, -2F));
        assertEquals(1F, Prone.factor(true, 3F));
        assertEquals(1F, Prone.factor(true, Float.NaN));
    }

    // X, not C (NTM's jetpack switch) or Z (NTM's magnet)
    @Test
    void keyIsX() {
        assertEquals("key.keyboard.x", CrawlKey.CRAWL_KEY.getDefaultKey().getName());
    }
}
