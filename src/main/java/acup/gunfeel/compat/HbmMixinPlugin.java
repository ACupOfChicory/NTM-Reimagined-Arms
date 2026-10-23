package acup.gunfeel.compat;

import java.io.BufferedReader;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModInfo;

// The plugin of ntmgunfeel-hbm.mixins.json (the mixins into NTM; the ones into Minecraft and Flywheel are in
// ntmgunfeel.mixins.json and are left alone). With a tested NTM (ntmTested in neoforge.mods.toml) it only reads the
// two versions. With any other it reads the NTM classes the mixins change and what the mod's code calls in NTM, before
// Mixin applies anything, and skips the mixins of every system that does not fit (Compat.resolve), so the game starts
// with those systems off instead of crashing. Anything that still fails while applying is caught by HbmMixinErrors.
// If this check itself goes wrong, nothing is turned off: the mod runs as it would without it.
public final class HbmMixinPlugin implements IMixinConfigPlugin {
    static final Logger LOG = LoggerFactory.getLogger("ntm-gunfeel");
    private static final String MIXIN_PATH = "acup/gunfeel/mixin/";

    @Override
    public void onLoad(String mixinPackage) {
        try {
            String hbm = null, range = "";
            for (ModInfo m : LoadingModList.get().getMods()) {
                if (m.getModId().equals(Compat.HBM_ID)) hbm = m.getVersion().toString();
                if (m.getModId().equals(Compat.MOD_ID)) {
                    Object r = m.getModProperties().get("ntmTested");
                    if (r != null) range = r.toString();
                    Compat.setModVersion(m.getVersion().toString());
                }
            }
            boolean fits = hbm == null || Compat.inRange(hbm, range);
            Compat.setVersions(hbm, range, fits);
            Mixins.registerErrorHandlerClass(HbmMixinErrors.class.getName());
            if (fits) return;
            LOG.warn("NTM {} is not a tested version for NTM Reimagined Arms (tested: {}), checking what still fits", hbm, range);
            check();
        } catch (Throwable t) {
            LOG.error("NTM compatibility check failed, nothing is turned off", t);
        }
    }

    private void check() throws IOException {
        long start = System.nanoTime();
        ClassLoader cl = HbmMixinPlugin.class.getClassLoader();
        Map<String, ClassNode> cache = new HashMap<>();
        MixinProbe.Classes classes = name -> cache.computeIfAbsent(name, n -> read(cl, n));
        Compat.Result r = evaluate(m -> read(cl, MIXIN_PATH + m), classes, lines(cl, ApiProbe.FILE));
        if (r == null) {
            LOG.error("Could not read any NTM class to check it, nothing is turned off");
            return;
        }
        Compat.apply(r);
        r.skipped().forEach((m, why) -> LOG.warn("Mixin {} is not applied: {}", m, why));
        r.off().forEach((f, why) -> LOG.warn("System {} is off: {}", f.id, why));
        LOG.warn("NTM check took {} ms: {} of {} systems off", (System.nanoTime() - start) / 1_000_000, r.off().size(),
                Feature.values().length);
    }

    // what to turn off: every system's mixins checked against NTM's classes, and what the mod calls in NTM (apiLines);
    // null when not one class the mixins change could be read, as the check would then turn off everything for nothing
    static Compat.Result evaluate(MixinProbe.Classes mixinClasses, MixinProbe.Classes classes, List<String> apiLines) {
        MixinProbe probe = new MixinProbe(classes);
        Set<String> mixins = new LinkedHashSet<>();
        for (Feature f : Feature.values()) mixins.addAll(f.mixins);
        Map<String, List<String>> problems = new LinkedHashMap<>();
        int targetsRead = 0;
        for (String m : mixins) {
            ClassNode node = mixinClasses.get(m);
            if (node == null) continue;
            for (String t : MixinProbe.targets(node)) if (classes.get(t) != null) targetsRead++;
            List<String> p = new ArrayList<>();
            for (MixinProbe.Problem pr : probe.check(node, 1)) {
                // a @Pseudo target that is not there is meant: the mixin just does nothing
                if (!(pr.soft() && pr.text().startsWith("@Pseudo target"))) p.add(pr.text());
            }
            problems.put(m, p);
        }
        if (targetsRead == 0) return null;
        return Compat.resolve(problems, ApiProbe.missing(apiLines, classes));
    }

    // the class as it is in its jar, not loaded and not transformed
    static ClassNode read(ClassLoader cl, String internal) {
        try (InputStream in = cl.getResourceAsStream(internal + ".class")) {
            if (in == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static List<String> lines(ClassLoader cl, String resource) throws IOException {
        List<String> out = new ArrayList<>();
        try (InputStream in = cl.getResourceAsStream(resource)) {
            if (in == null) return out;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String l; (l = r.readLine()) != null; ) out.add(l);
        }
        return out;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !Compat.isSkipped(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
