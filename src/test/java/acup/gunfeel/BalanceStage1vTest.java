package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// Stage 1v of the balance: the power armour spread table and the recipes, without the game.
class BalanceStage1vTest {
    private static final Path HBM = Path.of("src/main/resources/data/hbm/recipe");
    private static final Path NTM = Path.of("src/main/resources/data/ntmgunfeel/recipe/ntm");
    private static final List<String> RECIPES = List.of("dieselsuit_helmet", "dieselsuit_plate", "dieselsuit_legs", "gun_tesla_cannon", "gun_lasrifle", "gun_tau");

    // the shipped keys powerArmorSpread.<set> are the table's, and the table is the owner's
    @Test
    void spreadTable() throws IOException {
        Properties p = SpreadModelTest.shipped();
        Map<String, Float> shipped = new TreeMap<>();
        for (String k : p.stringPropertyNames()) if (k.startsWith("powerArmorSpread.")) shipped.put(k.substring(17), Float.parseFloat(p.getProperty(k).trim()));
        assertEquals(new TreeMap<>(PowerArmor.SPREAD), shipped);
        assertEquals(Map.ofEntries(Map.entry("t51", 0.8F), Map.entry("hev", 0.7F), Map.entry("ajr", 0.7F), Map.entry("ajro", 0.7F),
                Map.entry("envsuit", 0.7F), Map.entry("trenchmaster", 0.7F), Map.entry("bj", 0.6F), Map.entry("ncrpa", 0.6F),
                Map.entry("rpa", 0.5F), Map.entry("fau", 0.4F), Map.entry("dns", 0.4F)), PowerArmor.SPREAD);
        for (String none : List.of("security", "steamsuit", "dieselsuit")) assertTrue(!PowerArmor.SPREAD.containsKey(none), none);
        assertEquals("true", p.getProperty("balanceRecipes"));
    }

    private static JsonObject read(Path f) throws IOException {
        return JsonParser.parseString(Files.readString(f)).getAsJsonObject();
    }

    // NTM's own recipe of that id, from its jar on the test classpath (not ours: same path)
    private static JsonObject jar(String name) throws IOException {
        for (URL u : Collections.list(BalanceStage1vTest.class.getClassLoader().getResources("data/hbm/recipe/" + name + ".json"))) {
            if (!u.toString().contains("hbm-neoforge")) continue;
            try (InputStream in = u.openStream()) {
                return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
        }
        throw new AssertionError("NTM's jar has no recipe " + name);
    }

    private static boolean jarHas(String path) {
        return BalanceStage1vTest.class.getClassLoader().getResource(path) != null;
    }

    private static boolean condition(JsonObject o, boolean on) {
        var c = o.getAsJsonArray("neoforge:conditions");
        return c != null && c.size() == 1 && c.get(0).getAsJsonObject().get("type").getAsString().equals("ntmgunfeel:balance_recipes")
                && c.get(0).getAsJsonObject().get("on").getAsBoolean() == on;
    }

    // our files are these six, the balance's under NTM's ids with on = true, NTM's own as they are in its jar with on =
    // false; every key used, every item and tag in NTM's jar
    @Test
    void recipes() throws IOException {
        Set<String> ours = new HashSet<>();
        try (var s = Files.list(HBM)) {
            s.forEach(f -> ours.add(f.getFileName().toString().replace(".json", "")));
        }
        assertEquals(new HashSet<>(RECIPES), ours);
        for (String name : RECIPES) {
            JsonObject balance = read(HBM.resolve(name + ".json")), ntm = read(NTM.resolve(name + ".json"));
            assertTrue(condition(balance, true), name);
            assertTrue(condition(ntm, false), name);
            JsonObject copy = ntm.deepCopy();
            copy.remove("neoforge:conditions");
            assertEquals(jar(name), copy, name + ": NTM's recipe as it is");
            assertEquals(jar(name).get("type"), balance.get("type"));
            assertEquals(jar(name).get("result"), balance.get("result"));
            Set<String> used = new HashSet<>();
            for (JsonElement row : balance.getAsJsonArray("pattern")) for (char c : row.getAsString().toCharArray()) if (c != ' ') used.add(String.valueOf(c));
            assertEquals(balance.getAsJsonObject("key").keySet(), used, name);
            for (var e : balance.getAsJsonObject("key").entrySet()) {
                String id = e.getValue().getAsString();
                boolean tag = id.startsWith("#");
                String[] ns = (tag ? id.substring(1) : id).split(":");
                String path = tag ? "data/" + ns[0] + "/tags/item/" + ns[1] + ".json" : "assets/" + ns[0] + "/items/" + ns[1] + ".json";
                if (!ns[0].equals("minecraft")) assertTrue(jarHas(path), name + ": " + id);
            }
        }
    }
}
