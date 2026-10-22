package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// The files in res/ that Minecraft only reads at launch or when a sound plays: broken ones fail silently in game
// (missing text, silent sounds) or stop the mod from loading. Run from the repository root.
class ResourcesTest {
    static final Path RES = Path.of("src/main/resources");
    static final Path ASSETS = RES.resolve("assets/ntmgunfeel");
    static final Path SRC = Path.of("src");

    @Test
    void everyJsonFileParses() throws IOException {
        for (Path p : files(RES, ".json")) {
            try {
                JsonParser.parseString(Files.readString(p));
            } catch (RuntimeException e) {
                fail(p + ": " + e.getMessage());
            }
        }
    }

    @Test
    void languagesHaveTheSameKeys() throws IOException {
        Set<String> en = lang("en_us").keySet();
        for (Path p : files(ASSETS.resolve("lang"), ".json")) {
            String code = p.getFileName().toString().replace(".json", "");
            Set<String> other = lang(code).keySet();
            Set<String> missing = new TreeSet<>(en);
            missing.removeAll(other);
            Set<String> extra = new TreeSet<>(other);
            extra.removeAll(en);
            assertTrue(missing.isEmpty(), code + " lacks " + missing);
            assertTrue(extra.isEmpty(), code + " has keys en_us does not: " + extra);
        }
    }

    @Test
    void noEmptyTranslations() throws IOException {
        for (Path p : files(ASSETS.resolve("lang"), ".json")) {
            for (Map.Entry<String, JsonElement> e : JsonParser.parseString(Files.readString(p)).getAsJsonObject().entrySet()) {
                assertFalse(e.getValue().getAsString().isBlank(), p.getFileName() + ": " + e.getKey() + " is empty");
            }
        }
    }

    @Test
    void translationKeysUsedInCodeExist() throws IOException {
        Pattern key = Pattern.compile("\"((?:key|message|gui|item|block|commands?|tooltip)\\.(?:category\\.)?ntmgunfeel\\.[a-z0-9_.]+)\"");
        Set<String> used = new TreeSet<>();
        for (Path p : files(SRC, ".java")) {
            Matcher m = key.matcher(Files.readString(p));
            while (m.find()) used.add(m.group(1));
        }
        assertFalse(used.isEmpty(), "found no translation keys in src/, is the pattern still right?");
        Set<String> en = lang("en_us").keySet();
        used.removeAll(en);
        assertTrue(used.isEmpty(), "used in code but not in en_us.json: " + used);
    }

