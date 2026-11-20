package acup.gunfeel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.FinalShots;
import acup.gunfeel.host.HurtNow;

/**
 * What BalanceFinal knows without the game: whether the scheme is on, its keys worked out (balance.final.*: the pairs'
 * file, the ladder's parameters, the sets with the kits' pools, the blasts' and the range's rules, the acid) and the stand's
 * questions of them, shot pairs and ordinary hits. BalanceFinal extends it on
 * each version and supplies the live reads and blast callbacks through FinalShots:
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

    // ---------------------------------------------------------------- the set worn

    /** the scheme's view of one hit: its pair and the set it meets */
    record Hit(BalanceFinalRule.Pair pair, BalanceFinalRule.Armor armor, double clickDamage) {
        /** the damage of one projectile on the body (before the part hit's multiplier) */
        float projectile() {
            return (float) (clickDamage / pair.projectiles());
        }
    }

    /** the stand's words for a hit, null outside the scheme */
    static String describeOf(Hit h) {
        return h == null ? null : String.format(Locale.ROOT, "%s.%s on %s, %.3f a click", h.pair().gun(), h.pair().ammo(), h.armor().kind(), h.clickDamage());
    }

    static BalanceFinalRule.Armor armorOf(Body b, State s, HurtNow now, BooleanSupplier on) {
        return armorOf(b, s, now, on, BalanceArmorBase::setOfWorn);
    }

    /** the same with the worn items' port given (core's tests) */
    static BalanceFinalRule.Armor armorOf(Body b, State s, HurtNow now, BooleanSupplier on,
            BiFunction<Body, Integer, String> worn) {
        var a = armorOfWorn(b, s, on, worn);
        if (a == null || !s.acid().on()) return a;
        // the acid's corrosion takes the set's tier down (balance.final.acid; Fable №2, 2.4)
        float k = now.corrosion(b);
        if (!(k > 0F)) return a;
        double t = s.acid().tierOf(a.tier(), k);
        return t == a.tier() ? a : new BalanceFinalRule.Armor(a.kind(), t, a.hours(), a.en(), a.pool());
    }

    static float clickOn(Body b, String id, BooleanSupplier on) {
        return clickOn(b, id, state(), EuphImmunityBase.NowPort.NOW, on, BalanceArmorBase::setOfWorn);
    }

    static float clickOn(Body b, String id, State s, HurtNow now, BooleanSupplier on,
            BiFunction<Body, Integer, String> worn) {
        var pr = s.pairs().get(id);
        var armor = pr == null ? null : armorOf(b, s, now, on, worn);
        if (armor == null) return Float.NaN;
        double click = BalanceFinalRule.clickDamage(pr, armor, s.sets().get("fau"), s.refs(), s.params());
        return (float) (click / pr.projectiles()) * b.maxHealth() / (float) BalanceFinalRule.HP;
    }

    /**
     * the set the scheme takes on {@code b} as the slots have it (BalanceFinal.armorOf before the acid): a full set of
     * one kind its own (with its pool), a mixed or incomplete one by the slots' weights (no pool), nothing of the table:
     * bare (players only); null when not the scheme's. {@code balanceOn}: the balance's feature is on (the version's),
     * asked where the old code asked it, only for a player's full set of a kit's
     */
    static BalanceFinalRule.Armor armorOfWorn(acup.gunfeel.host.Body b, State s, java.util.function.BooleanSupplier balanceOn) {
        return armorOfWorn(b, s, balanceOn, BalanceArmorBase::setOfWorn);
    }

    static BalanceFinalRule.Armor armorOfWorn(Body b, State s, BooleanSupplier balanceOn,
            BiFunction<Body, Integer, String> worn) {
        BalanceFinalRule.Armor[] slots = new BalanceFinalRule.Armor[4];
        String first = null;
        boolean same = true, any = false;
        for (int i = 0; i < 4; i++) {
            String set = worn.apply(b, i);
            var a = set == null ? null : s.sets().get(set);
            slots[i] = a;
            if (a != null) any = true;
            if (i == 0) first = set;
            if (a == null || set == null || !set.equals(first)) same = false;
        }
        if (!any) return b.player() ? BalanceFinalRule.MODEL_SETS.get("none") : null;
        if (same) return slots[0].pool() > 0 && !pooledOf(b, first, balanceOn) ? new BalanceFinalRule.Armor(slots[0].kind(), slots[0].tier(), slots[0].hours(), slots[0].en(), 0) : slots[0];
        return BalanceFinalRule.mix(slots, s.weights(), s.empty());
    }

    /** the full set's pool is there: a player's, its kit in the mode pool (balance.immune*.mode) with the balance on */
    static boolean pooledOf(acup.gunfeel.host.Body b, String set, java.util.function.BooleanSupplier balanceOn) {
        if (!b.player()) return false;
        for (var kit : EuphImmunityBase.Kit.values()) if (kit.set.equals(set)) return balanceOn.getAsBoolean() && kit.mode().equals("pool");
        return false;
    }

    // ---------------------------------------------------------------- the shot's pair

    private static final Map<Object, String> PAIR = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final Map<Object, Float> SHARE = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private record Last(String pair, long tick) {}
    private static final Map<Object, Last> LAST = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final Map<Object, Boolean> IR_SUBS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static final class ShotsPort {
        static final FinalShots READS = CoreServices.load(FinalShots.class, FinalShots.MISSING);
    }

    static float hitAs(Object projectile, Body shooter, Object from, Object as,
            float share, FinalShots reads, BooleanSupplier enabled) {
        if (projectile == null || shooter == null || reads.client(shooter) || !reads.gunsOn()) return 1F;
        Object gunItem = reads.gunOf(shooter);
        if (gunItem == null) return 1F;
        try {
            String gun = reads.gunName(gunItem);
            String id = gun + "." + reads.ammoName(as);
            if (enabled.getAsBoolean() && gun != null && state().pairs().containsKey(id)) {
                PAIR.put(projectile, id);
                SHARE.put(projectile, share);
            }
            return share * reads.as(gunItem, from, as);
        } catch (RuntimeException | LinkageError e) {
            // a hit must never fail over the tables: the shell's own damage
            return 1F;
        }
    }

    static float shareOf(Object projectile, Body shooter, Object from,
            Object as, float share, FinalShots reads, BooleanSupplier enabled) {
        if (projectile == null || shooter == null || reads.client(shooter) || !reads.gunsOn()) return share;
        Object gunItem = reads.gunOf(shooter);
        if (gunItem == null) return share;
        // hitAs's own, but a table failing leaves the share (hitAs would give the whole damage: two bullets of a full SP)
        try {
            String gun = reads.gunName(gunItem);
            String id = gun + "." + reads.ammoName(as);
            if (enabled.getAsBoolean() && gun != null && state().pairs().containsKey(id)) {
                PAIR.put(projectile, id);
                SHARE.put(projectile, share);
            }
            return share * reads.as(gunItem, from, as);
        } catch (RuntimeException | LinkageError e) {
            return share;
        }
    }

    static float share(Object source, FinalShots reads) {
        Float k = SHARE.get(reads.direct(source));
        return k == null ? 1F : k;
    }

    static void fired(Object projectile, Body shooter, Object gunItem, Object ammo, FinalShots reads, BooleanSupplier enabled) {
        if (projectile == null || shooter == null || reads.client(shooter) || !enabled.getAsBoolean()) return;
        try {
            fired0(projectile, shooter, gunItem, ammo, reads);
        } catch (RuntimeException | LinkageError e) {
            // the shot goes on without a pair: NTM's machine takes it
        }
    }

    static void fired0(Object projectile, Body shooter, Object gunItem, Object ammo, FinalShots reads) {
        String gun = reads.gunName(gunItem);
        if (gun == null || ammo == null) return;
        String id = gun + "." + reads.ammoName(ammo);
        if (!state().pairs().containsKey(id)) {
            String loaded = reads.loadedAmmo(shooter, gunItem);
            if (loaded != null && state().pairs().containsKey(gun + "." + loaded)) id = gun + "." + loaded;
        }
        PAIR.put(projectile, id);
        LAST.put(reads.raw(shooter), new Last(id, reads.time(reads.raw(shooter))));
        reads.countShot(shooter);
        if (reads.logOn()) reads.logShot(projectile, shooter, gunItem);
    }

    static void submunition(Object projectile, Body thrower, FinalShots reads, BooleanSupplier enabled) {
        if (projectile == null || thrower == null || reads.client(thrower) || !enabled.getAsBoolean() || PAIR.containsKey(projectile)) return;
        Last l = LAST.get(reads.raw(thrower));
        if (l != null && reads.time(reads.raw(thrower)) - l.tick() <= 2) PAIR.put(projectile, l.pair());
    }

    static void beamSub(Object beam, Body thrower, Object config, FinalShots reads, BooleanSupplier enabled) {
        if (beam == null || thrower == null || reads.client(thrower) || !enabled.getAsBoolean()) return;
        try {
            if (!reads.irConfig(config)) return;
        } catch (RuntimeException | LinkageError e) {
            return;
        }
        Last l = LAST.get(reads.raw(thrower));
        if (l == null || reads.time(reads.raw(thrower)) - l.tick() > 2) return;
        PAIR.put(beam, l.pair());
        IR_SUBS.put(beam, Boolean.TRUE);
    }

    static String blastPair(Object source, FinalShots reads) {
        Object shooter = reads.entity(source);
        Last l = shooter == null ? null : LAST.get(shooter);
        if (l == null || reads.time(shooter) - l.tick() > 5) return null;
        // a beam's pair alone: an NTM blast after a bullet's shot (a fire's, a launcher's) is not the pair's
        var pr = state().pairs().get(l.pair());
        return pr != null && !pr.cat().equals("PHYS") ? l.pair() : null;
    }

    static boolean irSub(Object source, FinalShots reads) {
        return reads.direct(source) != null && IR_SUBS.containsKey(reads.direct(source));
    }

    static String pairOf(Object projectile) {
        return projectile == null ? null : PAIR.get(projectile);
    }

    static Hit hit(Body target, Object source, FinalShots reads, BooleanSupplier enabled, HurtNow now) {
        if (reads.client(target) || !enabled.getAsBoolean()) return null;
        String cat = reads.category(source);
        if (cat == null) return null;
        State s = state();
        Object projectile;
        if (BalanceArmorBase.CAT_EXPLOSION.equals(cat)) {
            if (!reads.bulletBlast() || reads.struckNow() != reads.raw(target)) return null;
            projectile = reads.bulletNow();
        } else projectile = reads.direct(source);
        String id = pairOf(projectile);
        if (id == null) id = reads.vnt() && !BalanceArmorBase.CAT_EXPLOSION.equals(cat) ? blastPair(source, reads) : null;
        if (id == null) return null;
        var pr = s.pairs().get(id);
        if (pr == null) return null;
        var armor = armorOf(target, s, now, reads::balanceOn, reads::worn);
        if (armor == null) return null;
        BalanceFinalRule.Armor fau = s.sets().get("fau");
        double click = s.cache().computeIfAbsent(new CacheKey(id, armor), k -> BalanceFinalRule.clickDamage(pr, armor, fau, s.refs(), s.params()));
        return new Hit(pr, armor, click);
    }

    static float damage(Body target, Object source, FinalShots reads, BooleanSupplier enabled, HurtNow now) {
        float b = blastOrFrag(target, source, reads, enabled, now);
        if (!Float.isNaN(b)) return b * target.maxHealth() / (float) BalanceFinalRule.HP;
        Hit h = hit(target, source, reads, enabled, now);
        if (h == null) return Float.NaN;
        if (againThisTick(target, source, reads)) return 0F;
        if (reads.logOn()) reads.logHit(target, source, h.pair().id(), h.pair().tier(), now.onHead(target));
        float scale = target.maxHealth() / (float) BalanceFinalRule.HP;
        return h.projectile() * scale * range(target, source, h, reads) * share(source, reads);
    }

    static float range(Body target, Object source, Hit h, FinalShots reads) {
        var r = state().range();
        String role = BalanceFinalRule.roleOf(h.pair(), state().params());
        boolean own = r.lineOf().containsKey(h.pair().gun());
        // an explosive shell's direct hit takes the shell's line, or its role's (Fable №2, 2.1; №3); other blasts none
        boolean shell = !own && BalanceArmorBase.CAT_EXPLOSION.equals(reads.category(source)) && h.pair().explosive() && (role.equals("shotgun") || role.equals("slug"));
        // the IR arc's beam: no range, like the arc (Fable's answer 6, 5)
        // the shell's direct hit comes in NTM's blast (vntBlastNow): the shell's line all the same; so does a drop's blast of a gun's own line
        if (!r.on() || (reads.vnt() || BalanceArmorBase.CAT_EXPLOSION.equals(reads.category(source))) && !shell && !(own && BalanceArmorBase.CAT_EXPLOSION.equals(reads.category(source))) || irSub(source, reads) || reads.entity(source) == null) return 1F;
        double d = reads.distance(target, source);
        // the gun's own line; the shell's (Fable №3), or its role's when the rule has none (X942)
        return (float) BalanceRangeRule.hit(r, h.pair().gun(), role, shell, d);
    }

    static float drain(Body target, Object source, FinalShots reads, BooleanSupplier enabled, HurtNow now) {
        float b = blastOrFrag(target, source, reads, enabled, now);
        if (!Float.isNaN(b)) return b;
        Hit h = hit(target, source, reads, enabled, now);
        if (h == null) return Float.NaN;
        if (againThisTick(target, source, reads)) return 0F;
        boolean head = now.onHead(target);
        if (reads.logOn()) reads.logHit(target, source, h.pair().id(), h.pair().tier(), head);
        float d = h.projectile() * range(target, source, h, reads) * share(source, reads);
        return head ? Math.min(d * now.headMultiplier(), reads.headCap() * (float) BalanceFinalRule.HP) : d;
    }

    static boolean ntmShot(Body target, Object source, FinalShots reads, BooleanSupplier enabled) {
        if (!enabled.getAsBoolean()) return false;
        String cat = reads.category(source);
        if (cat == null || BalanceArmorBase.CAT_EXPLOSION.equals(cat)) return false;
        // a player's shot of no pair (a launcher's submunition, another game's gun) keeps X908's machine
        if (!reads.mobShooter(source)) return false;
        String id = pairOf(reads.direct(source));
        if (id == null) id = reads.vnt() && !BalanceArmorBase.CAT_EXPLOSION.equals(cat) ? blastPair(source, reads) : null;
        if (id == null) return true;
        State s = state();
        // a GL-54 fragment grenade's (BalanceFinal.frag): the scheme's, like a pair's
        if (s.blast().on() && id.startsWith("gl54.") && s.blast().frags().containsKey(id.substring(5))) return false;
        return !s.pairs().containsKey(id);
    }

    static boolean againThisTick(Body target, Object source, FinalShots reads) {
        boolean irSub = irSub(source, reads);
        // the Tesla Cannon's arc (a beam's class, not an explosion): an explosive shell's pellets each have their own blast
        if (!(reads.vnt() || irSub) || reads.explosion(source)
                || BalanceArmorBase.CAT_EXPLOSION.equals(reads.category(source))) return false;
        long now = reads.time(reads.raw(target));
        BlastHit was = BLAST_HITS.get(reads.raw(target));
        int id = System.identityHashCode(source);
        int shooter = reads.entity(source) == null ? Integer.MIN_VALUE : reads.entityId(reads.entity(source));
        if (was != null && was.tick() == now && was.shooter() == shooter && was.source() != id) return true;
        BLAST_HITS.put(reads.raw(target), new BlastHit(id, shooter, now));
        return false;
    }

    private record BlastHit(int source, int shooter, long tick) {}
    private static final Map<Object, BlastHit> BLAST_HITS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** a blast of the ladder's under way on one target: what one hit of it takes (of 20 HP and the pool), its pair */
    private record BlastNow(Object target, double take, String id, double tier, Object projectile) {
    }

    private static final ThreadLocal<BlastNow> BLAST_NOW = new ThreadLocal<>();

    /** a hand grenade going off (ItemGrenadeFilling.explode): its filling's name */
    private static final ThreadLocal<String> GRENADE = new ThreadLocal<>();

    /** a hand grenade goes off: its blasts are dealt with its filling known */
    public static void grenade(Object grenade, Runnable explode, FinalShots reads, BooleanSupplier enabled) {
        if (reads.rawClient(grenade) || !enabled.getAsBoolean()) {
            explode.run();
            return;
        }
        String was = GRENADE.get(), filling;
        try {
            filling = reads.grenadeFilling(grenade);
        } catch (RuntimeException e) {
            filling = null;
        }
        if (filling != null) GRENADE.set(filling);
        else GRENADE.remove();
        try {
            explode.run();
        } finally {
            if (was != null) GRENADE.set(was);
            else GRENADE.remove();
        }
    }

    /** Runs a blast hit with the scheme context. */
    public static boolean blastAt(Object explosion, Object blast, Body target, boolean struck,
            boolean explosive, java.util.function.Supplier<Boolean> hurt, FinalShots reads, BooleanSupplier enabled, HurtNow now) {
        if (!explosive || explosion == null || target == null || reads.client(target) || !enabled.getAsBoolean()) return hurt.get();
        BlastNow b;
        try {
            b = blastOf(explosion, blast, target, struck, reads, enabled, now);
        } catch (RuntimeException e) {
            b = null;
        }
        if (b == null) return hurt.get();
        BlastNow was = BLAST_NOW.get();
        BLAST_NOW.set(b);
        try {
            return hurt.get();
        } finally {
            if (was != null) BLAST_NOW.set(was);
            else BLAST_NOW.remove();
        }
    }

    /** the blast's hurt under way on {@code target} is the ladder's (no part's multiplier on it, NTM's early check passed) */
    public static boolean blastNow(Body target, FinalShots reads) {
        BlastNow b = BLAST_NOW.get();
        return b != null && b.target() == reads.raw(target);
    }

    /** a hit the scheme takes, a blast's or a GL-54 fragment's among them (BalanceArmor.covers) */
    static boolean takes(Body target, Object source, FinalShots reads, BooleanSupplier enabled, HurtNow now) {
        return hit(target, source, reads, enabled, now) != null || blastNow(target, reads) && expl(source, reads) || !Float.isNaN(frag(target, source, false, reads, enabled, now));
    }

    private static boolean expl(Object source, FinalShots reads) {
        return BalanceArmorBase.CAT_EXPLOSION.equals(reads.category(source));
    }

    private static BlastNow blastOf(Object explosion, Object blast, Body target, boolean struck, FinalShots reads, BooleanSupplier enabled, HurtNow now) {
        State s = state();
        var r = s.blast();
        // balanceArmorBlasts off: the blast is NTM's (BalanceArmor.category), so not the ladder's either
        if (!r.on() || !ReaimTimes.flag("balanceArmorBlasts", true)) return null;
        var armor = armorOf(target, s, now, reads::balanceOn, reads::worn);
        if (armor == null) return null;
        String id;
        double tier, near;
        Object owner, projectile;
        if (blast != null) {
            // a launcher's round (its gun in balance.final.blast.launchers): its pair by the shot
            var bullet = reads.boundBullet(blast);
            id = pairOf(bullet);
            int dot = id == null ? -1 : id.indexOf('.');
            if (dot <= 0) return null;
            var l = r.launchers().get(id.substring(0, dot));
            Double round = r.rounds().get(id.substring(dot + 1));
            if (l == null || round == null) return null;
            tier = BalanceBlastRule.pairTier(l, round, r.cap());
            double t = tier;
            near = s.cache().computeIfAbsent(new CacheKey("blast " + id, armor), k -> (double) BalanceBlastRule.nearLauncher(l, BalanceBlastRule.target(t, armor, s.params(), r)));
            owner = reads.projectileOwner(bullet);
            projectile = bullet;
        } else {
            // a hand grenade (its filling in balance.final.blast.grenades), not inside a shot's impact
            String filling = GRENADE.get();
            Double t = filling == null || reads.currentImpact() != null ? null : r.grenades().get(filling);
            if (t == null) return null;
            id = "grenade." + filling;
            tier = t;
            near = s.cache().computeIfAbsent(new CacheKey("blast " + id, armor), k -> BalanceBlastRule.nearGrenade(BalanceBlastRule.target(t, armor, s.params(), r), s.params(), r));
            owner = reads.exploder(explosion);
            // a mob's or a machine's grenade: NTM's numbers, as their shots
            if (owner != null && !reads.playerEntity(owner)) return null;
            projectile = null;
        }
        // where the target is: struck, or its box's distance from the blast against NTM's reach (twice the size)
        double where = BalanceBlastRule.where(struck, reads.boxDistance(target, explosion), 2.0 * reads.blastSize(explosion), r.hits());
        double share = BalanceBlastRule.share(near, where);
        // one's own blast: half
        if (owner == reads.raw(target)) share *= 0.5;
        return new BlastNow(reads.raw(target), share * (BalanceFinalRule.HP + armor.pool()), id, tier, projectile);
    }

    /** Removes the mob's own base armor and FBI modifier from scheme hits. */
    public static float[] mobOwnArmor(Body target, Object source, FinalShots reads, BooleanSupplier enabled, HurtNow now) {
        if (target.player() || reads.client(target) || reads.category(source) == null || !enabled.getAsBoolean()
                || !ReaimTimes.text("balance.final.mobArmor", "player").trim().equalsIgnoreCase("player"))
            return NO_OWN;
        try {
            if (!takes(target, source, reads, enabled, now)) return NO_OWN;
        } catch (RuntimeException e) {
            return NO_OWN;
        }
        return new float[] {own(target, 0, reads),
                own(target, 1, reads)};
    }

    private static final float[] NO_OWN = {0F, 0F};

    /** the mob's own share of an attribute: its base and balance.fbiArmor's modifier */
    private static float own(Body target, int attribute, FinalShots reads) {
        var a = reads.ownAttribute(target, attribute);
        if (a == null) return 0F;
        var fbi = reads.ownModifier(a);
        return (float) Math.max(0, reads.attributeBase(a) + (fbi == null ? 0 : reads.modifierAmount(fbi)));
    }

    /** a blast's or a GL-54 fragment's take of 20 HP and the pool, or NaN when the hit is neither (the log counts it) */
    private static float blastOrFrag(Body target, Object source, FinalShots reads, BooleanSupplier enabled, HurtNow now) {
        BlastNow b = BLAST_NOW.get();
        if (b != null && b.target() == reads.raw(target) && expl(source, reads)) {
            if (reads.logOn()) reads.logBlastHit(target, source, b.projectile(), b.id(), b.tier(), false);
            return (float) b.take();
        }
        return frag(target, source, true, reads, enabled, now);
    }

    /** One fragment's share of a full cone burst, without a head multiplier. */
    private static float frag(Body target, Object source, boolean log, FinalShots reads, BooleanSupplier enabled, HurtNow now) {
        if (reads.client(target) || !enabled.getAsBoolean()) return Float.NaN;
        String cat = reads.category(source);
        if (cat == null || BalanceArmorBase.CAT_EXPLOSION.equals(cat)) return Float.NaN;
        Object projectile = reads.direct(source);
        String id = pairOf(projectile);
        if (id == null || !id.startsWith("gl54.")) return Float.NaN;
        State s = state();
        var r = s.blast();
        String kind = id.substring(5);
        var f = r.frags().get(kind);
        var l = r.launchers().get("gl54");
        Double round = r.rounds().get(kind);
        var spec = acup.gunfeel.gl54.frag.FragSpec.of(acup.gunfeel.gl54.GrenadeKind.of(kind));
        if (!r.on() || f == null || l == null || round == null || spec == null) return Float.NaN;
        var armor = armorOf(target, s, now, reads::balanceOn, reads::worn);
        if (armor == null) return Float.NaN;
        double tier = BalanceBlastRule.pairTier(l, round, r.cap());
        double role = s.params().roles().getOrDefault("airburst", 1.2);
        double click = s.cache().computeIfAbsent(new CacheKey("frag " + id, armor), k -> BalanceBlastRule.fragClick(l, f, tier, armor, s.params(), role));
        if (log && reads.logOn()) reads.logBlastHit(target, source, projectile, id, tier, false);
        // the click is a burst whose cone all hits (the ring's fragments are the side spray, each the same)
        return (float) (click / Math.max(1, spec.cone()));
    }

}
