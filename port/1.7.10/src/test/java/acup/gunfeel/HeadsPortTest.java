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
        // the dev game's types, then the other mods' of the owner's pack (Et Futurum Requiem and others; the pack stand)
        for (String file : new String[] {"/ntmgunfeel-mob-poses-1710.jsonl", "/ntmgunfeel-mob-poses-pack-1710.jsonl"})
        try (BufferedReader r = new BufferedReader(new InputStreamReader(HeadsPortTest.class.getResourceAsStream(file), StandardCharsets.UTF_8))) {
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

    // Et Futurum's shulker (ModelShulker, ShulkerRenderer.renderModel): the head is drawn in the renderer's renderModel
    // after the model's base and lid, inside doRender's renderModel call that the capture brackets, and only while the
    // shulker is open (isClosed false or a peek; MobsScenario opens it, the dump's is open). Here the dump's base, the
    // head's cube where ModelShulker puts it (-3, 12, -3, 6 px, about the base's origin) and the lid where
    // setRotationAngles puts it at the peek given (-8, 8, -8, 16 x 12 x 16 px closed).
    static Pose openShulker(float peek) throws Exception {
        Pose open = poses().get("etfuturum.shulker");
        ModelBox base = open.boxes().get(0), lid = open.boxes().get(1);
        assertEquals("root/base", base.part());
        assertEquals("root/lid", lid.part());
        assertEquals("root/head", open.boxes().get(2).part());
        // the base's corner is the model's (-8, 16, -8); a pixel along the model's x, y, z is u/16, v/8, w/16
        double px = base.ux() / 16, py = base.vy() / 8, pz = base.wz() / 16;
        double ox = base.x() + 8 * px, oy = base.y() - 16 * py, oz = base.z() + 8 * pz; // the model's (0, 0, 0)
        double lift = (Math.sin((0.5 + peek) * Math.PI) - 1) * 8; // the lid's rotation point, 24 closed, moves by this
        List<ModelBox> boxes = new ArrayList<>();
        boxes.add(base);
        boxes.add(new ModelBox("root/lid", ox - 8 * px, oy + (8 + lift) * py, oz - 8 * pz, 16 * px, 0, 0, 0, 12 * py, 0, 0, 0, 16 * pz, lid.mesh()));
        ModelBox head = new ModelBox("root/head", ox - 3 * px, oy + 12 * py, oz - 3 * pz, 6 * px, 0, 0, 0, 6 * py, 0, 0, 0, 6 * pz, 0);
        // the dump's head is where the model puts it
        ModelBox taken = open.boxes().get(2);
        assertArrayEquals(new double[] {head.x(), head.y(), head.z(), head.ux(), head.vy(), head.wz()},
                new double[] {taken.x(), taken.y(), taken.z(), taken.ux(), taken.vy(), taken.wz()}, 1e-4);
        boxes.add(head);
        return new Pose("etfuturum.shulker", open.key(), open.yaw(), boxes);
    }

    @Test
    void shulkerOpenFindsItsHead() throws Exception {
        for (float peek : new float[] {0F, 0.3F, 0.5F, 1F}) {
            Pose p = openShulker(peek);
            var res = ModelHeads.resolve(p.key(), p.boxes(), p.yaw());
            assertFalse(res.zones().isEmpty(), "peek " + peek + ": " + res.reason());
            for (var z : res.zones()) assertEquals("root/head", z.box().part(), "peek " + peek);
        }
        // hurt, the renderer draws the head a second time for the red tint: the same cube twice
        Pose p = openShulker(0.3F);
        List<ModelBox> twice = new ArrayList<>(p.boxes());
        twice.add(p.boxes().get(2));
        var res = ModelHeads.resolve(p.key(), twice, p.yaw());
        assertFalse(res.zones().isEmpty(), "hurt: " + res.reason());
    }

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
            "Silverfish: root/segment0", "hbm.entity_parasite_maggot: root/segment0", "hbm.entity_glyphid_brenda: mesh/Body",
            "etfuturum.endermite: root/bodyParts0", "etfuturum.bee: root/bone/body"));

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

    // RandomThings' spirit (RenderSpirit: ModelSlime(16) as the main model, ModelSlime(0) as its pass): 26.2 has no
    // such mob. A slime's model, a shape of its own: no head, as 26.2's slime ("none": the port's markup,
    // ntmgunfeel-heads-1710.json), the whole body hit as drawn: the outer, see-through body taken as the slime's
    // (MixinRendererLivingEntity.gunfeel$pass, solid to a shot as 26.2's slime shell) and the inner one in it
    @Test
    void spiritHasNoHeadAndItsWholeBody() throws Exception {
        Pose p = poses().get("RandomThings.spirit");
        assertEquals("RandomThings:spirit", p.key());
        ModelBox in = p.boxes().get(0), shell = p.boxes().get(p.boxes().size() - 1);
        assertEquals("root/field_78200_a", in.part());
        assertEquals("root/field_78200_a/shell", shell.part());
        // the outer body about the inner one's middle, 8 px to its 6
        double k = 8.0 / 6.0;
        assertArrayEquals(new double[] {in.x() + in.ux() * (1 - k) / 2, in.y() + in.vy() * (1 - k) / 2, in.z() + in.wz() * (1 - k) / 2, in.ux() * k, in.vy() * k, in.wz() * k},
                new double[] {shell.x(), shell.y(), shell.z(), shell.ux(), shell.vy(), shell.wz()}, 1e-4);
        assertTrue(ModelHeads.noHead(p.key()));
        var res = ModelHeads.resolve(p.key(), p.boxes(), p.yaw());
        assertTrue(res.zones().isEmpty(), res.reason());
        assertEquals("dataset", res.source());
    }

    @Test
    void ntmMobsUnder1710NamesUse26_2Keys() {
        assertEquals("hbm:entity_ghost", EntityKeys.of("hbm.entity_ntm_ghost"));
        assertEquals("hbm:entity_radiation_blaze", EntityKeys.of("hbm.entity_ntm_radiation_blaze"));
        assertEquals("hbm:entity_glyphid", EntityKeys.of("hbm.entity_glyphid"));
        assertEquals("minecraft:zombified_piglin", EntityKeys.of("PigZombie"));
        assertEquals("minecraft:stray", EntityKeys.of("etfuturum.stray"));
        assertEquals("minecraft:zombie_villager", EntityKeys.of("etfuturum.villager_zombie"));
        assertEquals("minecraft:snow_golem", EntityKeys.of("chisel.snowman"));
        assertEquals("randomthings:spirit", EntityKeys.of("RandomThings.spirit").toLowerCase(java.util.Locale.ROOT));
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
