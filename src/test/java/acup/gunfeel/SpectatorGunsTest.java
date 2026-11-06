package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import acup.gunfeel.compat.Feature;

class SpectatorGunsTest {
    // the mod ships the rule switched on: a server turns it off with spectatorNoGuns = false
    @Test
    void onByDefault() throws Exception {
        Properties p = new Properties();
        try (var in = SpectatorGuns.class.getResourceAsStream("/ntmgunfeel-server.properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        assertEquals("true", p.getProperty(SpectatorGuns.KEY));
    }

    // the server half (keys, the gun put away) loads on a dedicated server, the client half (sending keys, HUD) only on a client
    @Test
    void mixinsOnTheirSides() throws Exception {
        String json;
        try (var in = SpectatorGuns.class.getResourceAsStream("/ntmgunfeel-hbm.mixins.json")) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String common = json.substring(json.indexOf("\"mixins\""), json.indexOf("\"client\""));
        String client = json.substring(json.indexOf("\"client\""));
        for (String m : new String[] {"MixinSpectatorGun"}) assertTrue(common.contains('"' + m + '"'), m);
        for (String m : new String[] {"MixinSpectatorKeys", "MixinSpectatorHud"}) assertTrue(client.contains('"' + m + '"'), m);
        assertEquals(3, Feature.SPECTATOR.mixins.size());
    }
}
