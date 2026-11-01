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

// The head zones on the poses 1.7.10 takes from every living type of the dev game (MobsScenario's dump, NTM 1.0.27
// X5863 and vanilla): 26.2's head markup (ntmgunfeel-heads.json) has to find the head on each mob it marks.
class HeadsPortTest {
    record Pose(String name, String key, float yaw, List<ModelBox> boxes) {}

    static Map<String, Pose> poses() throws Exception {
        Map<String, Pose> out = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(HeadsPortTest.class.getResourceAsStream("/ntmgunfeel-mob-poses-1710.jsonl"), StandardCharsets.UTF_8))) {
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
                out.put(o.get("name").getAsString(), new Pose(o.get("name").getAsString(), EntityKeys.of(o.get("name").getAsString()), o.get("yaw").getAsFloat(), boxes));
            }
        }
        return out;
    }

    // marked in 26.2, no head here: the cyber crab's head is its shell, one mesh in 26.2 and 20 cubes in 1.7.10; the
    // bat of 26.2 is another model (its head 4 x 3 x 2 px, 1.7.10's a 6 px cube): a head of another size is not taken
    private static final java.util.Set<String> NO_HEAD = new java.util.HashSet<>(java.util.Arrays.asList("hbm:entity_cyber_crab", "minecraft:bat"));

    @Test
    void everyMarkedMobFindsItsHead() throws Exception {
        List<String> bad = new ArrayList<>();
        int marked = 0;
        for (Pose p : poses().values()) {
            var profile = HeadProfiles.get(p.key());
            if (profile == null || profile.none() || NO_HEAD.contains(p.key())) continue;
            marked++;
            var res = ModelHeads.resolve(p.key(), p.boxes(), p.yaw());
            if (res.zones().isEmpty()) bad.add(p.name() + " (" + p.key() + "): " + res.reason());
        }
        assertTrue(marked >= 45, "marked mobs among the poses: " + marked);
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    // the zones are on the head: its cubes, the glyphid's jaws; and where 26.2 marks other parts: the nuclear glyphid's
    // mini nuke, Brenda's body, the ghast's and the squid's body, the first segment of the silverfish and the maggot
    private static final java.util.Set<String> MARKED_SO = new java.util.HashSet<>(java.util.Arrays.asList("Ghast: root/body", "Squid: root/body",
            "Silverfish: root/segment0", "hbm.entity_parasite_maggot: root/segment0", "hbm.entity_glyphid_brenda: mesh/Body"));

    @Test
    void zonesAreOnHeadParts() throws Exception {
        List<String> bad = new ArrayList<>();
        for (Pose p : poses().values()) {
            for (var z : ModelHeads.resolve(p.key(), p.boxes(), p.yaw()).zones()) {
                String part = z.box().part().toLowerCase(java.util.Locale.ROOT);
                if (!part.contains("head") && !part.contains("jaw") && !part.contains("nuke") && !part.contains("beak") && !part.contains("mouth")
                        && !MARKED_SO.contains(p.name() + ": " + z.box().part()))
                    bad.add(p.name() + ": " + z.box().part());
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    @Test
    void ntmMobsUnder1710NamesUse26_2Keys() {
        assertEquals("hbm:entity_ghost", EntityKeys.of("hbm.entity_ntm_ghost"));
        assertEquals("hbm:entity_radiation_blaze", EntityKeys.of("hbm.entity_ntm_radiation_blaze"));
        assertEquals("hbm:entity_glyphid", EntityKeys.of("hbm.entity_glyphid"));
        assertEquals("minecraft:zombified_piglin", EntityKeys.of("PigZombie"));
    }

    // the limbs (ntmgunfeel-limbs.json, the same binding): every marked mob finds them
    @Test
    void everyMarkedMobFindsItsLimbs() throws Exception {
        List<String> bad = new ArrayList<>(), good = new ArrayList<>();
        for (Pose p : poses().values()) {
            if (!ModelHeads.marksLimbs(p.key())) continue;
            var res = ModelHeads.limbs(p.key(), p.boxes(), p.yaw());
            if (res.zones().isEmpty()) bad.add(p.name() + " (" + p.key() + "): " + res.reason());
            else good.add(p.name() + " " + res.zones().size());
        }
        java.nio.file.Files.write(java.nio.file.Paths.get("build/limbs-diag.txt"), (String.join("\n", good) + "\n--\n" + String.join("\n", bad)).getBytes(StandardCharsets.UTF_8));
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }
}
