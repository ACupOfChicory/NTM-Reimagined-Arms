package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** REUSE.toml: who made each file. New files fall to the owner by default; other people's files are named. */
class ReuseTomlTest {
    static final Path TOML = Path.of("REUSE.toml");
    static final Path LICENSES = Path.of("LICENSES");
    static final String ASSETS = "src/main/resources/assets/";
    static final String OWNER = "2026 ACupOfChicory";
    static final String DEFAULT_LICENSE = "GPL-3.0-or-later";
    static final List<String> NTM = List.of("HbmMods (The Bobcat) and contributors to Hbm's Nuclear Tech Mod", OWNER);

    record Rule(List<String> paths, List<String> copyright, String license, String precedence) {
        boolean isDefault() { return paths.equals(List.of("**")); }
        boolean matches(String file) {
            for (String p : paths) {
                PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + p);
                if (m.matches(Path.of(file))) return true;
            }
            return false;
        }
    }

    static List<Rule> rules() throws IOException {
        List<Rule> out = new ArrayList<>();
        Map<String, List<String>> cur = null;
        String key = null;
        Pattern kv = Pattern.compile("^([\\w-]+)\\s*=\\s*(.*)$");
        Pattern str = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
        for (String raw : Files.readAllLines(TOML)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.equals("[[annotations]]")) {
                if (cur != null) out.add(rule(cur));
                cur = new HashMap<>();
                key = null;
                continue;
            }
            if (cur == null) continue;
            Matcher m = kv.matcher(line);
            String values = line;
            if (m.matches()) {
                key = m.group(1);
                values = m.group(2);
                cur.put(key, new ArrayList<>());
            }
            if (key == null) continue;
            Matcher s = str.matcher(values);
            while (s.find()) cur.get(key).add(s.group(1));
        }
        if (cur != null) out.add(rule(cur));
        return out;
    }

    static Rule rule(Map<String, List<String>> t) {
        List<String> lic = t.getOrDefault("SPDX-License-Identifier", List.of());
        assertEquals(1, lic.size(), "one license expression per table: " + t);
        List<String> prec = t.getOrDefault("precedence", List.of("closest"));
        return new Rule(t.getOrDefault("path", List.of()), t.getOrDefault("SPDX-FileCopyrightText", List.of()), lic.get(0),
                prec.get(0));
    }

    /** The rule REUSE applies: the last table that matches (every table after the first is "override"). */
    static Rule resolve(List<Rule> rules, String file) {
        Rule hit = null;
        for (Rule r : rules) if (r.matches(file)) hit = r;
        return hit;
    }

    /** The files REUSE sees: git's, or without git the tracked folders on disk. */
    static List<String> files() throws IOException {
        try {
            Process p = new ProcessBuilder("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard").redirectErrorStream(false).start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (p.waitFor() == 0 && !out.isEmpty()) {
                List<String> files = new ArrayList<>();
                for (String f : out.split("\0")) if (!f.isEmpty() && Files.isRegularFile(Path.of(f))) files.add(f);
                return files;
            }
        } catch (IOException | InterruptedException e) {
            // no git: walk the folders below
        }
        List<String> out = new ArrayList<>();
        for (String root : List.of("src", "tools", "third-party", "gradle", ".github", "LICENSES")) {
            if (!Files.isDirectory(Path.of(root))) continue;
            try (Stream<Path> s = Files.walk(Path.of(root))) {
                s.filter(Files::isRegularFile).map(p -> p.toString().replace('\\', '/')).forEach(out::add);
            }
        }
        try (Stream<Path> s = Files.list(Path.of("."))) {
            s.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).forEach(out::add);
        }
        return out;
    }

    static boolean isArt(String f) {
        return f.startsWith(ASSETS) && (f.contains("/textures/") || f.contains("/sounds/") || f.contains("/models/")
                || f.contains("/animations/"));
    }

    @Test
    void firstRuleGivesEverythingToTheOwner() throws IOException {
        Rule first = rules().get(0);
        assertTrue(first.isDefault(), "first table is path = \"**\"");
        assertEquals(List.of(OWNER), first.copyright());
        assertEquals(DEFAULT_LICENSE, first.license());
    }

    @Test
    void everyLaterRuleOverrides() throws IOException {
        List<Rule> rules = rules();
        for (Rule r : rules.subList(1, rules.size())) assertEquals("override", r.precedence(), r.paths().toString());
    }

    @Test
    void everyLicenseHasItsTextAndEveryTextIsUsed() throws IOException {
        Set<String> used = new TreeSet<>();
        for (Rule r : rules()) {
            assertFalse(r.copyright().isEmpty(), "copyright for " + r.paths());
            for (String id : r.license().split("\\s+(?:AND|OR|WITH)\\s+")) used.add(id.replaceAll("[()]", ""));
        }
        Set<String> texts = new TreeSet<>();
        try (Stream<Path> s = Files.list(LICENSES)) {
            s.map(p -> p.getFileName().toString().replaceFirst("\\.txt$", "")).forEach(texts::add);
        }
        assertEquals(used, texts);
    }

    @Test
    void everyPathFindsAFile() throws IOException {
        List<String> files = files();
        for (Rule r : rules()) {
            for (String p : r.paths()) {
                PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + p);
                assertTrue(files.stream().anyMatch(f -> m.matches(Path.of(f))), "no file for " + p);
            }
        }
    }

    /** Sounds are all other people's: each one needs its own author. */
    @Test
    void everySoundHasAnAuthorOtherThanTheDefault() throws IOException {
        List<Rule> rules = rules();
        int n = 0;
        for (String f : files()) {
            if (!f.startsWith(ASSETS) || !f.endsWith(".ogg")) continue;
            n++;
            assertFalse(resolve(rules, f).isDefault(), "sound without an author in REUSE.toml: " + f);
        }
        assertTrue(n > 100, "sounds found: " + n);
    }

    /** Other people's textures and models are named one by one, so an owner's new file never falls under them. */
    @Test
    void otherPeoplesArtIsNamedExplicitly() throws IOException {
        List<Rule> rules = rules();
        for (String f : files()) {
            if (!isArt(f) || f.endsWith(".ogg")) continue;
            Rule r = resolve(rules, f);
            boolean owner = r.isDefault();
            if (f.contains("/gl54") && !f.contains("/models/item/")) {
                assertFalse(owner, "GL-54 art under the default rule: " + f);
            }
            if (!owner && f.contains("/textures/")) {
                assertTrue(r.paths().contains(f), "texture matched by a glob, not by name: " + f);
            }
        }
    }


    /** What is made from NTM's files is NTM's authors' and the owner's, under NTM's LGPL; the owner's own art stays the owner's. */
    @Test
    void ntmDerivedFilesAreNtmsAndTheOwners() throws IOException {
        List<Rule> rules = rules();
        int n = 0;
        for (String f : files()) {
            boolean ntm = f.startsWith("src/main/resources/assets/hbm/") || f.startsWith("src/main/resources/data/hbm/recipe/")
                    || f.startsWith("src/main/resources/data/ntmgunfeel/recipe/ntm/") || f.startsWith("src/test/gundata/")
                    || f.startsWith("src/test/blockfacts/") || f.equals("src/main/resources/ntmgunfeel-machine-shapes.pack")
                    || f.equals("src/main/resources/assets/ntmgunfeel/water/ammo.tsv");
            if (!ntm) continue;
            n++;
            Rule r = resolve(rules, f);
            assertEquals(NTM, r.copyright(), f);
            assertEquals("LGPL-3.0-only", r.license(), f);
        }
        assertTrue(n > 80, "NTM-derived files found: " + n);
        for (String own : List.of(ASSETS + "ntmgunfeel/textures/item/ammo_g12_shock.png", ASSETS + "ntmgunfeel/textures/effect/hole_old.png",
                ASSETS + "ntmgunfeel/textures/particle/water_ring.png", "src/main/resources/data/hbm/tags/item/gun_mechanisms/steel.json")) {
            assertTrue(resolve(rules, own).isDefault(), "the owner's file under another rule: " + own);
        }
        assertEquals(Files.readString(LICENSES.resolve("LGPL-3.0-only.txt")), Files.readString(Path.of("third-party/ntm-LGPL-3.0.txt")),
                "the LGPL in the jar is the one in LICENSES/");
    }

    /** Bullet hit sounds: the author and license in REUSE.toml are the ones CREDITS.txt gives. */
    @Test
    void hitSoundsMatchCredits() throws IOException {
        Path credits = Path.of(ASSETS + "ntmgunfeel/sounds/bullet/CREDITS.txt");
        Pattern line = Pattern.compile("^([a-z0-9, ]+?)\\s+\".*\" by (.+?) \\((CC0|CC BY 3\\.0|CC BY 4\\.0)\\)\\s+\\S+$");
        Map<String, String> spdx = Map.of("CC0", "CC0-1.0", "CC BY 3.0", "CC-BY-3.0", "CC BY 4.0", "CC-BY-4.0");
        List<Rule> rules = rules();
        int n = 0;
        for (String raw : Files.readAllLines(credits)) {
            Matcher m = line.matcher(raw.strip());
            if (!m.matches()) continue;
            for (String name : m.group(1).split(",\\s*")) {
                String f = ASSETS + "ntmgunfeel/sounds/bullet/hit/" + name.strip() + ".ogg";
                assertTrue(Files.exists(Path.of(f)), "CREDITS.txt names a missing file: " + f);
                Rule r = resolve(rules, f);
                assertEquals(List.of(m.group(2)), r.copyright(), f);
                assertEquals(spdx.get(m.group(3)), r.license(), f);
                n++;
            }
        }
        try (Stream<Path> s = Files.list(Path.of(ASSETS + "ntmgunfeel/sounds/bullet/hit"))) {
            assertEquals(n, s.count(), "every hit sound is in CREDITS.txt");
        }
    }

    /** Whizz and crack sounds: all CC0, the author CREDITS.txt names ("whizz3-6" is a range). */
    @Test
    void flyBySoundsMatchCredits() throws IOException {
        String dir = ASSETS + "ntmgunfeel/sounds/bullet/";
        Pattern line = Pattern.compile("^(whizz|crack)(\\d+)(?:-(\\d+))?\\s.*\" by (\\S+)\\s+\\S+$");
        List<Rule> rules = rules();
        int n = 0;
        for (String raw : Files.readAllLines(Path.of(dir + "CREDITS.txt"))) {
            Matcher m = line.matcher(raw.strip());
            if (!m.matches()) continue;
            int from = Integer.parseInt(m.group(2));
            int to = m.group(3) == null ? from : Integer.parseInt(m.group(3));
            for (int i = from; i <= to; i++) {
                String f = dir + m.group(1) + i + ".ogg";
                assertTrue(Files.exists(Path.of(f)), "CREDITS.txt names a missing file: " + f);
                Rule r = resolve(rules, f);
                assertEquals(List.of(m.group(4)), r.copyright(), f);
                assertEquals("CC0-1.0", r.license(), f);
                n++;
            }
        }
        try (Stream<Path> s = Files.list(Path.of(dir))) {
            assertEquals(n, s.filter(p -> p.toString().endsWith(".ogg")).count(), "every whizz and crack is in CREDITS.txt");
        }
    }
}
