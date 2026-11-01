package acup.gunfeel.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

// port: 26.2's CompatTest (e284339) for the port's protection against an NTM the mod was not tested with (Compat,
// NtmCheck): the build-number range, what is turned off, and that with the NTM the port is built against nothing is.
class CompatPortTest {
    static final Path RES = Paths.get("src/main/resources");
    static final String BULLET_RENDER = "com/hbm/render/entity/projectile/RenderBulletMK4";

    static MixinProbe.Classes classes() {
        Map<String, ClassNode> cache = new HashMap<String, ClassNode>();
        ClassLoader cl = CompatPortTest.class.getClassLoader();
        return n -> {
            if (!cache.containsKey(n)) cache.put(n, NtmCheck.read(cl, n));
            return cache.get(n);
        };
    }

    static MixinProbe.Classes mixins() {
        ClassLoader cl = CompatPortTest.class.getClassLoader();
        return m -> NtmCheck.read(cl, NtmCheck.MIXIN_PATH + m);
    }

    static List<String> api() throws IOException {
        return Files.readAllLines(RES.resolve(ApiProbe.FILE), StandardCharsets.UTF_8);
    }

    static final MixinProbe.Remap SAME = (m, n) -> n;

    // ---------------------------------------------------------------- versions

    @Test
    void versionRanges() {
        assertEquals(5863, Compat.build("1.0.27 BETA (5863)"));
        assertEquals(5808, Compat.build("1.0.27_X5808"));
        assertEquals(-1, Compat.build("?"));
        assertTrue(Compat.inRange("1.0.27 BETA (5863)", "[5808,5863]"));
        assertTrue(Compat.inRange("1.0.27 BETA (5808)", "[5808,5863]"));
        assertFalse(Compat.inRange("1.0.27 BETA (5864)", "[5808,5863]"));
        assertFalse(Compat.inRange("1.0.27 BETA (5700)", "[5808,)"));
        assertTrue(Compat.inRange("1.0.27 BETA (5900)", "[5808,)"));
        assertFalse(Compat.inRange("1.0.27 BETA (5863)", "[5808,5863)"));
        assertTrue(Compat.inRange("1.0.27 BETA (5863)", ""));
        assertTrue(Compat.inRange("1.0.27 BETA (5863)", "[oops"));
        assertEquals("5863", Compat.lastSupported("[5808,5863]"));
        assertEquals("5808", Compat.lastSupported("[5808,)"));
    }

    // the NTM on the class path (the one the port is built against) is a tested one
    @Test
    void testedRangeHoldsTheNtmTheModIsBuiltAgainst() throws IOException {
        String version = ntmVersion();
        assertTrue(Compat.inRange(version, Compat.TESTED), "NTM " + version + " is not in " + Compat.TESTED);
        assertTrue(Compat.build(version) > 0, version);
    }

    // NTM's own RefStrings.VERSION ("1.0.27 BETA (5863)"), as its @Mod gives it to FML
    static String ntmVersion() {
        try {
            return (String) Class.forName("com.hbm.lib.RefStrings").getField("VERSION").get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("NTM is not on the test class path", e);
        }
    }

    // ---------------------------------------------------------------- what is turned off

    @Test
    void sharedMixinTakesBothSystems() {
        Map<String, List<String>> gone = new HashMap<String, List<String>>();
        gone.put("MixinHbmAnimationPacket", java.util.Collections.singletonList("gone"));
        Compat.Result r = Compat.resolve(gone, new HashMap<Feature, List<String>>());
        assertEquals(set(Feature.ARMS, Feature.INSTANT_SWITCH), r.off().keySet());
        // ARMS's other mixins go with it
        assertTrue(r.skipped().containsKey("MixinItemRenderWeaponBase"));
        assertTrue(r.skipped().containsKey("MixinG3Inspect"));
        assertFalse(r.skipped().containsKey("MixinLegoClient"));
    }

