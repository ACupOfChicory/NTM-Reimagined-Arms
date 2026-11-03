package acup.gunfeel.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// port: 26.2's Compat (compat/Compat.java, e284339), through the 1.7.10 port. NTM CE's version is
// "MAJORMOD.MAJORAPI.MINOR.PATCH" ("2.6.1.0", its jar NTM-CE-1.12.2-2.6.1.0): the tested range is of those, TESTED here
// (26.2: ntmTested in neoforge.mods.toml; 1.12.2's mcmod.info has no such field). Maven's VersionRange -> a plain
// [low,high] range of versions compared part by part (build).
// What is turned off is decided the same (resolve).
// How the installed NTM fits the mod. In the tested range everything runs as built (the build checks every mixin
// against that NTM: CompatTest). Outside it LateMixins checks each mixin into NTM and what the mod's code calls in NTM
// before anything is applied, and turns off the systems (Feature) that do not fit; the player is told in chat, the
// server in its log.
public final class Compat {
    public static final String MOD_ID = "ntmgunfeel", HBM_ID = "hbm";
    // the NTM builds this version was tried with
    public static final String TESTED = "[2.6.1.0,2.6.1.0]";

    private static volatile String hbmVersion = "", tested = "", modVersion = "";
    private static volatile boolean inRange = true;
    // mixin simple name -> why it was not applied; system -> why it is off
    private static final Map<String, String> SKIPPED = Collections.synchronizedMap(new LinkedHashMap<String, String>());
    private static final Map<Feature, String> OFF = Collections.synchronizedMap(new EnumMap<Feature, String>(Feature.class));

    private Compat() {
    }

    // ---------------------------------------------------------------- versions

    private static final Pattern BUILD = Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:\\.(\\d+))?");

    // NTM CE's version ("2.6.1.0") as one number that orders as the versions do (each part 0-999), -1 when there is none
    public static long build(String version) {
        if (version == null) return -1;
        Matcher m = BUILD.matcher(version.trim());
        if (!m.find()) return -1;
        long b = 0;
        for (int i = 1; i <= 4; i++) b = b * 1000 + (m.group(i) == null ? 0 : Math.min(999, Integer.parseInt(m.group(i))));
        return b;
    }

    // "[low,high]" of versions ("(" or ")" for an open end, an empty side for none); a version or range that does
    // not parse counts as in it: nothing is turned off for a typo
    public static boolean inRange(String version, String range) {
        if (range == null || range.trim().isEmpty()) return true;
        long b = build(version);
        String r = range.trim();
        if (b < 0 || r.length() < 3 || "[(".indexOf(r.charAt(0)) < 0 || "])".indexOf(r.charAt(r.length() - 1)) < 0) return true;
        String[] lh = r.substring(1, r.length() - 1).split(",", -1);
        try {
            if (lh.length == 1) return b == bound(lh[0]);
            if (lh.length != 2) return true;
            String lo = lh[0].trim(), hi = lh[1].trim();
            if (!lo.isEmpty() && (r.charAt(0) == '[' ? b < bound(lo) : b <= bound(lo))) return false;
            if (!hi.isEmpty() && (r.charAt(r.length() - 1) == ']' ? b > bound(hi) : b >= bound(hi))) return false;
            return true;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static long bound(String v) {
        long b = build(v);
        if (b < 0) throw new NumberFormatException(v);
        return b;
    }

    // the newest version the range names: its top bound, or its bottom one when it has none
    public static String lastSupported(String range) {
        String r = range.trim();
        if (r.length() < 3) return range;
        String[] lh = r.substring(1, r.length() - 1).split(",", -1);
        String last = lh[lh.length - 1].trim();
        return last.isEmpty() ? lh[0].trim() : last;
    }

    // ---------------------------------------------------------------- what to turn off

    public record Result(Map<String, String> skipped, Map<Feature, String> off) {
    }

    // mixinProblems: per mixin what does not fit NTM (empty = fits); apiMissing: per system the NTM members its code
    // calls that NTM no longer has. A system with a mixin that does not fit, or with something missing, is off; all
    // mixins of a system that is off are skipped, which can take down another system sharing one; CORE takes all.
    public static Result resolve(Map<String, List<String>> mixinProblems, Map<Feature, List<String>> apiMissing) {
        Map<String, String> skipped = new LinkedHashMap<String, String>();
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
