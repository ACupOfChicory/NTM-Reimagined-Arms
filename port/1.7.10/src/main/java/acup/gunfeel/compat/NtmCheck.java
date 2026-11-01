package acup.gunfeel.compat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import acup.gunfeel.GunFeel;

// port: 26.2's HbmMixinPlugin (compat/HbmMixinPlugin.java, e284339) for GTNHMixins' late mixins: LateMixins asks it
// which of its mixins into NTM to hand on. With any NTM, tested (Compat.TESTED) or not, it reads the NTM classes the mixins change and what the mod's code calls in NTM (ntmgunfeel-ntm-api.txt), before
// Mixin applies anything, and leaves out the mixins of every system that does not fit (Compat.resolve), so the game
// starts with those systems off instead of crashing. If this check itself goes wrong, nothing is turned off.
// In a player's game NTM calls Minecraft by SRG names: the mixins' selectors and @At targets are read through the
// mod's refmap there (in dev both are MCP's).
public final class NtmCheck {
    static final String MIXIN_PATH = "acup/gunfeel/mixins/", REFMAP = "mixins.ntmgunfeel.refmap.json", FORCE_OFF = "ntmgunfeel.forceOff";

    private NtmCheck() {
    }

    // the mixins to apply of `wanted`, NTM being `hbm` (its version, null: not known)
    public static List<String> filter(List<String> wanted, String hbm) {
        try {
            // a dev run may stand in another range (-Ptested), to run the check with the NTM at hand
            String tested = System.getProperty("ntmgunfeel.tested", Compat.TESTED);
            boolean fits = hbm != null && Compat.inRange(hbm, tested);
            Compat.setVersions(hbm, tested, fits);
            // checked with a tested NTM too: a mixin that misses its target (a name the refmap lacks) turns its system off
            // instead of failing the game's start
            if (!fits) GunFeel.LOGGER.warn("NTM {} is not a tested version for NTM Reimagined Arms (tested builds: {}), checking what still fits", hbm,
                    tested);
            Compat.Result r = check();
            if (r == null) return wanted;
            List<String> out = new ArrayList<String>();
            for (String m : wanted) if (!Compat.isSkipped(m)) out.add(m);
            return out;
        } catch (Throwable t) {
            GunFeel.LOGGER.error("NTM compatibility check failed, nothing is turned off", t);
            return wanted;
        }
    }

    private static Compat.Result check() throws IOException {
        long start = System.nanoTime();
        ClassLoader cl = NtmCheck.class.getClassLoader();
        final Map<String, ClassNode> cache = new HashMap<String, ClassNode>();
        MixinProbe.Classes classes = name -> {
            if (!cache.containsKey(name)) cache.put(name, read(cl, name));
            return cache.get(name);
        };
        MixinProbe.Remap remap = deobfuscated() ? (m, n) -> n : refmap(cl);
        Compat.Result r = evaluate(m -> read(cl, MIXIN_PATH + m), classes, remap, lines(cl, ApiProbe.FILE));
        if (r == null) {
            GunFeel.LOGGER.error("Could not read any NTM class to check it, nothing is turned off");
            return null;
        }
        Compat.apply(r);
        for (Map.Entry<String, String> e : r.skipped().entrySet()) GunFeel.LOGGER.warn("Mixin {} is not applied: {}", e.getKey(), e.getValue());
        for (Map.Entry<Feature, String> e : r.off().entrySet()) GunFeel.LOGGER.warn("System {} is off: {}", e.getKey().id, e.getValue());
        String took = "NTM check took {} ms: {} of {} systems off";
        if (r.off().isEmpty()) GunFeel.LOGGER.info(took, (System.nanoTime() - start) / 1_000_000, 0, Feature.values().length);
        else GunFeel.LOGGER.warn(took, (System.nanoTime() - start) / 1_000_000, r.off().size(), Feature.values().length);
        return r;
    }

