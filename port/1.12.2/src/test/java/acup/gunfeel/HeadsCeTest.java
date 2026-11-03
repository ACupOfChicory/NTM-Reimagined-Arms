package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// port: the head and limb zones on the poses NTM CE 2.6.1.0 on 1.12.2 draws (MobsScenario's dump of every living
// type of the dev game): 26.2's full markup (ntmgunfeel-heads.json, -limbs.json) finds the head and limbs on each mob
// it marks, by the mob's own profile or a template of a like model (ZoneTemplates)
class HeadsCeTest {
    static Map<String, HeadsPortTest.Pose> poses() throws Exception {
        Map<String, HeadsPortTest.Pose> out = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(HeadsCeTest.class.getResourceAsStream("/ntmgunfeel-mob-poses-ce.jsonl"), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null;) {
                if (line.trim().isEmpty()) continue;
                JsonObject o = new JsonParser().parse(line).getAsJsonObject();
                List<ModelBox> boxes = new ArrayList<>();
                for (JsonElement e : o.getAsJsonArray("boxes")) {
                    JsonObject b = e.getAsJsonObject();
                    JsonArray n = b.getAsJsonArray("n");
                    double[] d = new double[12];
                    for (int i = 0; i < 12; i++) d[i] = n.get(i).getAsDouble();
                    long mesh = 0;
                    if (b.has("mesh")) {
                        JsonArray t = b.getAsJsonArray("mesh");
                        float[] f = new float[t.size()];
                        for (int i = 0; i < f.length; i++) f[i] = t.get(i).getAsFloat();
                        mesh = ModelMesh.register(new ModelMesh(f)).id();
                    }
                    boxes.add(new ModelBox(b.get("part").getAsString(), d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7], d[8], d[9], d[10], d[11], mesh));
                }
                String name = o.get("name").getAsString();
                out.put(name, new HeadsPortTest.Pose(name, o.get("key").getAsString(), o.get("yaw").getAsFloat(), boxes));
            }
        }
        return out;
    }

    @Test
    void theFull26_2MarkupIsInTheJar() {
        assertEquals(218, HeadProfiles.all().size());
    }

    @Test
    void everyMarkedMobFindsItsHead() throws Exception {
        List<String> bad = new ArrayList<>(), good = new ArrayList<>();
        for (HeadsPortTest.Pose p : poses().values()) {
            var res = ModelHeads.resolve(p.key(), p.boxes(), p.yaw());
            String row = p.key() + " " + res.source() + " " + res.zones().size() + " " + res.reason();
            var profile = HeadProfiles.get(p.key());
            if (profile != null && !profile.none() && res.zones().isEmpty()) bad.add(row);
            else good.add(row);
        }
        java.nio.file.Files.write(java.nio.file.Paths.get("build/heads-ce-diag.txt"), (String.join("\n", good) + "\n--\n" + String.join("\n", bad)).getBytes(StandardCharsets.UTF_8));
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    @Test
    void everyMarkedMobFindsItsLimbs() throws Exception {
        List<String> bad = new ArrayList<>(), good = new ArrayList<>();
        for (HeadsPortTest.Pose p : poses().values()) {
            var res = ModelHeads.limbs(p.key(), p.boxes(), p.yaw());
            String row = p.key() + " " + res.source() + " " + res.zones().size() + " " + res.reason();
            if (ModelHeads.marksLimbs(p.key()) && res.zones().isEmpty()) bad.add(row);
            else good.add(row);
        }
        java.nio.file.Files.write(java.nio.file.Paths.get("build/limbs-ce-diag.txt"), (String.join("\n", good) + "\n--\n" + String.join("\n", bad)).getBytes(StandardCharsets.UTF_8));
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }
}
