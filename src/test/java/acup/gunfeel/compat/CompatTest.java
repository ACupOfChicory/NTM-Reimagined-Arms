package acup.gunfeel.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// The protection against an NTM the mod was not tested with (Compat, HbmMixinPlugin): the version range, what is
// turned off, and that with the tested NTM nothing is.
class CompatTest {
    static final Path RES = Path.of("src/main/resources");
    static final String LEGO = "com/hbm/items/weapon/sedna/factory/Lego";

    static MixinProbe.Classes classes() {
        Map<String, ClassNode> cache = new HashMap<>();
        ClassLoader cl = CompatTest.class.getClassLoader();
        return n -> cache.computeIfAbsent(n, k -> HbmMixinPlugin.read(cl, k));
    }

    static MixinProbe.Classes mixins() {
        ClassLoader cl = CompatTest.class.getClassLoader();
        return m -> HbmMixinPlugin.read(cl, "acup/gunfeel/mixin/" + m);
    }

    static List<String> api() throws IOException {
        return Files.readAllLines(RES.resolve(ApiProbe.FILE));
    }

    // ---------------------------------------------------------------- versions

    @Test
    void versionRanges() {
        assertTrue(Compat.inRange("1.0.3+1b8c6c2d", "[1.0.3]"));
        assertTrue(Compat.inRange("1.0.3+1b8c6c2d", "[1.0.3,)"));
        assertFalse(Compat.inRange("1.0.2+abc", "[1.0.3,)"));
        assertFalse(Compat.inRange("1.0.4", "[1.0.3]"));
        assertTrue(Compat.inRange("1.0.4", ""));
        assertTrue(Compat.inRange("1.0.4", "[oops"));
        assertEquals("1.0.3", Compat.lastSupported("[1.0.3]"));
        assertEquals("1.0.5", Compat.lastSupported("[1.0.3,1.0.5]"));
        assertEquals("1.0.3", Compat.lastSupported("[1.0.3,)"));
    }

    @Test
    void testedRangeHoldsTheNtmTheModIsBuiltAgainst() throws IOException {
        Matcher m = Pattern.compile("ntmTested\\s*=\\s*\"([^\"]+)\"").matcher(Files.readString(RES.resolve("META-INF/neoforge.mods.toml")));
        assertTrue(m.find(), "no ntmTested in neoforge.mods.toml");
        String version = ntmVersion();
        assertTrue(Compat.inRange(version, m.group(1)), "NTM " + version + " is not in ntmTested " + m.group(1));
    }

    static String ntmVersion() throws IOException {
        URL u = CompatTest.class.getClassLoader().getResource(LEGO + ".class");
        assertNotNull(u, "NTM is not on the test classpath");
        JarURLConnection c = (JarURLConnection) u.openConnection();
        try (JarFile jar = new JarFile(c.getJarFileURL().getPath())) {
            return jar.getManifest().getMainAttributes().getValue("Implementation-Version");
        }
    }

    // ---------------------------------------------------------------- what is turned off

    @Test
    void sharedMixinTakesBothSystems() {
        Compat.Result r = Compat.resolve(Map.of("MixinHbmAnimations", List.of("gone")), Map.of());
        assertTrue(r.off().keySet().containsAll(Set.of(Feature.RECOIL, Feature.ARMS)), r.off().toString());
        // RECOIL's and ARMS's other mixins go with them, and nothing else
        assertTrue(r.skipped().containsKey("MixinItemGunBaseNT"));
        assertTrue(r.skipped().containsKey("MixinSexyBottle"));
        assertEquals(Set.of(Feature.RECOIL, Feature.ARMS), r.off().keySet());
    }

    @Test
    void missingCallTurnsOffItsSystem() {
        Compat.Result r = Compat.resolve(Map.of(), Map.of(Feature.TRACERS, List.of("ShotPayload", "x")));
        assertEquals(Set.of(Feature.TRACERS), r.off().keySet());
        assertEquals("NTM: ShotPayload (+1)", r.off().get(Feature.TRACERS));
        assertEquals(Set.of("MixinLegoClient", "MixinRenderBulletMK4"), r.skipped().keySet());
    }

