package acup.gunfeel.gl54;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

// The GL-54's recipes (data/ntmgunfeel/recipe) are data: the game rejects one that does not load, so every file is read
// here. The JSON is valid, each item and tag in it is in NTM's jar (or this mod's), the fluids are ones NTM's own recipes use,
// a shaped pattern uses only its keys, and every magazine a recipe makes holds the grenades it should.
class Gl54RecipesTest {
    static final Path RECIPES = Path.of("src/main/resources/data/ntmgunfeel/recipe");
    static final Path OWN_ASSETS = Path.of("src/main/resources/assets/ntmgunfeel/items");
    static final Path OWN_TAGS = Path.of("src/main/resources/data/ntmgunfeel/tags/item");
    // the tags this mod adds under NTM's namespace (the new gun mechanisms)
    static final Path OWN_HBM_TAGS = Path.of("src/main/resources/data/hbm/tags/item");

    static List<Path> files() throws IOException {
        try (Stream<Path> s = Files.walk(RECIPES)) {
            // ntm/: NTM's own recipes the balance's and the mechanisms' replace, balance/ and mechanisms/: Tesla Cannon's
            // other two (BalanceStage1vTest and GunMechanismsTest check them against NTM's jar)
            return s.filter(p -> p.toString().endsWith(".json") && java.util.stream.Stream.of("ntm", "balance", "mechanisms").noneMatch(RECIPES.relativize(p)::startsWith)).sorted().toList();
        }
    }

