package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/** How early the held aim comes back after a reload or an inspect: fixed in the mod, the times /gunaim had by default. */
class ReaimTimesTest {
    @Test
    void reloadIsAlwaysTheDefault() {
        for (String gun : new String[] {"hbm:gun_maresleg", "hbm:gun_double_barrel", "hbm:gun_double_barrel_sacred_dragon", "hbm:gun_g3"}) {
            assertEquals(0.3F, ReaimTimes.seconds(gun, "reload"), gun);
            assertEquals(6, ReaimTimes.ticks(gun, "reload"), gun);
        }
    }

    @Test
    void inspectIsTheDefaultBesidesTheDoubleBarrels() {
        assertEquals(0.3F, ReaimTimes.seconds("hbm:gun_maresleg", "inspect"));
        assertEquals(6, ReaimTimes.ticks("hbm:gun_g3", "inspect"));
        for (String gun : new String[] {"hbm:gun_double_barrel", "hbm:gun_double_barrel_sacred_dragon"}) {
            assertEquals(0.6F, ReaimTimes.seconds(gun, "inspect"), gun);
            assertEquals(12, ReaimTimes.ticks(gun, "inspect"), gun);
        }
    }

    @Test
    void noGunaimCommand() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("acup.gunfeel.ReaimCommand"));
    }

    // the server settings shipped in the jar hold no reaim time any more
    @Test
    void noReaimKeyInTheJar() throws Exception {
        Properties p = new Properties();
        try (var in = ReaimTimes.class.getResourceAsStream("/ntmgunfeel-server.properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        assertFalse(p.stringPropertyNames().stream().anyMatch(k -> k.contains(".reaim")), p.stringPropertyNames().toString());
    }
}
