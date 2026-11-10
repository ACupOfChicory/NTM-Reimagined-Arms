package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// The new gun mechanisms (GunMechanisms, GunMechanismRecipes) without the game: what tools/gun_mechanisms.py wrote,
// against NTM's jar on the test classpath.
class GunMechanismsTest {
    private static final Path RES = Path.of("src/main/resources");
    private static final List<String> MATERIALS = List.of("steel", "dura_steel", "workers_alloy", "ferrouranium", "tc_alloy");
    private static final String MECH = "ntmgunfeel:gun_mechanism_recipes", BAL = "ntmgunfeel:balance_recipes";

    private static byte[] jar(String path) throws IOException {
        for (URL u : Collections.list(GunMechanismsTest.class.getClassLoader().getResources(path))) {
            if (!u.toString().contains("hbm-neoforge")) continue;
            try (InputStream in = u.openStream()) {
                return in.readAllBytes();
            }
        }
        throw new AssertionError("NTM's jar has no " + path);
    }

    private static boolean inJar(String path) throws IOException {
        for (URL u : Collections.list(GunMechanismsTest.class.getClassLoader().getResources(path))) if (u.toString().contains("hbm-neoforge")) return true;
        return false;
    }

    private static JsonObject json(byte[] b) {
        return JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject ours(String path) throws IOException {
        return json(Files.readAllBytes(RES.resolve(path)));
    }

    private static BufferedImage image(byte[] b) throws IOException {
        return ImageIO.read(new java.io.ByteArrayInputStream(b));
    }

    private static double luma(int rgb) {
        return 0.299 * (rgb >> 16 & 255) + 0.587 * (rgb >> 8 & 255) + 0.114 * (rgb & 255);
    }

    // the colours of the solid pixels, dark to light
    private static List<Integer> palette(BufferedImage im) {
        Set<Integer> cols = new HashSet<>();
        for (int y = 0; y < im.getHeight(); y++) for (int x = 0; x < im.getWidth(); x++) {
            int a = im.getRGB(x, y) >>> 24;
            assertTrue(a == 0 || a == 255);
            if (a == 255) cols.add(im.getRGB(x, y) & 0xffffff);
        }
        List<Integer> out = new ArrayList<>(cols);
        out.sort(Comparator.comparingDouble(GunMechanismsTest::luma));
        return out;
    }

    // each pixel's shade: its colour's rank in the palette, -1 clear
    private static int[] mask(BufferedImage im) {
        List<Integer> pal = palette(im);
        int[] m = new int[256];
        for (int i = 0; i < 256; i++) {
            int c = im.getRGB(i % 16, i / 16);
            m[i] = c >>> 24 == 0 ? -1 : pal.indexOf(c & 0xffffff);
        }
        return m;
    }

    // the five textures: NTM's mechanism mask, each pixel's shade the colour of that shade in the material's parts
    @Test
    void textures() throws IOException {
        int[] shape = mask(image(jar("assets/hbm/textures/item/part_mechanism_gun_metal.png")));
        for (String n : List.of("weapon_steel", "saturnite")) assertTrue(java.util.Arrays.equals(shape, mask(image(jar("assets/hbm/textures/item/part_mechanism_" + n + ".png")))), n);
        for (String mat : MATERIALS) {
            BufferedImage im = image(Files.readAllBytes(RES.resolve("assets/hbm/textures/item/part_mechanism_" + mat + ".png")));
            assertEquals(16, im.getWidth());
            assertEquals(16, im.getHeight());
            assertTrue(java.util.Arrays.equals(shape, mask(im)), mat + ": the mask");
            String part = mat.equals("ferrouranium") ? "part_receiver_heavy_" : "part_receiver_light_";
            List<Integer> want = palette(image(jar("assets/hbm/textures/item/" + part + mat + ".png")));
            assertEquals(6, want.size(), mat);
            assertEquals(want, palette(im), mat + ": the palette of " + part + mat);
            assertTrue(!inJar("assets/hbm/textures/item/part_mechanism_" + mat + ".png"), mat + ": NTM has it now");
        }
    }

    @Test
    void itemsTagsLang() throws IOException {
        JsonObject en = ours("assets/ntmgunfeel/lang/en_us.json"), ru = ours("assets/ntmgunfeel/lang/ru_ru.json");
        Set<String> tags = new TreeSet<>();
        try (var s = Files.list(RES.resolve("data/hbm/tags/item/gun_mechanisms"))) {
            s.forEach(f -> tags.add(f.getFileName().toString().replace(".json", "")));
        }
        assertEquals(new TreeSet<>(MATERIALS), tags);
        for (String mat : MATERIALS) {
            String id = "hbm:part_mechanism_" + mat;
            assertEquals(id, ours("assets/hbm/items/part_mechanism_" + mat + ".json").getAsJsonObject("model").get("model").getAsString().replace("item/", ""));
            assertEquals("hbm:item/part_mechanism_" + mat, ours("assets/hbm/models/item/part_mechanism_" + mat + ".json").getAsJsonObject("textures").get("layer0").getAsString());
            JsonArray values = ours("data/hbm/tags/item/gun_mechanisms/" + mat + ".json").getAsJsonArray("values");
            assertEquals(1, values.size());
            assertEquals(id, values.get(0).getAsJsonObject().get("id").getAsString());
            assertTrue(!values.get(0).getAsJsonObject().get("required").getAsBoolean(), mat + ": an empty tag, not a broken one, with the system off");
            // NTM's other parts of the material are in NTM's tags of the same name
            assertTrue(inJar("data/hbm/tags/item/receivers_" + (mat.equals("ferrouranium") ? "heavy/" : "light/") + mat + ".json"), mat);
            assertTrue(en.has("item.hbm.part_mechanism_" + mat) && ru.has("item.hbm.part_mechanism_" + mat), mat);
        }
        assertTrue(en.has("ntmgunfeel.feature.gun_mechanisms") && ru.has("ntmgunfeel.feature.gun_mechanisms"));
        assertEquals("true", SpreadModelTest.shipped().getProperty("gunMechanismRecipes"));
    }

    // tools/gun_mechanisms.py and GunMechanismRecipes hold one table
    @Test
    void oneTable() throws IOException {
        String py = Files.readString(Path.of("tools/gun_mechanisms.py"));
        String body = py.substring(py.indexOf("RECIPES = {"), py.indexOf("}\n# recipes the balance"));
        Map<String, GunMechanismRecipes.Swap> table = new LinkedHashMap<>();
        Matcher m = Pattern.compile("'(\\w+)': \\{'(\\w+)': '(\\w+)'\\}").matcher(body);
        while (m.find()) table.put(m.group(1), new GunMechanismRecipes.Swap(m.group(2), m.group(3)));
        assertEquals(GunMechanismRecipes.RECIPES, table);
        Map<String, String> balance = new LinkedHashMap<>();
        Matcher b = Pattern.compile("BALANCE = \\{'(\\w+)': '(\\w+)'\\}").matcher(py);
        while (b.find()) balance.put(b.group(1), b.group(2));
        assertEquals(GunMechanismRecipes.BALANCE, balance);
        for (var s : table.values()) assertTrue(MATERIALS.contains(s.to()) && List.of("gun_metal", "weapon_steel").contains(s.from()), s.toString());
    }

    private static JsonArray conditions(Object... typeOn) {
        JsonArray a = new JsonArray();
        for (int i = 0; i < typeOn.length; i += 2) {
            JsonObject c = new JsonObject();
            c.addProperty("type", (String) typeOn[i]);
            c.addProperty("on", (Boolean) typeOn[i + 1]);
            a.add(c);
        }
        return a;
    }

    private static JsonObject without(JsonObject o) {
        JsonObject c = o.deepCopy();
        c.remove("neoforge:conditions");
        return c;
    }

    // NTM's recipe with only its mechanism swapped
    private static JsonObject swapped(JsonObject recipe, GunMechanismRecipes.Swap s) {
        String text = recipe.toString(), from = "\"#hbm:gun_mechanisms/" + s.from() + "\"";
        assertTrue(text.contains(from), recipe + " takes no " + s.from());
        return JsonParser.parseString(text.replace(from, "\"#hbm:gun_mechanisms/" + s.to() + "\"")).getAsJsonObject();
    }

    // each recipe: ours under NTM's id with the new mechanism and nothing else changed, NTM's as it is in its jar; the
    // balance's Tesla Cannon in its four files
    @Test
    void recipes() throws IOException {
        for (var e : GunMechanismRecipes.RECIPES.entrySet()) {
            String name = e.getKey();
            JsonObject ntm = json(jar("data/hbm/recipe/" + name + ".json"));
            JsonObject hbm = ours("data/hbm/recipe/" + name + ".json"), copy = ours("data/ntmgunfeel/recipe/ntm/" + name + ".json");
            assertEquals(ntm, without(copy), name + ": NTM's recipe as it is");
            if (GunMechanismRecipes.BALANCE.containsKey(name)) {
                JsonObject balance = ours("data/ntmgunfeel/recipe/balance/" + name + ".json"), mechanisms = ours("data/ntmgunfeel/recipe/mechanisms/" + name + ".json");
                assertEquals(conditions(BAL, true, MECH, true), hbm.get("neoforge:conditions"), name);
                assertEquals(conditions(BAL, true, MECH, false), balance.get("neoforge:conditions"), name);
                assertEquals(conditions(BAL, false, MECH, true), mechanisms.get("neoforge:conditions"), name);
                assertEquals(conditions(BAL, false, MECH, false), copy.get("neoforge:conditions"), name);
                var toBalance = new GunMechanismRecipes.Swap(e.getValue().from(), GunMechanismRecipes.BALANCE.get(name));
                assertEquals(swapped(without(balance), toBalance), without(hbm), name + ": the balance's with the mechanism of its tier");
                assertEquals(swapped(ntm, e.getValue()), without(mechanisms), name + ": NTM's with the new mechanism");
            } else {
                assertEquals(conditions(MECH, true), hbm.get("neoforge:conditions"), name);
                assertEquals(conditions(MECH, false), copy.get("neoforge:conditions"), name);
                assertEquals(swapped(ntm, e.getValue()), without(hbm), name);
            }
        }
    }

    // the recipe files under NTM's ids are the balance's and these, and every one of them loads under one condition
    @Test
    void noOtherFiles() throws IOException {
        Set<String> hbm = new TreeSet<>();
        try (var s = Files.list(RES.resolve("data/hbm/recipe"))) {
            s.forEach(f -> hbm.add(f.getFileName().toString().replace(".json", "")));
        }
        Set<String> want = new TreeSet<>(GunMechanismRecipes.RECIPES.keySet());
        want.addAll(BalanceStage1vTest.RECIPES);
        assertEquals(want, hbm);
        for (String dir : List.of("balance", "mechanisms")) try (var s = Files.list(RES.resolve("data/ntmgunfeel/recipe/" + dir))) {
            assertEquals(GunMechanismRecipes.BALANCE.keySet(), new HashSet<>(s.map(f -> f.getFileName().toString().replace(".json", "")).toList()), dir);
        }
        for (String name : hbm) for (JsonElement c : ours("data/hbm/recipe/" + name + ".json").getAsJsonArray("neoforge:conditions"))
            assertTrue(c.getAsJsonObject().get("on").getAsBoolean(), name);
    }
}