    @Test
    void coreTakesAll() {
        Map<Feature, List<String>> missing = new HashMap<Feature, List<String>>();
        missing.put(Feature.CORE, java.util.Collections.singletonList("ItemGunBaseNT"));
        Compat.Result r = Compat.resolve(new HashMap<String, List<String>>(), missing);
        assertEquals(Feature.values().length, r.off().size());
        Set<String> all = new HashSet<String>();
        for (Feature f : Feature.values()) all.addAll(f.mixins);
        assertEquals(all, r.skipped().keySet());
    }

    @Test
    void testedNtmTurnsNothingOff() throws IOException {
        Compat.Result r = NtmCheck.evaluate(mixins(), classes(), SAME, api());
        assertNotNull(r, "no NTM class could be read");
        assertTrue(r.skipped().isEmpty(), r.skipped().toString());
        assertTrue(r.off().isEmpty(), r.off().toString());
    }

    // with every mixin's problems, the hard ones too: the probe finds none with the NTM the port is built against
    @Test
    void probeFindsNothingWrongWithTheTestedNtm() {
        MixinProbe probe = new MixinProbe(classes());
        List<String> all = new ArrayList<String>();
        for (Feature f : Feature.values()) for (String m : f.mixins) {
            ClassNode node = mixins().get(m);
            assertNotNull(node, m);
            for (MixinProbe.Problem p : probe.check(node, 1)) all.add(p.toString());
        }
        assertTrue(all.isEmpty(), all.toString());
    }

    // NTM renames the method the bullet render mixin changes: only that system goes (in the port the Maresleg mixin
    // shares GunConfig's methods with MixinG3Inspect, so 26.2's getOrchestra would take ARMS too)
    @Test
    void renamedMethodTurnsOffOnlyItsSystem() throws IOException {
        MixinProbe.Classes real = classes();
        ClassNode render = NtmCheck.read(CompatPortTest.class.getClassLoader(), BULLET_RENDER);
        assertNotNull(render);
        for (MethodNode m : render.methods) if (m.name.equals("doRender") || m.name.equals("func_76986_a")) m.name = m.name + "Renamed";
        MixinProbe.Classes doctored = n -> n.equals(BULLET_RENDER) ? render : real.get(n);
        Compat.Result r = NtmCheck.evaluate(mixins(), doctored, SAME, api());
        assertNotNull(r);
        assertEquals(set(Feature.TRACERS), r.off().keySet(), r.off().toString());
        assertTrue(r.skipped().containsKey("MixinRenderBulletMK4"), r.skipped().toString());
        assertFalse(r.skipped().containsKey("MixinGunConfigAnims"));
    }

    @Test
    void noNtmTurnsNothingOff() throws IOException {
        MixinProbe.Classes real = classes();
        assertNull(NtmCheck.evaluate(mixins(), n -> n.startsWith("com/hbm/") ? null : real.get(n), SAME, api()));
    }

    // the refmap's names are the ones the probe looks for (a player's game: NTM calls Minecraft by SRG names)
    @Test
    void refmapRenamesWhatItMaps() {
        MixinProbe.Remap r = NtmCheck.refmap(CompatPortTest.class.getClassLoader());
        String mcp = "Lnet/minecraft/world/World;playSoundAtEntity(Lnet/minecraft/entity/Entity;Ljava/lang/String;FF)V";
        String srg = r.map("acup/gunfeel/mixins/MixinRicochetSound", mcp);
        assertTrue(srg.contains("func_72956_a"), srg);
        assertEquals("anything", r.map("acup/gunfeel/mixins/MixinRicochetSound", "anything"));
    }

