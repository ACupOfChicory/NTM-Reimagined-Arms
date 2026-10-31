package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

// The server's settings as the port sends them (Net.Settings carries ServerSettings.encode), and the client's
// ReaimTimes going by them while connected: cameraScaleMin, bulletSpeed and the reaim times come from the server
class ServerSettingsPortTest {
    @AfterEach
    void local() {
        ServerSettings.useRemote(null);
    }

    static ServerSettings.Snapshot snapshot() {
        Map<String, String> server = new TreeMap<String, String>();
        server.put("cameraScaleMin", "0.4");
        server.put("bulletSpeed", "2.5");
        server.put("hbm:gun_light_revolver.reaimReload", "0.7");
        return new ServerSettings.Snapshot("break minecraft:glass\n", server, Collections.<String, String>emptyMap());
    }

    @Test
    void roundTrip() throws IOException {
        ServerSettings.Snapshot s = snapshot();
        assertEquals(s, ServerSettings.decode(ServerSettings.encode(s)));
        assertEquals(s, ServerSettings.decode(ServerSettings.encodeFitting(s)));
    }

    @Test
    void shortPacketFails() throws IOException {
        byte[] all = ServerSettings.encode(snapshot());
        byte[] cut = Arrays.copyOf(all, all.length / 2);
        assertThrows(IOException.class, () -> ServerSettings.decode(cut));
    }

    @Test
    void clientGoesByTheServer() {
        ServerSettings.useRemote(snapshot());
        assertEquals(0.4F, ReaimTimes.number("cameraScaleMin", 0.25F));
        assertEquals(2.5F, ReaimTimes.number("bulletSpeed", 1.6F));
        assertEquals(0.7F, ReaimTimes.seconds("hbm:gun_light_revolver", "reload"));
        ServerSettings.useRemote(null);
        assertEquals(2.5F == ReaimTimes.number("bulletSpeed", 1.6F), false);
    }
}