    static JsonObject read(Path p) throws IOException {
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    static ZipFile ntm() throws Exception {
        return new ZipFile(Path.of(Class.forName("com.hbm.items.ModItems", false, Gl54RecipesTest.class.getClassLoader()).getProtectionDomain().getCodeSource().getLocation().toURI()).toFile());
    }

    static void collect(JsonElement e, List<String> ids, List<String> fluids) {
        if (e.isJsonPrimitive()) {
            ids.add(e.getAsString());
        } else if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) collect(x, ids, fluids);
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("ingredient")) {
                JsonElement i = o.get("ingredient");
                if (i.isJsonObject() && i.getAsJsonObject().has("fluid")) fluids.add(i.getAsJsonObject().get("fluid").getAsString());
                else collect(i, ids, fluids);
            }
        }
    }

    @Test
    void itemsTagsAndFluidsExist() throws Exception {
        try (ZipFile jar = ntm()) {
            Set<String> fluidsOfNtm = new HashSet<>();
            for (var en = jar.entries(); en.hasMoreElements();) {
                var z = en.nextElement();
                if (!z.getName().startsWith("data/hbm/recipe/") || !z.getName().endsWith(".json")) continue;
                String text = new String(jar.getInputStream(z).readAllBytes(), StandardCharsets.UTF_8);
                for (var m = java.util.regex.Pattern.compile("\"(?:type|fluid)\":\\s*\"(hbm:[a-z_]+)\"").matcher(text); m.find();) fluidsOfNtm.add(m.group(1));
            }
            for (Path p : files()) {
                JsonObject r = read(p);
                List<String> ids = new ArrayList<>(), fluids = new ArrayList<>();
                if (r.has("key")) for (var k : r.getAsJsonObject("key").entrySet()) collect(k.getValue(), ids, fluids);
                if (r.has("input_items")) collect(r.get("input_items"), ids, fluids);
                if (r.has("input_fluids")) for (JsonElement f : r.getAsJsonArray("input_fluids")) fluids.add(f.getAsJsonObject().get("type").getAsString());
                for (String id : ids) {
                    boolean tag = id.startsWith("#");
                    String[] ns = (tag ? id.substring(1) : id).split(":", 2);
                    if (tag) {
                        assertTrue(jar.getEntry("data/" + ns[0] + "/tags/item/" + ns[1] + ".json") != null
                                || Files.exists((ns[0].equals("hbm") ? OWN_HBM_TAGS : OWN_TAGS).resolve(ns[1] + ".json")), p + ": no tag " + id);
                    } else if (ns[0].equals("ntmgunfeel")) {
                        assertTrue(Files.exists(OWN_ASSETS.resolve(ns[1] + ".json")), p + ": no item " + id);
                    } else {
                        assertNotNull(jar.getEntry("assets/" + ns[0] + "/items/" + ns[1] + ".json"), p + ": no item " + id);
                    }
                }
                for (String f : fluids) assertTrue(fluidsOfNtm.contains(f), p + ": no fluid " + f + " in NTM's own recipes");
                if (r.has("result")) {
                    String id = r.getAsJsonObject("result").get("id").getAsString();
                    assertTrue(id.startsWith("ntmgunfeel:") && Files.exists(OWN_ASSETS.resolve(id.substring(11) + ".json")), p + ": result " + id);
                }
            }
        }
    }

    // the press's grid is exactly three rows of three cells (the game refuses a recipe with any other pattern)
    @Test
    void pressPatternsAreThreeByThree() throws IOException {
        for (Path p : files()) {
            JsonObject r = read(p);
            if (!r.get("type").getAsString().equals("hbm:ammo_press")) continue;
            assertEquals(3, r.getAsJsonArray("pattern").size(), p.toString());
            for (JsonElement row : r.getAsJsonArray("pattern")) assertEquals(3, row.getAsString().length(), p.toString());
        }
    }

    @Test
    void patternsUseOnlyTheirKeys() throws IOException {
        for (Path p : files()) {
            JsonObject r = read(p);
            if (!r.has("pattern")) continue;
            Set<Character> used = new HashSet<>(), keys = new HashSet<>();
            for (JsonElement row : r.getAsJsonArray("pattern")) for (char c : row.getAsString().toCharArray()) if (c != ' ') used.add(c);
            for (var k : r.getAsJsonObject("key").keySet()) keys.add(k.charAt(0));
            assertEquals(keys, used, p.toString());
        }
    }

    static MagContents made(JsonObject r) {
        JsonObject out = r.getAsJsonArray("output_items").get(0).getAsJsonObject();
        assertEquals("ntmgunfeel:gl54_mag", out.get("id").getAsString());
        return MagContents.CODEC.parse(JsonOps.INSTANCE, out.getAsJsonObject("components").get("ntmgunfeel:gl54_mag")).getOrThrow();
    }

    @Test
    void presetsMakeEightOfTheirKindAndTheExampleItsOrder() throws IOException {
        Set<String> kinds = new HashSet<>();
        for (GrenadeKind k : GrenadeKind.values()) {
            JsonObject r = read(RECIPES.resolve("ammo_press/gl54_preset_" + k.key + ".json"));
            MagContents c = made(r);
            assertEquals(8, c.size());
            assertEquals(k.key, c.kind());
            // eight grenades of the kind in cells 0..7, the empty magazine in cell 8
            assertEquals(List.of("AAA", "AAA", "AAM"), List.of(r.getAsJsonArray("pattern").get(0).getAsString(), r.getAsJsonArray("pattern").get(1).getAsString(), r.getAsJsonArray("pattern").get(2).getAsString()));
            assertEquals("ntmgunfeel:gl54_grenade_" + k.key, r.getAsJsonObject("key").getAsJsonObject("A").get("ingredient").getAsString());
            assertEquals("ntmgunfeel:gl54_mag_empty", r.getAsJsonObject("key").getAsJsonObject("M").get("ingredient").getAsString());
            kinds.add(k.key);
        }
        assertEquals(9, kinds.size());
        JsonObject ex = read(RECIPES.resolve("ammo_press/gl54_preset_example.json"));
        assertEquals(List.of("airburst", "airburst", "he", "he", "ap", "ap", "inc", "smoke"), made(ex).rounds());
        // the pattern, cell by cell, gives that order
        StringBuilder cells = new StringBuilder();
        for (JsonElement row : ex.getAsJsonArray("pattern")) cells.append(row.getAsString());
        List<String> order = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String id = ex.getAsJsonObject("key").getAsJsonObject(String.valueOf(cells.charAt(i))).get("ingredient").getAsString();
            order.add(id.substring("ntmgunfeel:gl54_grenade_".length()));
        }
        assertEquals(made(ex).rounds(), order);
        assertEquals('m', cells.charAt(8));
    }

    @Test
    void everyKindHasARecipeMakingFourGrenadesAndTheGunAndTheEmptyMagazineAreThere() throws IOException {
        Set<String> made = new HashSet<>();
        for (GrenadeKind k : GrenadeKind.values()) {
            String name = switch (k) {
                case AIRBURST, AP, HE, SMOKE -> "ammo_press/gl54_" + k.key + ".json";
                case INCENDIARY -> "ammo_press/gl54_inc.json";
                default -> "assembly_machine/gl54_" + k.key + ".json";
            };
            JsonObject r = read(RECIPES.resolve(name));
            JsonObject out = r.getAsJsonArray("output_items").get(0).getAsJsonObject();
            assertEquals("ntmgunfeel:gl54_grenade_" + k.key, out.get("id").getAsString(), name);
            assertEquals(4, out.get("count").getAsInt(), name);
            made.add(k.key);
        }
        assertEquals(9, made.size());
        assertEquals("ntmgunfeel:gun_gl54", read(RECIPES.resolve("gun_gl54.json")).getAsJsonObject("result").get("id").getAsString());
        JsonObject empty = read(RECIPES.resolve("gl54_mag_empty.json"));
        assertEquals("ntmgunfeel:gl54_mag_empty", empty.getAsJsonObject("result").get("id").getAsString());
        assertEquals(2, empty.getAsJsonObject("result").get("count").getAsInt());
    }

    // the gun's recipe: technetium steel's tier (the resistant alloy's barrel and receiver, the military grade board); its
    // mechanism is technetium steel's with gunMechanismRecipes on, as Lag's, the minigun's and the missile launcher's, and
    // weapon steel's with it off, as NTM's recipes of these; the two files differ in nothing else
    @Test
    void theGunIsOfTechnetiumsTierWithTheMechanismOfTheSetting() throws IOException {
        JsonObject on = read(RECIPES.resolve("gun_gl54.json")), off = read(RECIPES.resolve("gun_gl54_weapon_steel.json"));
        assertEquals("[{\"type\":\"ntmgunfeel:gun_mechanism_recipes\",\"on\":true}]", on.get("neoforge:conditions").toString());
        assertEquals("[{\"type\":\"ntmgunfeel:gun_mechanism_recipes\",\"on\":false}]", off.get("neoforge:conditions").toString());
        assertEquals("[\"FCV\",\"BRM\",\"G S\"]", on.get("pattern").toString());
        JsonObject k = on.getAsJsonObject("key");
        assertEquals("hbm:circuit_advanced", k.get("C").getAsString());
        assertEquals("#hbm:barrels_heavy/any_resistant_alloy", k.get("B").getAsString());
        assertEquals("#hbm:receivers_light/any_resistant_alloy", k.get("R").getAsString());
        assertEquals("#hbm:gun_mechanisms/tc_alloy", k.get("M").getAsString());
        assertEquals("#hbm:gun_mechanisms/weapon_steel", off.getAsJsonObject("key").get("M").getAsString());
        JsonObject a = on.deepCopy(), b = off.deepCopy();
        for (JsonObject o : List.of(a, b)) {
            o.remove("neoforge:conditions");
            o.getAsJsonObject("key").remove("M");
        }
        assertEquals(a, b);
    }

    @Test
    void gasesGoByThePipeWithPlastic() throws IOException {
        for (String g : new String[] {"chlorine", "phosgene", "acid", "mustard"}) {
            JsonObject r = read(RECIPES.resolve("assembly_machine/gl54_" + g + ".json"));
            assertTrue(r.getAsJsonArray("input_fluids").get(0).getAsJsonObject().get("amount").getAsInt() > 0, g);
            assertTrue(r.toString().contains("#hbm:ingots/any_plastic"), g);
        }
        // the mustard gas costs the most
        assertEquals(1000, read(RECIPES.resolve("assembly_machine/gl54_mustard.json")).getAsJsonArray("input_fluids").get(0).getAsJsonObject().get("amount").getAsInt());
    }
}