    // A player's game: NTM's release jar (SRG names, libs/, the one RFG deobfuscates for the dev runs) read as it is,
    // the mixins' names through the refmap. A mixin into an NTM override of a Minecraft method left unmapped
    // (remap = false: MixinHoldAim's onUpdate, X378.68) misses there and nowhere in dev; Minecraft's own classes are
    // not readable by name in that game either.
    @Test
    void releaseNtmThroughTheRefmapTurnsNothingOff() throws IOException {
        Path jar = null;
        try (java.util.stream.Stream<Path> l = Files.list(Paths.get("libs"))) {
            for (Path p : (Iterable<Path>) l::iterator) if (p.getFileName().toString().startsWith("HBM-NTM-") && p.toString().endsWith(".jar")) jar = p;
        }
        assertNotNull(jar, "no NTM release jar in libs/");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            Map<String, ClassNode> cache = new HashMap<String, ClassNode>();
            MixinProbe.Classes release = n -> {
                if (!n.startsWith("com/hbm/")) return null;
                if (!cache.containsKey(n)) {
                    ClassNode node = null;
                    java.util.zip.ZipEntry e = zip.getEntry(n + ".class");
                    if (e != null) try (InputStream in = zip.getInputStream(e)) {
                        node = new ClassNode();
                        new org.objectweb.asm.ClassReader(in).accept(node, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
                    } catch (IOException x) {
                        throw new RuntimeException(x);
                    }
                    cache.put(n, node);
                }
                return cache.get(n);
            };
            MixinProbe.Remap remap = NtmCheck.refmap(CompatPortTest.class.getClassLoader());
            Compat.Result r = NtmCheck.evaluate(mixins(), release, remap, api());
            assertNotNull(r, "no NTM class could be read");
            assertTrue(r.skipped().isEmpty(), r.skipped().toString());
            assertTrue(r.off().isEmpty(), r.off().toString());
        }
    }

    // ---------------------------------------------------------------- the map and the configs

    // every mixin LateMixins hands on is in a system, and every system's mixin is one of them
    @Test
    void everyNtmMixinBelongsToASystem() {
        Set<String> mapped = new HashSet<String>();
        for (Feature f : Feature.values()) mapped.addAll(f.mixins);
        Set<String> listed = new HashSet<String>(acup.gunfeel.LateMixinsAccess.all());
        Set<String> unmapped = new HashSet<String>(listed);
        unmapped.removeAll(mapped);
        assertTrue(unmapped.isEmpty(), "mixins into NTM with no system: " + unmapped);
        Set<String> notListed = new HashSet<String>(mapped);
        notListed.removeAll(listed);
        assertTrue(notListed.isEmpty(), "systems name mixins LateMixins does not list: " + notListed);
    }

    // the late json lists no mixin itself (LateMixins does: else NtmCheck could not leave one out), the early one none
    // into NTM
    @Test
    void configsListNoNtmMixinThemselves() throws IOException {
        assertTrue(listed("mixins.ntmgunfeel.late.json").isEmpty());
        ClassLoader cl = CompatPortTest.class.getClassLoader();
        for (String m : listed("mixins.ntmgunfeel.json")) {
            ClassNode node = NtmCheck.read(cl, "acup/gunfeel/mixins/early/" + m);
            assertNotNull(node, m);
            for (String t : MixinProbe.targets(node)) assertFalse(t.startsWith("com/hbm/"), m + " changes NTM's " + t);
        }
    }

    @Test
    void everySystemHasNames() throws IOException {
        for (String lang : new String[] {"en_US", "ru_RU"}) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(RES.resolve("assets/ntmgunfeel/lang/" + lang + ".lang"))) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (Feature f : Feature.values()) assertTrue(p.containsKey(f.langKey()), lang + " lacks " + f.langKey());
        }
    }

    static List<String> listed(String config) throws IOException {
        JsonObject o = new JsonParser().parse(new String(Files.readAllBytes(RES.resolve(config)), StandardCharsets.UTF_8)).getAsJsonObject();
        List<String> out = new ArrayList<String>();
        for (String side : new String[] {"mixins", "client", "server"}) {
            JsonArray a = o.getAsJsonArray(side);
            if (a != null) for (int i = 0; i < a.size(); i++) out.add(a.get(i).getAsString());
        }
        return out;
    }

    @SafeVarargs
    static <T> Set<T> set(T... ts) {
        return new HashSet<T>(java.util.Arrays.asList(ts));
    }
}
