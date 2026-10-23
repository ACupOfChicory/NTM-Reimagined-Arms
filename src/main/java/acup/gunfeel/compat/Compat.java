package acup.gunfeel.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.Restriction;
import org.apache.maven.artifact.versioning.VersionRange;

// How the installed NTM fits the mod. The tested NTM versions are ntmTested in neoforge.mods.toml (a recommendation:
// NeoForge loads any NTM). In that range everything runs as built (the build checks every mixin against that NTM).
// Outside it HbmMixinPlugin checks each mixin into NTM and what the mod's code calls in NTM before anything is
// applied, and turns off the systems (Feature) that do not fit; the player sees it on CompatScreen, the server in its
// log, anyone in /gunfeel status.
public final class Compat {
    public static final String MOD_ID = "ntmgunfeel", HBM_ID = "hbm", HBM_CONFIG = "ntmgunfeel-hbm.mixins.json";

    private static volatile String hbmVersion = "", tested = "", modVersion = "";
    private static volatile boolean inRange = true;
    // mixin simple name -> why it was not applied; system -> why it is off
    private static final Map<String, String> SKIPPED = Collections.synchronizedMap(new LinkedHashMap<>());
    private static final Map<Feature, String> OFF = Collections.synchronizedMap(new EnumMap<>(Feature.class));

    private Compat() {
    }

    // ---------------------------------------------------------------- versions (NeoForge compares them the same way)

    // a range that does not parse counts as no range: nothing is turned off for a typo. The build after '+' is left
    // out (NTM's jar is 1.0.3+1b8c6c2d, which Maven would not count as 1.0.3 in [1.0.3])
    public static boolean inRange(String version, String range) {
        if (range == null || range.isBlank()) return true;
        int plus = version.indexOf('+');
        if (plus > 0) version = version.substring(0, plus);
        try {
            return VersionRange.createFromVersionSpec(range.trim()).containsVersion(new DefaultArtifactVersion(version));
        } catch (InvalidVersionSpecificationException e) {
            return true;
        }
    }

    // the newest version the range names: its top bound, or its bottom one when it has none
    public static String lastSupported(String range) {
        try {
            VersionRange r = VersionRange.createFromVersionSpec(range.trim());
            if (r.getRecommendedVersion() != null) return r.getRecommendedVersion().toString();
            List<Restriction> rs = r.getRestrictions();
            if (rs.isEmpty()) return range;
            Restriction last = rs.get(rs.size() - 1);
            ArtifactVersion v = last.getUpperBound() != null ? last.getUpperBound() : last.getLowerBound();
            return v == null ? range : v.toString();
        } catch (InvalidVersionSpecificationException | RuntimeException e) {
            return range;
        }
    }

    // ---------------------------------------------------------------- what to turn off

    public record Result(Map<String, String> skipped, Map<Feature, String> off) {
    }

    // mixinProblems: per mixin what does not fit NTM (empty = fits); apiMissing: per system the NTM members its code
    // calls that NTM no longer has. A system with a mixin that does not fit, or with something missing, is off; all
    // mixins of a system that is off are skipped, which can take down another system sharing one; CORE takes all.
    public static Result resolve(Map<String, List<String>> mixinProblems, Map<Feature, List<String>> apiMissing) {
        Map<String, String> skipped = new LinkedHashMap<>();
        Map<Feature, String> off = new EnumMap<>(Feature.class);
        mixinProblems.forEach((m, p) -> {
            if (!p.isEmpty()) skipped.put(m, p.get(0) + more(p));
        });
        apiMissing.forEach((f, p) -> {
            if (!p.isEmpty()) off.put(f, "NTM: " + p.get(0) + more(p));
        });
        if (off.containsKey(Feature.CORE)) {
            for (Feature f : Feature.values()) off.putIfAbsent(f, "core");
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Feature f : Feature.values()) {
                if (off.containsKey(f)) {
                    for (String m : f.mixins) if (skipped.putIfAbsent(m, "system " + f.id + " off") == null) changed = true;
                } else {
                    for (String m : f.mixins) {
                        if (skipped.containsKey(m)) {
                            off.put(f, m + ": " + skipped.get(m));
                            changed = true;
                            break;
                        }
                    }
                }
            }
        }
        return new Result(skipped, off);
    }

    private static String more(List<String> p) {
        return p.size() > 1 ? " (+" + (p.size() - 1) + ")" : "";
    }

    // ---------------------------------------------------------------- state

    static void setVersions(String hbm, String testedRange, boolean fits) {
        hbmVersion = hbm == null ? "" : hbm;
        tested = testedRange == null ? "" : testedRange;
        inRange = fits;
    }

    public static void setModVersion(String v) {
        modVersion = v == null ? "" : v;
    }

    static void apply(Result r) {
        SKIPPED.putAll(r.skipped());
        OFF.putAll(r.off());
        for (Feature f : r.off().keySet()) f.set(false);
    }

    // a mixin into NTM failed while being applied, past the checks (HbmMixinErrors): its systems go off from now on
    static void failedLate(String mixin, String reason) {
        Map<String, List<String>> p = new LinkedHashMap<>();
        synchronized (SKIPPED) {
            SKIPPED.forEach((m, why) -> p.put(m, List.of(why)));
        }
        p.put(mixin, List.of(reason));
        apply(resolve(p, Map.of()));
    }

    public static String hbmVersion() {
        return hbmVersion;
    }

    public static String tested() {
        return tested;
    }

    public static String modVersion() {
        return modVersion;
    }

    public static boolean inRange() {
        return inRange;
    }

    static boolean isSkipped(String mixin) {
        return !SKIPPED.isEmpty() && SKIPPED.containsKey(mixin);
    }

    public static Map<String, String> skipped() {
        synchronized (SKIPPED) {
            return new LinkedHashMap<>(SKIPPED);
        }
    }

    public static Map<Feature, String> off() {
        synchronized (OFF) {
            Map<Feature, String> m = new EnumMap<>(Feature.class);
            m.putAll(OFF);
            return m;
        }
    }

    public static List<Feature> offList() {
        return new ArrayList<>(off().keySet());
    }
}