    @Test
    void everySoundFileExists() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String name : soundNames()) {
            String[] nsPath = name.contains(":") ? name.split(":", 2) : new String[] {"minecraft", name};
            if (!nsPath[0].equals("ntmgunfeel")) continue;
            if (!Files.exists(ASSETS.resolve("sounds/" + nsPath[1] + ".ogg"))) missing.add(name);
        }
        assertTrue(missing.isEmpty(), "sounds.json names files that are not there: " + missing);
    }

    @Test
    void everySoundFileIsUsed() throws IOException {
        Set<String> named = new HashSet<>(soundNames());
        List<String> unused = new ArrayList<>();
        Path dir = ASSETS.resolve("sounds");
        for (Path p : files(dir, ".ogg")) {
            String rel = dir.relativize(p).toString().replace('\\', '/');
            String name = "ntmgunfeel:" + rel.substring(0, rel.length() - 4);
            if (!named.contains(name)) unused.add(name);
        }
        assertTrue(unused.isEmpty(), "sound files no sound event plays (dead weight in the jar): " + unused);
    }

    @Test
    void soundCreditsCoverEverySound() throws IOException {
        // CC BY sounds must name their author; CREDITS.txt lines start with the files: "wood2, wood3  ..." or "whizz3-6 ..."
        Set<String> credited = new HashSet<>();
        Pattern range = Pattern.compile("([a-z]+)(\\d+)-(\\d+)");
        for (String line : Files.readAllLines(ASSETS.resolve("sounds/bullet/CREDITS.txt"))) {
            int quote = line.indexOf('"');
            if (quote <= 0) continue;
            for (String token : line.substring(0, quote).trim().split("[,\\s]+")) {
                Matcher r = range.matcher(token);
                if (r.matches()) {
                    for (int i = Integer.parseInt(r.group(2)); i <= Integer.parseInt(r.group(3)); i++) credited.add(r.group(1) + i);
                } else if (token.matches("[a-z]+\\d+")) {
                    credited.add(token);
                }
            }
        }
        List<String> uncredited = new ArrayList<>();
        for (Path p : files(ASSETS.resolve("sounds"), ".ogg")) {
            String name = p.getFileName().toString().replace(".ogg", "");
            if (!credited.contains(name)) uncredited.add(name);
        }
        assertTrue(uncredited.isEmpty(), "no line in CREDITS.txt for: " + uncredited);
    }

    @Test
    void modsTomlMatchesTheMod() throws IOException {
        String toml = Files.readString(RES.resolve("META-INF/neoforge.mods.toml"));
        assertTrue(toml.contains("modId = \"ntmgunfeel\""), "modId must match the assets namespace ntmgunfeel");
        Matcher v = Pattern.compile("(?m)^version *= *\"([^\"]+)\"").matcher(toml);
        assertTrue(v.find(), "no version line (the jar is named after it)");
        assertTrue(v.group(1).matches("\\d+\\.\\d+\\.\\d+(-[a-z0-9.]+)?"), "odd version " + v.group(1));
        Matcher mixins = Pattern.compile("config *= *\"([^\"]+)\"").matcher(toml);
        while (mixins.find()) assertTrue(Files.exists(RES.resolve(mixins.group(1))), "missing " + mixins.group(1));
    }

    @Test
    void propertiesFilesHaveNoDuplicateKeys() throws IOException {
        // java.util.Properties keeps the last of two equal keys without a word, so the first one silently does nothing
        for (Path p : files(RES, ".properties")) {
            Set<String> seen = new HashSet<>();
            List<String> dup = new ArrayList<>();
            String pending = "";
            for (String line : Files.readAllLines(p, StandardCharsets.ISO_8859_1)) {
                String l = pending + line.strip();
                if (l.endsWith("\\") && !l.endsWith("\\\\")) {
                    pending = l.substring(0, l.length() - 1);
                    continue;
                }
                pending = "";
                if (l.isEmpty() || l.startsWith("#") || l.startsWith("!")) continue;
                String k = key(l);
                if (!seen.add(k)) dup.add(k);
            }
            assertTrue(dup.isEmpty(), p.getFileName() + " sets these keys twice: " + dup);
        }
    }

    @Test
    void blockTagsNameNamespacedIds() throws IOException {
        for (Path p : files(RES.resolve("data"), ".json")) {
            JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            if (!o.has("values")) continue;
            for (JsonElement e : o.getAsJsonArray("values")) {
                String id = e.isJsonObject() ? e.getAsJsonObject().get("id").getAsString() : e.getAsString();
                assertTrue(id.matches("#?[a-z0-9_.-]+:[a-z0-9_./-]+"), p.getFileName() + ": bad id " + id);
            }
        }
    }

    // ------------------------------------------------------------------

    static String key(String line) {
        StringBuilder k = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                k.append(line.charAt(++i));
            } else if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                break;
            } else {
                k.append(c);
            }
        }
        return k.toString();
    }

    static JsonObject lang(String code) throws IOException {
        return JsonParser.parseString(Files.readString(ASSETS.resolve("lang/" + code + ".json"))).getAsJsonObject();
    }

    static List<String> soundNames() throws IOException {
        List<String> out = new ArrayList<>();
        JsonObject root = JsonParser.parseString(Files.readString(ASSETS.resolve("sounds.json"))).getAsJsonObject();
        for (Map.Entry<String, JsonElement> event : root.entrySet()) {
            for (JsonElement s : event.getValue().getAsJsonObject().getAsJsonArray("sounds")) {
                if (s.isJsonPrimitive()) {
                    out.add(s.getAsString());
                } else {
                    JsonObject o = s.getAsJsonObject();
                    if (o.has("type") && o.get("type").getAsString().equals("event")) continue;
                    out.add(o.get("name").getAsString());
                }
            }
        }
        assertFalse(out.isEmpty());
        return out;
    }

    static List<Path> files(Path dir, String suffix) throws IOException {
        assertTrue(Files.isDirectory(dir), dir.toAbsolutePath() + " missing: run the tests from the repository root");
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(suffix)).sorted().toList();
        }
    }

    @Test
    void namespaceFolderIsTheOnlyOne() throws IOException {
        try (Stream<Path> s = Files.list(RES.resolve("assets"))) {
            assertEquals(List.of("ntmgunfeel"), s.map(p -> p.getFileName().toString()).toList());
        }
    }
}
