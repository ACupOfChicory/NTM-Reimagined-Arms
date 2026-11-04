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

    // 1.12.2 has no attenuation_distance: BulletFx sets the sounds where they fade out as 26.2's, by its table
    @Test
    void fadesAs26() throws IOException {
        Map<String, Float> root = new TreeMap<String, Float>();
        try (Reader r = Files.newBufferedReader(PinnedPortTest.REPO.resolve("src/main/resources/assets/ntmgunfeel/sounds.json"), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, JsonElement> e : new JsonParser().parse(r).getAsJsonObject().entrySet())
                for (JsonElement s : e.getValue().getAsJsonObject().getAsJsonArray("sounds")) {
                    float f = s.isJsonObject() && s.getAsJsonObject().has("attenuation_distance")
                            ? s.getAsJsonObject().get("attenuation_distance").getAsFloat() : 16F;
                    Float was = root.put("ntmgunfeel:" + e.getKey(), f);
                    assertTrue(was == null || was == f, e.getKey() + ": one fade for all its files");
                }
        }
        for (Map.Entry<String, Float> e : root.entrySet()) assertEquals(e.getValue(), BulletFx.fade26(e.getKey()), 1e-6F, e.getKey());
        for (String id : BulletFx.FADE_26.keySet()) assertTrue(root.containsKey(id), id);
        assertEquals(16F, BulletFx.fade26("minecraft:block.stone.hit"), 1e-6F);
    }

    // a sound set by BulletFx.faded fades out with 1.12.2's 16 blocks where 26.2's fades out with its own
    @Test
    void fadedKeepsTheSideAndTheFade() {
        double[] ear = {10, 65.62, -3}, at = {12, 66, 1};
        double d = Math.sqrt(4 + 0.38 * 0.38 + 16);
        for (double over : new double[] {8, 12, 32, 40}) {
            double[] p = BulletFx.faded(ear, at, over);
            double dp = Math.sqrt(Math.pow(p[0] - ear[0], 2) + Math.pow(p[1] - ear[1], 2) + Math.pow(p[2] - ear[2], 2));
            assertEquals(1 - d / over, 1 - dp / 16, 1e-9, "over " + over);
            for (int i = 0; i < 3; i++) assertEquals((at[i] - ear[i]) / d, (p[i] - ear[i]) / dp, 1e-9);
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