    // what to turn off: every system's mixins checked against NTM's classes, and what the mod calls in NTM (apiLines);
    // null when not one class the mixins change could be read, as the check would then turn off everything for nothing
    static Compat.Result evaluate(MixinProbe.Classes mixinClasses, MixinProbe.Classes classes, MixinProbe.Remap remap, List<String> apiLines) {
        MixinProbe probe = new MixinProbe(classes, remap);
        Set<String> mixins = new LinkedHashSet<String>();
        for (Feature f : Feature.values()) mixins.addAll(f.mixins);
        Map<String, List<String>> problems = new LinkedHashMap<String, List<String>>();
        int targetsRead = 0;
        for (String m : mixins) {
            ClassNode node = mixinClasses.get(m);
            if (node == null) continue;
            for (String t : MixinProbe.targets(node)) if (classes.get(t) != null) targetsRead++;
            List<String> p = new ArrayList<String>();
            for (MixinProbe.Problem pr : probe.check(node, 1)) {
                // a @Pseudo target that is not there is meant: the mixin just does nothing; a soft miss (require = 0)
                // is one Mixin lets pass
                if (!pr.soft()) p.add(pr.text());
            }
            problems.put(m, p);
        }
        if (targetsRead == 0) return null;
        // a dev run may turn systems off by hand (-PforceOff=tracers,hud), to see the game without them
        String forced = System.getProperty(FORCE_OFF);
        if (forced != null) for (String id : forced.split(",")) {
            Feature f = Feature.byId(id.trim());
            if (f == null) continue;
            for (String m : f.mixins) if (problems.containsKey(m)) problems.get(m).add("turned off by hand (-PforceOff)");
        }
        return Compat.resolve(problems, ApiProbe.missing(apiLines, classes));
    }

    // a dev game (MCP names everywhere)
    static boolean deobfuscated() {
        try {
            Object dev = net.minecraft.launchwrapper.Launch.blackboard.get("fml.deobfuscatedEnvironment");
            return Boolean.TRUE.equals(dev);
        } catch (Throwable t) {
            return false;
        }
    }

    // the refmap's "mappings": per mixin (internal name) its names as the game has them
    static MixinProbe.Remap refmap(ClassLoader cl) {
        final Map<String, Map<String, String>> maps = new HashMap<String, Map<String, String>>();
        try (InputStream in = cl.getResourceAsStream(REFMAP)) {
            if (in != null) {
                JsonObject root = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject mappings = root.getAsJsonObject("mappings");
                if (mappings != null) for (Map.Entry<String, JsonElement> e : mappings.entrySet()) {
                    Map<String, String> m = new HashMap<String, String>();
                    for (Map.Entry<String, JsonElement> k : e.getValue().getAsJsonObject().entrySet()) m.put(k.getKey(), k.getValue().getAsString());
                    maps.put(e.getKey(), m);
                }
            }
        } catch (IOException | RuntimeException e) {
            GunFeel.LOGGER.warn("Could not read the mixins' refmap: NTM is checked by the names in the mixins", e);
        }
        return (mixin, name) -> {
            Map<String, String> m = maps.get(mixin);
            String to = m == null ? null : m.get(name);
            return to == null ? name : to;
        };
    }

    // the class as it is in its jar, not loaded and not transformed
    static ClassNode read(ClassLoader cl, String internal) {
        try (InputStream in = cl.getResourceAsStream(internal + ".class")) {
            if (in == null) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) bytes.write(buf, 0, n);
            ClassNode node = new ClassNode();
            new ClassReader(bytes.toByteArray()).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static List<String> lines(ClassLoader cl, String resource) throws IOException {
        List<String> out = new ArrayList<String>();
        try (InputStream in = cl.getResourceAsStream(resource)) {
            if (in == null) return out;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String l; (l = r.readLine()) != null; ) out.add(l);
        }
        return out;
    }
}
