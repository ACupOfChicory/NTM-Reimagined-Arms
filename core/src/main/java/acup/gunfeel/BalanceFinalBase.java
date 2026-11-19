package acup.gunfeel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What BalanceFinal knows without the game: whether the scheme is on, its keys worked out (balance.final.*: the pairs'
 * file, the ladder's parameters, the sets with the kits' pools, the blasts' and the range's rules, the acid) and the stand's
 * questions of them (the pairs, a pair's hit clicks on a set, a set's tier under the acid). BalanceFinal extends it on
 * each version and takes the hit off the game (the shot's pair by its projectile, the set worn, the blast under way):
 * its callers name these members through it, BalanceFinal.pairs, BalanceFinal.DEFAULT_SETS and so on.
 */
public class BalanceFinalBase {
    BalanceFinalBase() {
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ntm-gunfeel");
    private static final String PAIRS = "/ntmgunfeel-balance-final-pairs.tsv";

    public static boolean on() {
        return ReaimTimes.flag("balance.final", true) && BalanceArmorBase.on();
    }

    // ---------------------------------------------------------------- the keys

    /** what the keys say now, worked out again when the settings change */
    record State(Object version, BalanceFinalRule.Params params, Map<String, BalanceFinalRule.Pair> pairs, BalanceFinalRule.Refs refs,
            Map<String, BalanceFinalRule.Armor> sets, double[] weights, double empty,
            Map<CacheKey, Double> cache, BalanceBlastRule.Rule blast, BalanceRangeRule.Rule range, BalanceFinalRule.Acid acid) {
    }

    /** a cell of the cache: the pair and the set it meets */
    record CacheKey(String pair, BalanceFinalRule.Armor armor) {
    }

    private static volatile State state;
    private static List<String> lines;

    static State state() {
        Object v = ReaimTimes.version();
        State s = state;
        if (s != null && s.version().equals(v)) return s;
        synchronized (BalanceFinalBase.class) {
            s = state;
            if (s != null && s.version().equals(v)) return s;
            state = s = build(v);
            return s;
        }
    }

    private static List<String> lines() {
        if (lines == null) {
            try (InputStream in = BalanceFinalBase.class.getResourceAsStream(PAIRS)) {
                lines = in == null ? List.of() : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            } catch (IOException e) {
                LOGGER.warn("Could not read the balance's final pairs", e);
                lines = List.of();
            }
        }
        return lines;
    }

    private static double num(String key, double def, double min, double max) {
        float v = ReaimTimes.number(key, (float) def);
        return Float.isFinite(v) && v >= min && v <= max ? v : def;
    }

    private static State build(Object version) {
        var d = BalanceFinalRule.Params.DEFAULT;
        String[] ch = ReaimTimes.text("balance.final.character", d.beta() + ":" + d.lo() + ":" + d.hi()).trim().split(":");
        double beta = d.beta(), lo = d.lo(), hi = d.hi();
        try {
            if (ch.length == 3) {
                double b = Double.parseDouble(ch[0]), l = Double.parseDouble(ch[1]), h = Double.parseDouble(ch[2]);
                if (b >= 0 && l > 0 && h >= l) {
                    beta = b;
                    lo = l;
                    hi = h;
                }
            }
        } catch (NumberFormatException ignored) {
            // the defaults
        }
        double ammoK = d.ammoK(), from = d.ammoFrom(), most = d.ammoMax(), jhp = d.jhp();
        for (String e : ReaimTimes.text("balance.final.ammo", "").trim().split("\\s+")) {
            int c = e.indexOf('=');
            if (c <= 0) continue;
            try {
                double x = Double.parseDouble(e.substring(c + 1));
                switch (e.substring(0, c)) {
                    case "pen" -> ammoK = x;
                    case "from" -> from = x;
                    case "max" -> most = x;
                    case "jhp" -> jhp = x;
                    default -> {
                    }
                }
            } catch (NumberFormatException ignored) {
                // left out
            }
        }
        Map<String, Double> roles = new HashMap<>(d.roles());
        roles.putAll(BalanceFinalRule.roles(ReaimTimes.text("balance.final.roles", BalanceFinalRule.Params.ROLES)));
        var params = new BalanceFinalRule.Params(BalanceFinalRule.anchors(ReaimTimes.text("balance.final.ttk", BalanceFinalRule.Params.TTK)),
                num("balance.final.ttk.min", d.tmin(), 0.05, 60), num("balance.final.ttk.max", d.tmax(), 1, 1e6), num("balance.final.ttk.below", d.below(), 1, 100),
                beta, lo, hi, num("balance.final.energy", d.energy(), 0.01, 100), ammoK, from, most, jhp, num("balance.final.ped", d.ped(), -5, 5),
                1.0, num("balance.final.dnsOverFau", d.dnsOverFau(), 0, 100), d.laserTrench(), Map.copyOf(roles),
                num("balance.final.slug.pellets", d.slugPellets(), 0, 1000),
                BalanceFinalRule.MagRule.parse(ReaimTimes.text("balance.final.mag", ""), d.mag()),
                Map.copyOf(BalanceFinalRule.roleOfs(ReaimTimes.text("balance.final.roleOf", BalanceFinalRule.Params.ROLE_OF))),
                Map.copyOf(BalanceFinalRule.roles(ReaimTimes.text("balance.final.roles.fade", BalanceFinalRule.Params.FADE))));
        // the pairs, P the stand's (Fable №2, 1)
        List<String> bad = new ArrayList<>();
        var pairs = BalanceFinalRule.pairs(lines(), bad, true);
        for (String b : bad) LOGGER.warn("The balance's final pairs: a line not read: {}", b);
        List<BalanceFinalRule.Pair> formula = new ArrayList<>(pairs.values());
        Map<String, BalanceFinalRule.Armor> sets = new HashMap<>();
        DEFAULT_SETS.forEach((name, a) -> {
            var kit = kitOf(name);
            // the kits' pools the scheme counts in (balance.final.pools), whatever the pools' own keys: the stand makes them 0.0001
            sets.put(name, kit == null ? a : new BalanceFinalRule.Armor(a.kind(), a.tier(), a.hours(), a.en(), pools(kit)));
        });
        double[] weights = {3, 8, 6, 3};
        String[] w = ReaimTimes.text("balance.final.armor.weights", "3,8,6,3").split(",");
        if (w.length == 4) try {
            double[] x = new double[4];
            for (int i = 0; i < 4; i++) x[i] = Double.parseDouble(w[i].trim());
            if (x[0] >= 0 && x[1] >= 0 && x[2] >= 0 && x[3] >= 0 && x[0] + x[1] + x[2] + x[3] > 0) weights = x;
        } catch (NumberFormatException ignored) {
            // the defaults
        }
        var blast = new BalanceBlastRule.Rule(ReaimTimes.flag("balance.final.blast", true),
                parsed("balance.final.blast.launchers", BalanceBlastRule.LAUNCHERS, BalanceBlastRule::launchers),
                parsed("balance.final.blast.rounds", BalanceBlastRule.ROUNDS, BalanceBlastRule::tiers),
                parsed("balance.final.blast.grenades", BalanceBlastRule.GRENADES, BalanceBlastRule::tiers),
                num("balance.final.blast.shift", 1, -5, 5), BalanceBlastRule.cap(ReaimTimes.text("balance.final.blast.pair", "cap:2"), 2),
                BalanceBlastRule.hits(ReaimTimes.text("balance.final.blast.hits", ""), BalanceBlastRule.Hits.DEFAULT),
                parsed("balance.final.blast.frags", BalanceBlastRule.FRAGS, BalanceBlastRule::frags));
        var range = new BalanceRangeRule.Rule(parsed("balance.final.range", BalanceRangeRule.LINES, BalanceRangeRule::lines),
                num("balance.final.range.close", 8, 0, 256), Map.copyOf(BalanceFinalRule.roles(ReaimTimes.text("balance.final.range.home", BalanceRangeRule.HOME))),
                parsed("balance.final.range.lineOf", BalanceRangeRule.LINE_OF, BalanceRangeRule::lineOfs));
        return new State(version, params, pairs, BalanceFinalRule.refs(formula), Map.copyOf(sets), weights,
                num("balance.final.armor.empty", 0, 0, 20), new ConcurrentHashMap<>(), blast, range,
                BalanceFinalRule.Acid.parse(ReaimTimes.text("balance.final.acid", ACID), BalanceFinalRule.Acid.DEFAULT));
    }

    /** the kit of a full set of {@code name} (NTM's name), or null */
    private static EuphImmunityBase.Kit kitOf(String name) {
        for (var k : EuphImmunityBase.Kit.values()) if (k.set.equals(name)) return k;
        return null;
    }

    /** balance.final.pools: the pools of DNS, FAU and euphemium the scheme counts in, "dns:45 fau:40 euph:150" */
    public static final String POOLS = "dns:45 fau:40 euph:150";

    /** the pool of {@code kit} by balance.final.pools (Fable 2.6: DNS 45, FAU 40; X912: dns:40 fau:45) */
    static double pools(EuphImmunityBase.Kit kit) {
        String name = kit == EuphImmunityBase.Kit.EUPHEMIUM ? "euph" : kit.set;
        var byKey = BalanceFinalRule.roles(ReaimTimes.text("balance.final.pools", POOLS));
        Double v = byKey.get(name);
        if (v == null) v = BalanceFinalRule.roles(POOLS).get(name);
        return v == null ? kit.size : v;
    }

    /** the size of a kit's pool when its key balance.immune*.pool is not set: balance.final.pools with the scheme on, the kit's own off */
    static float poolOf(EuphImmunityBase.Kit kit) {
        try {
            return on() ? (float) pools(kit) : kit.size;
        } catch (RuntimeException e) {
            return kit.size;
        }
    }

    private static BalanceFinalRule.Armor a(String kind, double t, double h, double en, double pool) {
        return new BalanceFinalRule.Armor(kind, t, h, en, pool);
    }

    /**
     * the scheme's sets by NTM's names (the items' names before _helmet and the rest): tier, tier by the hours, beam
     * shift, the pool of a full set (the kits' own sizes, whatever the pools' keys: the stand makes them 0.0001; a player's kit in the mode pool alone has one, armorOf)
     */
    static final Map<String, BalanceFinalRule.Armor> DEFAULT_SETS = Map.ofEntries(Map.entry("steel", a("steel", 1, 1, 0, 0)),
            Map.entry("cobalt", a("cobalt", 1, 1, 0, 0)), Map.entry("alloy", a("alloy", 1, 1, 0, 0)), Map.entry("titanium", a("titanium", 1, 1, 0, 0)),
            Map.entry("taurun", a("taurun", 1, 1, 0, 0)), Map.entry("dieselsuit", a("dieselsuit", 2, 2, 0, 0)), Map.entry("t51", a("t51", 3, 3, 0, 0)),
            Map.entry("steamsuit", a("steamsuit", 2.5, 4, 0, 0)), Map.entry("security", a("security", 2.5, 4, 0, 0)), Map.entry("hev", a("hev", 4, 4, 0, 0)),
            Map.entry("ajr", a("ajr", 4, 4, 0, 0)), Map.entry("ajro", a("ajro", 4, 4, 0, 0)), Map.entry("envsuit", a("envsuit", 4, 4, 0, 0)),
            Map.entry("trenchmaster", a("trench", 6, 6, 0, 0)), Map.entry("bismuth", a("bismuth", 6, 6, 0, 0)), Map.entry("starmetal", a("starmetal", 6, 6, 0, 0)),
            Map.entry("bj", a("bj", 6, 6, 0, 0)), Map.entry("ncrpa", a("ncrpa", 6, 6, 0, 0)), Map.entry("cmb", a("cmb", 7, 7, 0, 0)),
            Map.entry("schrabidium", a("schrabidium", 7, 7, 0, 0)), Map.entry("rpa", a("rpa", 7, 7, 0, 0)),
            Map.entry("fau", a("fau", 8, 8, 0, EuphImmunityBase.Kit.FAU.size)), Map.entry("dns", a("dns", 9, 8, 0, EuphImmunityBase.Kit.DNS.size)),
            Map.entry("euphemium", a("euph", 9, 9, 0, EuphImmunityBase.Kit.EUPHEMIUM.size)));

    /** balance.final.acid by default (off: X922) */
    public static final String ACID = "tiers=1.0 tier=4 full=+1 fade=3";

    /** a key's entries; a value of which nothing reads (not empty, not "off") is the default's, not none */
    private static <T> Map<String, T> parsed(String key, String def, java.util.function.Function<String, Map<String, T>> parse) {
        String text = ReaimTimes.text(key, def);
        Map<String, T> out = parse.apply(text);
        if (out.isEmpty() && text != null && !text.isBlank() && !text.trim().equalsIgnoreCase("off")) out = parse.apply(def);
        return Map.copyOf(out);
    }

    /** the stand: the scheme's pairs by id (gun.ammo) */
    static Map<String, BalanceFinalRule.Pair> pairs() {
        return state().pairs();
    }

    /** the stand: the hit clicks of a pair on a set by the model's or NTM's names (NaN: no such pair or set) */
    static double hitClicks(String pair, String set) {
        State s = state();
        var pr = s.pairs().get(pair);
        var a = s.sets().get(set);
        if (a == null) a = BalanceFinalRule.MODEL_SETS.get(set);
        if (pr == null || a == null) return Double.NaN;
        return BalanceFinalRule.HP / BalanceFinalRule.clickDamage(pr, a, s.sets().get("fau"), s.refs(), s.params());
    }

    /** the stand: the same on the set with its tier {@code tier} (NaN: no such pair or set) */
    static double hitClicksAt(String pair, String set, double tier) {
        State s = state();
        var pr = s.pairs().get(pair);
        var a = s.sets().get(set);
        if (a == null) a = BalanceFinalRule.MODEL_SETS.get(set);
        if (pr == null || a == null) return Double.NaN;
        var at = new BalanceFinalRule.Armor(a.kind(), tier, a.hours(), a.en(), a.pool());
        return BalanceFinalRule.HP / BalanceFinalRule.clickDamage(pr, at, s.sets().get("fau"), s.refs(), s.params());
    }

    /** the stand: the set's tier by the acid's rule at corrosion {@code k} (NaN: no such set) */
    static double acidTier(String set, float k) {
        State s = state();
        var a = s.sets().get(set);
        if (a == null) a = BalanceFinalRule.MODEL_SETS.get(set);
        return a == null ? Double.NaN : s.acid().on() ? s.acid().tierOf(a.tier(), k) : a.tier();
    }
}
