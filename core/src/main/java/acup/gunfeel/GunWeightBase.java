package acup.gunfeel;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A gun's kind and weight by its item's name (the mod's file ntmgunfeel-gun-weight.txt in the jar) and the speed a
 * heavy one leaves (the server's settings). GunWeight in 26.2: by the game's stack, the speed's modifier, the view, the
 * tooltip.
 */
public class GunWeightBase {
    GunWeightBase() {
    }

    public enum Weight {
        LIGHT, MEDIUM, HEAVY, SUPER_HEAVY;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A gun's kind (pistol, smg, rifle, ...) and its weight. */
    public record Gun(String kind, Weight weight) {
    }

    public static final String FILE = "/ntmgunfeel-gun-weight.txt";
    public static final String SLOW_KEY = "gunWeightSlow", HEAVY_KEY = "gunWeightHeavySpeed", SUPER_KEY = "gunWeightSuperHeavySpeed";
    public static final float HEAVY = 0.94F, SUPER_HEAVY = 0.88F;
    private static final Map<String, Gun> GUNS = load();

    static Map<String, Gun> load() {
        Map<String, Gun> out = new HashMap<>();
        try (var in = GunWeightBase.class.getResourceAsStream(FILE)) {
            if (in == null) return out;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null;) {
                line = line.strip();
                Gun g = line(line);
                if (g != null) out.put(line.substring(0, line.indexOf('=')).strip(), g);
            }
        } catch (java.io.IOException e) {
            // no file: no classes
        }
        return Map.copyOf(out);
    }

    /**
     * One line of the file: {@code id = kind, weight}. Null for a comment, a broken line, or "none, none" (not a weapon);
     * an unknown weight leaves the kind with no weight (no slowing).
     */
    static Gun line(String line) {
        line = line.strip();
        if (line.isEmpty() || line.startsWith("#")) return null;
        int eq = line.indexOf('=');
        if (eq <= 0) return null;
        String[] p = line.substring(eq + 1).split(",");
        String kind = p[0].strip().toLowerCase(Locale.ROOT);
        Weight w = null;
        String word = p.length > 1 ? p[1].strip() : "";
        // "super-heavy" as the tooltip writes it is "super_heavy" too
        if (!word.isEmpty() && !word.equalsIgnoreCase("none")) try {
            w = Weight.valueOf(word.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            // a typo would quietly take the slowing away: said in the log
            org.slf4j.LoggerFactory.getLogger("ntm-gunfeel").warn("{}: \"{}\" is no weight (light, medium, heavy, super_heavy, none)", FILE, line);
        }
        if (kind.isEmpty() || kind.equals("none") && w == null) return null;
        return new Gun(kind, w);
    }

    /** A gun by its item id ({@code hbm:gun_minigun}), or null. */
    public static Gun gun(String id) {
        return GUNS.get(id);
    }

    /** The weight class of a gun by its item id, or null. */
    public static Weight of(String id) {
        Gun g = gun(id);
        return g == null ? null : g.weight();
    }

    static Map<String, Gun> all() {
        return GUNS;
    }

    /** Whether this server slows the players with heavy guns. */
    public static boolean enabled() {
        return ReaimTimes.flag(SLOW_KEY, true);
    }

    /** The share of its speed a class leaves on this server: 1 for light and medium. */
    public static float speed(Weight w) {
        if (w == Weight.HEAVY) return share(ReaimTimes.number(HEAVY_KEY, HEAVY), HEAVY);
        if (w == Weight.SUPER_HEAVY) return share(ReaimTimes.number(SUPER_KEY, SUPER_HEAVY), SUPER_HEAVY);
        return 1F;
    }

    // a key's share, 0.5 to 1; one that is no number (NaN) is its default
    static float share(float v, float def) {
        return Float.isNaN(v) ? def : Math.clamp(v, 0.5F, 1F);
    }

    /** The heavier of the two: null below everything. */
    static Weight heavier(Weight a, Weight b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
