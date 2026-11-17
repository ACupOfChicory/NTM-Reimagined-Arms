package acup.gunfeel.ammo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// The new rounds' recipes on NTM's ammo press, as the owner decided (2026-11-17); Gl54RecipesTest checks every item
// and tag in them is NTM's. The rubber slug's two: latex makes 6, rubber 12, and the tags do not overlap (NTM's
// any_rubber has both, so neither recipe takes it).
class NewAmmoRecipesTest {
    static final Path DIR = Path.of("src/main/resources/data/ntmgunfeel/recipe/ammo_press");

    static JsonObject read(String name) throws IOException {
        try (Reader r = Files.newBufferedReader(DIR.resolve(name + ".json"), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    /** "pattern rows | key: count ingredient, ... | out count" */
    static String shape(JsonObject o) {
        StringBuilder b = new StringBuilder();
        assertEquals("hbm:ammo_press", o.get("type").getAsString());
        o.getAsJsonArray("pattern").forEach(p -> b.append('[').append(p.getAsString()).append(']'));
        b.append(" |");
        Map<String, String> keys = new TreeMap<>();
        for (var e : o.getAsJsonObject("key").entrySet()) {
            JsonObject k = e.getValue().getAsJsonObject();
            keys.put(e.getKey(), (k.has("count") ? k.get("count").getAsInt() : 1) + " " + k.get("ingredient").getAsString());
        }
        keys.forEach((k, v) -> b.append(' ').append(k).append('=').append(v));
        var out = o.getAsJsonArray("output_items");
        assertEquals(1, out.size());
        JsonObject first = out.get(0).getAsJsonObject();
        b.append(" | ").append(first.get("count").getAsInt()).append(' ').append(first.get("id").getAsString());
        return b.toString();
    }

    @Test
    void recipesAreTheOwners() throws IOException {
        assertEquals("[ A ][ B ][ C ] | A=1 #c:ingots/latex B=1 #hbm:dusts/any_smokeless C=1 hbm:casing_shotshell | 6 ntmgunfeel:ammo_g12_rubber_slug",
                shape(read("g12_rubber_slug_latex")));
        assertEquals("[ A ][ B ][ C ] | A=1 #c:ingots/rubber B=1 #hbm:dusts/any_smokeless C=1 hbm:casing_shotshell | 12 ntmgunfeel:ammo_g12_rubber_slug",
                shape(read("g12_rubber_slug")));
        assertEquals("[ A ][ B ][ C ] | A=4 #c:ingots/lead B=4 #hbm:dusts/any_smokeless C=2 hbm:casing_small_steel | 16 ntmgunfeel:ammo_r556_duplex",
                shape(read("r556_duplex")));
        assertEquals("[ A ][ B ][ C ] | A=4 #c:ingots/lead B=4 #hbm:dusts/any_smokeless C=2 hbm:casing_small_steel | 12 ntmgunfeel:ammo_r762_duplex",
                shape(read("r762_duplex")));
        assertEquals("[   ][AB ][CD ] | A=2 hbm:nugget_am242 B=1 hbm:nugget_schrabidium C=2 hbm:ball_tatb D=1 hbm:casing_large_steel | 1 ntmgunfeel:ammo_bmg50_nuclear",
                shape(read("bmg50_nuclear")));
    }

    // the Duplex: as NTM's SP round of the calibre, the bullets' lead and the powder doubled, as many casings but of
    // weapon steel, as many rounds out
    @Test
    void duplexIsTheSpWithTwiceTheBullet() throws Exception {
        try (ZipFile jar = ntm()) {
            for (String cal : List.of("r556", "r762")) {
                JsonObject sp = JsonParser.parseString(new String(jar.getInputStream(jar.getEntry("data/hbm/recipe/ammo_press/ammo_press." + cal + "_sp.json"))
                        .readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject dx = read(cal + "_duplex");
                assertEquals(2 * sp.getAsJsonObject("key").getAsJsonObject("A").get("count").getAsInt(),
                        dx.getAsJsonObject("key").getAsJsonObject("A").get("count").getAsInt());
                assertEquals(sp.getAsJsonObject("key").getAsJsonObject("A").get("ingredient").getAsString(),
                        dx.getAsJsonObject("key").getAsJsonObject("A").get("ingredient").getAsString());
                assertEquals(2 * sp.getAsJsonObject("key").getAsJsonObject("B").get("count").getAsInt(),
                        dx.getAsJsonObject("key").getAsJsonObject("B").get("count").getAsInt());
                assertEquals("hbm:casing_small", sp.getAsJsonObject("key").getAsJsonObject("C").get("ingredient").getAsString());
                assertEquals("hbm:casing_small_steel", dx.getAsJsonObject("key").getAsJsonObject("C").get("ingredient").getAsString());
                assertEquals(sp.getAsJsonObject("key").getAsJsonObject("C").get("count").getAsInt(),
                        dx.getAsJsonObject("key").getAsJsonObject("C").get("count").getAsInt());
                assertEquals(sp.getAsJsonArray("output_items").get(0).getAsJsonObject().get("count").getAsInt(),
                        dx.getAsJsonArray("output_items").get(0).getAsJsonObject().get("count").getAsInt());
            }
        }
    }

    // latex and rubber are two tags, neither in the other (in NTM's jar)
    @Test
    void latexAndRubberDoNotOverlap() throws Exception {
        try (ZipFile jar = ntm()) {
            String latex = new String(jar.getInputStream(jar.getEntry("data/c/tags/item/ingots/latex.json")).readAllBytes(), StandardCharsets.UTF_8);
            String rubber = new String(jar.getInputStream(jar.getEntry("data/c/tags/item/ingots/rubber.json")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(latex.contains("hbm:ingot_biorubber"), latex);
            assertTrue(rubber.contains("hbm:ingot_rubber"), rubber);
            assertFalse(latex.contains("#c:ingots/rubber"), latex);
            assertFalse(rubber.contains("latex"), rubber);
        }
    }

    static ZipFile ntm() throws Exception {
        return new ZipFile(Path.of(Class.forName("com.hbm.items.ModItems", false, NewAmmoRecipesTest.class.getClassLoader()).getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toFile());
    }
}
