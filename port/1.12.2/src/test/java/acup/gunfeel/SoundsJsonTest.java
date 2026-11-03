package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// The port's sounds.json (1.7.10's format: names only, no attenuation_distance) has 26.2's events with the same files,
// and every file is there (shared.txt takes the .ogg files from the root).
class SoundsJsonTest {
    private static Map<String, List<String>> events(Path file) throws IOException {
        Map<String, List<String>> out = new TreeMap<String, List<String>>();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = new JsonParser().parse(r).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                List<String> names = new ArrayList<String>();
                for (JsonElement s : e.getValue().getAsJsonObject().getAsJsonArray("sounds"))
                    names.add(s.isJsonObject() ? s.getAsJsonObject().get("name").getAsString() : s.getAsString());
                out.put(e.getKey(), names);
            }
        }
        return out;
    }

    @Test
    void sameEventsAndFilesAs26() throws IOException {
        Map<String, List<String>> port = events(java.nio.file.Paths.get("src/main/resources/assets/ntmgunfeel/sounds.json"));
        Map<String, List<String>> root = events(PinnedPortTest.REPO.resolve("src/main/resources/assets/ntmgunfeel/sounds.json"));
        assertEquals(root, port);
        for (List<String> names : port.values())
            for (String n : names) {
                Path ogg = PinnedPortTest.REPO.resolve("src/main/resources/assets/ntmgunfeel/sounds/" + n.substring(n.indexOf(':') + 1) + ".ogg");
                assertTrue(Files.isRegularFile(ogg), ogg.toString());
            }
    }

    @Test
    void everyEventIsAPlayerSound() throws IOException {
        try (Reader r = Files.newBufferedReader(java.nio.file.Paths.get("src/main/resources/assets/ntmgunfeel/sounds.json"), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, JsonElement> e : new JsonParser().parse(r).getAsJsonObject().entrySet())
                assertEquals("player", e.getValue().getAsJsonObject().get("category").getAsString(), e.getKey());
        }
    }
}