    @Test
    void coreTakesAll() {
        Compat.Result r = Compat.resolve(Map.of(), Map.of(Feature.CORE, List.of("ItemGunBaseNT")));
        assertEquals(Feature.values().length, r.off().size());
        Set<String> all = new HashSet<>();
        for (Feature f : Feature.values()) all.addAll(f.mixins);
        assertEquals(all, r.skipped().keySet());
    }

    @Test
    void testedNtmTurnsNothingOff() throws IOException {
        Compat.Result r = HbmMixinPlugin.evaluate(mixins(), classes(), api());
        assertNotNull(r, "no NTM class could be read");
        assertTrue(r.skipped().isEmpty(), r.skipped().toString());
        assertTrue(r.off().isEmpty(), r.off().toString());
    }

    // NTM renames the method the spread mixin changes: only the spread goes
    @Test
    void renamedMethodTurnsOffOnlyItsSystem() throws IOException {
        MixinProbe.Classes real = classes();
        ClassNode lego = HbmMixinPlugin.read(CompatTest.class.getClassLoader(), LEGO);
        assertNotNull(lego);
        for (MethodNode m : lego.methods) if (m.name.equals("calcSpread")) m.name = "calcSpreadRenamed";
        MixinProbe.Classes doctored = n -> n.equals(LEGO) ? lego : real.get(n);
        Compat.Result r = HbmMixinPlugin.evaluate(mixins(), doctored, api());
        assertNotNull(r);
        assertEquals(Set.of(Feature.SPREAD), r.off().keySet(), r.off().toString());
        assertEquals(Set.of("MixinLegoSpread"), r.skipped().keySet());
    }

    @Test
    void noNtmTurnsNothingOff() throws IOException {
        assertEquals(null, HbmMixinPlugin.evaluate(mixins(), n -> n.startsWith("com/hbm/") ? null : classes().get(n), api()));
    }

    // ---------------------------------------------------------------- the map and the configs

    @Test
    void everyNtmMixinBelongsToASystem() throws IOException {
        Set<String> mapped = new HashSet<>();
        for (Feature f : Feature.values()) mapped.addAll(f.mixins);
        List<String> listed = listed(Compat.HBM_CONFIG);
        Set<String> unmapped = new HashSet<>(listed);
        unmapped.removeAll(mapped);
        assertTrue(unmapped.isEmpty(), "mixins into NTM with no system: " + unmapped);
        Set<String> notListed = new HashSet<>(mapped);
        listed.forEach(notListed::remove);
        assertTrue(notListed.isEmpty(), "systems name mixins " + Compat.HBM_CONFIG + " does not list: " + notListed);
    }

    @Test
    void vanillaConfigHasNoNtmTargets() throws IOException {
        ClassLoader cl = CompatTest.class.getClassLoader();
        for (String m : listed("ntmgunfeel.mixins.json")) {
            ClassNode node = HbmMixinPlugin.read(cl, "acup/gunfeel/mixin/mc/" + m);
            assertNotNull(node, m);
            for (String t : MixinProbe.targets(node)) assertFalse(t.startsWith("com/hbm/"), m + " changes NTM's " + t);
        }
    }

    @Test
    void everySystemHasNames() throws IOException {
        for (String lang : List.of("en_us", "ru_ru")) {
            JsonObject o = JsonParser.parseString(Files.readString(RES.resolve("assets/ntmgunfeel/lang/" + lang + ".json")))
                    .getAsJsonObject();
            for (Feature f : Feature.values()) assertTrue(o.has(f.langKey()), lang + " lacks " + f.langKey());
        }
    }

    static List<String> listed(String config) throws IOException {
        JsonObject o = JsonParser.parseString(Files.readString(RES.resolve(config))).getAsJsonObject();
        List<String> out = new ArrayList<>();
        for (String side : List.of("mixins", "client", "server")) {
            JsonArray a = o.getAsJsonArray(side);
            if (a != null) a.forEach(e -> out.add(e.getAsString()));
        }
        return out;
    }
}
