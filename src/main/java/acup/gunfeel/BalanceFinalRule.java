package acup.gunfeel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The balance's final scheme without the game (BalanceFinal applies it): how many hit clicks a pair needs on a set, as
 * the model has it ({@code notes/balance/final/model/scheme_final.py}: {@code T_d}, {@code eff_d}, {@code body_target},
 * {@code real_ttk}, {@code cell}).
 *
 * <p>One formula for every bullet and beam of a pair of the table: {@code d} = the pair's tier (with its ammo's and
 * the pedestal's shifts) − the set's; {@code d} gives the TTK the pair is to kill in ({@link Params#anchors}); the
 * pair's raw DPS shades it ({@link Params#beta}) and so does its role (an SMG from the hip slower, a shotgun close up
 * quicker: {@link Params#roles}); the clicks are then counted so that the real TTK (its pause, its
 * share of hits P, its reloads) comes closest to it. A click whose projectiles all hit does {@code 20 / N} HP. NTM's
 * damage and the set's threshold and resistance are not in it.
 */
public final class BalanceFinalRule {
    private BalanceFinalRule() {
    }

    /** the health the scheme counts in (a player's) */
    public static final double HP = 20.0;

    /** a pair's numbers (a line of ntmgunfeel-balance-final-pairs.tsv); {@code mag} below 0: no magazine */
    public record Pair(String gun, String ammo, double tier, boolean ped, double p, int delay, boolean auto, int mag, int reload, double pellets,
            int roundsPerClick, int hands, double pen, String cat, double rawDamage, boolean explosive, String role) {
        public String id() {
            return gun + "." + ammo;
        }

        /** projectiles of one click: pellets of a round, rounds of a click, both guns of akimbo */
        public double projectiles() {
            return Math.max(1.0, pellets) * Math.max(1, roundsPerClick) * Math.max(1, hands);
        }

        public boolean phys() {
            return "PHYS".equals(cat);
        }

        Pair withTier(double t) {
            return new Pair(gun, ammo, t, ped, p, delay, auto, mag, reload, pellets, roundsPerClick, hands, pen, cat, rawDamage, explosive, role);
        }
    }

    /**
     * a set as the scheme takes it: its tier, its tier by the hours (the head's rule), its shift for beams (EN: −1 for
     * a set NTM gives no energy resistance), the pool of a full set and its kind (dns, fau, trench, mix, or the rest)
     */
    public record Armor(String kind, double tier, double hours, double en, double pool) {
    }

    /**
     * the magazine's rule (balance.final.mag; Fable 2026-11-12, 2.4): a kill of the usual fight that falls on the last
     * {@code spare} clicks of a magazine, or on the first click after a reload, is moved to click M − spare (of the
     * magazine before), the damage of a click raised for it; spare = {@code small} for a magazine below 10 clicks, else
     * {@code reserve} of it (at least 1). Not when the TTK after it is shorter than {@code min} of the target, nor for a
     * magazine of fewer than 3 clicks. {@code reserve} 0: off (X912)
     */
    public record MagRule(double reserve, int small, double min) {
        public static final MagRule OFF = new MagRule(0, 1, 0.6);
        public static final MagRule DEFAULT = new MagRule(0.15, 1, 0.6);

        public boolean on() {
            return reserve > 0;
        }

        /** the clicks kept in a magazine of {@code mag} clicks (Python's round, half to even, as the model) */
        double spare(double mag) {
            return mag < 10 ? small : Math.max(1, (int) Math.rint(reserve * mag));
        }

        /** "reserve=0.15 small=1 min=0.6"; a broken entry is left out */
        public static MagRule parse(String text, MagRule def) {
            double reserve = def.reserve(), min = def.min();
            int small = def.small();
            if (text != null) for (String e : text.trim().split("\\s+")) {
                int c = e.indexOf('=');
                if (c <= 0) continue;
                try {
                    double x = Double.parseDouble(e.substring(c + 1));
                    if (!Double.isFinite(x) || x < 0) continue;
                    switch (e.substring(0, c)) {
                        case "reserve" -> reserve = Math.min(x, 0.9);
                        case "small" -> small = (int) x;
                        case "min" -> min = x;
                        default -> {
                        }
                    }
                } catch (NumberFormatException ignored) {
                    // left out
                }
            }
            return new MagRule(reserve, small, min);
        }
    }

    /** the scheme's numbers (the server's keys balance.final.*) */
    public record Params(TreeMap<Integer, Double> anchors, double tmin, double tmax, double below, double beta, double lo, double hi, double energy,
            double ammoK, double ammoFrom, double ammoMax, double jhp, double ped, double explShift, double dnsOverFau, Set<String> headGuns,
            double headGap, double laserTrench, Map<String, Double> roles, double slugPellets, MagRule mag) {
        /** Fable 2026-11-12 (10.2, ladder H): up x0.77 a tier to the floor 1.25 s, down the hours' ladder */
        public static final String TTK = "+4:1.25 +3:1.6 +2:2.1 +1:2.7 0:3.5 -1:7 -2:13.5 -3:28 -4:40 -5:50 -6:60 -7:68 -8:75 -9:82";
        public static final String ROLES = "smg:1.6 mg:1.3 rifle:1.0 pistol:1.0 shotgun:0.75 slug:1.0 sniper:0.85 beam:1.0";
        /** X912's: the keys' values that give it back */
        public static final String TTK_X912 = "+3:1.63 +2:2.08 +1:2.68 0:3.5 -1:7.0 -2:18 -3:105 -4:210";
        public static final String ROLES_X912 = "smg:1.6 mg:1.3 rifle:1.0 pistol:1.2 shotgun:0.75 sniper:0.85 beam:1.0";
        public static final Params DEFAULT = new Params(BalanceFinalRule.anchors(TTK), 0.7, 900, 1.1, 0.3, 0.7, 1.4, 1.15, 4.0, 0.15, 1.0, -1.0, 0.5, 1.0, 1.15,
                Set.of(), 1.0, 1.0, BalanceFinalRule.roles(ROLES), 4, MagRule.OFF);
        public static final Params X912 = new Params(BalanceFinalRule.anchors(TTK_X912), 0.7, 900, 2.0, 0.3, 0.7, 1.4, 1.15, 4.0, 0.15, 1.0, -1.0, 0.5, 1.0, 1.15,
                Set.of(), 1.0, 1.0, BalanceFinalRule.roles(ROLES_X912), 0, MagRule.OFF);

        /** the role's share of the usual TTK (1 for a role not given) */
        double role(String r) {
            Double v = roles.get(r);
            return v == null ? 1.0 : v;
        }

        public Params withHeadGuns(Set<String> guns) {
            return new Params(anchors, tmin, tmax, below, beta, lo, hi, energy, ammoK, ammoFrom, ammoMax, jhp, ped, explShift, dnsOverFau, guns, headGap,
                    laserTrench, roles, slugPellets, mag);
        }

        public Params withMag(MagRule m) {
            return new Params(anchors, tmin, tmax, below, beta, lo, hi, energy, ammoK, ammoFrom, ammoMax, jhp, ped, explShift, dnsOverFau, headGuns, headGap,
                    laserTrench, roles, slugPellets, m);
        }
    }

    /**
     * the pair's role as the scheme takes it: a shotgun's shot of fewer than {@code slugPellets} projectiles a round (a
     * slug, an explosive shell) is a {@code slug}, its time a rifle's (Fable 2.4); 0: no such role (X912)
     */
    public static String roleOf(Pair pr, Params p) {
        return "shotgun".equals(pr.role()) && pr.pellets() < p.slugPellets() ? "slug" : pr.role();
    }

    /** "role:share" by spaces (share above 0); a broken entry is left out */
    public static Map<String, Double> roles(String text) {
        Map<String, Double> out = new HashMap<>();
        if (text != null) for (String e : text.trim().split("\\s+")) {
            int c = e.indexOf(':');
            if (c <= 0) continue;
            try {
                double v = Double.parseDouble(e.substring(c + 1));
                if (Double.isFinite(v) && v > 0) out.put(e.substring(0, c), v);
            } catch (NumberFormatException ignored) {
                // left out
            }
        }
        return out;
    }

    /** "d:seconds" by spaces (d an integer, seconds above 0); a broken entry is left out, none left: the default */
    public static TreeMap<Integer, Double> anchors(String text) {
        TreeMap<Integer, Double> out = new TreeMap<>();
        if (text != null) for (String e : text.trim().split("\\s+")) {
            int c = e.indexOf(':');
            if (c <= 0) continue;
            try {
                int d = Integer.parseInt(e.substring(0, c).replace("+", ""));
                double s = Double.parseDouble(e.substring(c + 1));
                if (Double.isFinite(s) && s > 0) out.put(d, s);
            } catch (NumberFormatException ignored) {
                // left out
            }
        }
        return out.isEmpty() && !TTK_SAFE.isEmpty() ? new TreeMap<>(TTK_SAFE) : out;
    }

    private static final TreeMap<Integer, Double> TTK_SAFE = new TreeMap<>(Map.ofEntries(Map.entry(4, 1.25), Map.entry(3, 1.6), Map.entry(2, 2.1), Map.entry(1, 2.7),
            Map.entry(0, 3.5), Map.entry(-1, 7.0), Map.entry(-2, 13.5), Map.entry(-3, 28.0), Map.entry(-4, 40.0), Map.entry(-5, 50.0), Map.entry(-6, 60.0),
            Map.entry(-7, 68.0), Map.entry(-8, 75.0), Map.entry(-9, 82.0)));

    /** the TTK the anchors give {@code d} (by the logarithm between two anchors; below the lowest ×below a step) */
    public static double ttkOf(double d, Params p) {
        var a = p.anchors();
        int hi = a.lastKey(), lo = a.firstKey();
        if (d >= hi) return Math.max(p.tmin(), a.get(hi));
        if (d <= lo) return Math.min(p.tmax(), a.get(lo) * Math.pow(p.below(), lo - d));
        var f = a.floorEntry((int) Math.floor(d));
        var c = a.ceilingEntry((int) Math.floor(d) + 1);
        double s = (d - f.getKey()) / (c.getKey() - f.getKey());
        return Math.max(p.tmin(), Math.exp(Math.log(f.getValue()) * (1 - s) + Math.log(c.getValue()) * s));
    }

    /** the ammo's shift of {@code d} on a set of {@code tier}: piercing rounds up, hollow points down (bullets only) */
    public static double ammoShift(Pair pr, double tier, Params p) {
        if (!pr.phys()) return 0.0;
        double k = Math.min(1.0, tier / 3.0);
        if (pr.pen() < 0) return p.jhp() * k;
        if (pr.pen() < p.ammoFrom()) return 0.0;
        return Math.min(p.ammoMax(), p.ammoK() * pr.pen()) * k;
    }

    static int intervalTicks(Pair pr) {
        return Math.max(pr.auto() ? 1 : 4, pr.delay());
    }

    static double rawDps(Pair pr) {
        return pr.rawDamage() * pr.pellets() * pr.roundsPerClick() * pr.hands() / (intervalTicks(pr) / 20.0);
    }

    /** the pairs' raw DPS by tier (bullets only; a tier of fewer than 6 pairs takes all pairs' median) */
    public record Refs(Map<Double, Double> byTier, double all) {
        double of(double tier) {
            return byTier.getOrDefault(tier, all);
        }
    }

    public static Refs refs(Collection<Pair> pairs) {
        Map<Double, List<Double>> by = new HashMap<>();
        List<Double> all = new ArrayList<>();
        for (Pair pr : pairs) {
            if (!pr.phys()) continue;
            by.computeIfAbsent(pr.tier(), t -> new ArrayList<>()).add(rawDps(pr));
            all.add(rawDps(pr));
        }
        double allm = all.isEmpty() ? 1.0 : median(all);
        Map<Double, Double> out = new HashMap<>();
        by.forEach((t, v) -> out.put(t, v.size() >= 6 ? median(v) : allm));
        return new Refs(out, allm);
    }

    static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        s.sort(null);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    /** the pair's character: (its tier's DPS / its own)^beta within lo..hi (bullets only) */
    public static double character(Pair pr, Refs refs, Params p) {
        if (!pr.phys()) return 1.0;
        double f = Math.pow(refs.of(pr.tier()) / Math.max(rawDps(pr), 1e-9), p.beta());
        return Math.max(p.lo(), Math.min(p.hi(), f));
    }

    /** d of the pair on the set */
    public static double d(Pair pr, Armor a, Params p) {
        double t = a.tier();
        if ("trench".equals(a.kind()) && "LASER".equals(pr.cat())) t += p.laserTrench();
        if (!pr.phys()) t += a.en();
        double d = pr.tier() - t;
        if (pr.ped()) d += p.ped();
        if (pr.explosive()) d += p.explShift();
        return d + ammoShift(pr, t, p);
    }

    /** the TTK the pair is to kill the set in, its pool too, s; DNS never below dnsOverFau of FAU's */
    public static double target(Pair pr, Armor a, Armor fau, Refs refs, Params p) {
        double c = pr.phys() ? 1.0 : p.energy();
        double t = Math.max(p.tmin(), ttkOf(d(pr, a, p), p) * c * character(pr, refs, p) * p.role(roleOf(pr, p)));
        double tt = t * (HP + a.pool()) / HP;
        if ("dns".equals(a.kind()) && fau != null) tt = Math.max(tt, p.dnsOverFau() * target(pr, fau, null, refs, p));
        return tt;
    }

    /** the clicks fired to take {@code tot} HP off with {@code dmg} a hit click, and the real TTK of them, s */
    public static double[] realTtk(double dmg, Pair pr, double tot) {
        double pe = Math.max(pr.p(), 0.05), dt = intervalTicks(pr) / 20.0;
        int n = Math.max(1, (int) Math.ceil(tot / (dmg * pe) - 1e-9));
        double mag = pr.mag() < 0 ? Double.POSITIVE_INFINITY : pr.mag() / (double) Math.max(1, pr.roundsPerClick());
        int rl = Double.isInfinite(mag) ? 0 : (int) Math.floor((n - 1) / mag);
        return new double[] {(n - 1) * dt + rl * pr.reload() / 20.0, n};
    }

    /** the hit clicks N (2 or more) whose real TTK comes closest to the target, the magazine's rule after it */
    public static int hitClicks(Pair pr, Armor a, Armor fau, Refs refs, Params p) {
        double tt = target(pr, a, fau, refs, p), tot = HP + a.pool();
        int nh = closest(pr, tot, tt);
        return p.mag().on() ? magRule(pr, tot, tt, nh, p) : nh;
    }

    private static int closest(Pair pr, double tot, double tt) {
        int lo = 2, hi = 6000;
        if (realTtk(HP / lo, pr, tot)[0] >= tt) return 2;
        while (lo + 1 < hi) {
            int mid = (lo + hi) / 2;
            if (realTtk(HP / mid, pr, tot)[0] < tt) lo = mid;
            else hi = mid;
        }
        return Math.abs(realTtk(HP / lo, pr, tot)[0] - tt) <= Math.abs(realTtk(HP / hi, pr, tot)[0] - tt) ? lo : hi;
    }

    /**
     * the magazine's rule ({@link MagRule}; the model's fm.py {@code _cell}): the kill on the last spare clicks of a
     * magazine, or on the first click after a reload, moved to click M − spare, fewer hit clicks for it
     */
    static int magRule(Pair pr, double tot, double tt, int nh, Params p) {
        if (pr.mag() < 0) return nh;
        double mag = pr.mag() / (double) Math.max(1, pr.roundsPerClick());
        if (mag < 3) return nh;
        int n = (int) realTtk(HP / nh, pr, tot)[1];
        if (n < 2 || nh <= 2) return nh;
        double lim = mag - p.mag().spare(mag);
        double pos = (n - 1) % mag + 1, full = Math.floor((n - 1) / mag);
        double want;
        if (pos > lim) want = full * mag + lim;
        else if (pos == 1 && n > mag) want = (full - 1) * mag + lim;
        else return nh;
        int w = (int) want;
        if (w < 2) return nh;
        int nh2 = nh;
        while (nh2 > 2 && realTtk(HP / nh2, pr, tot)[1] > w) nh2--;
        double[] r = realTtk(HP / nh2, pr, tot);
        double t = r[1] > 1 ? Math.max(r[0], p.tmin()) : r[0];
        return r[1] <= w && t >= p.mag().min() * tt ? nh2 : nh;
    }

    /**
     * the damage of a click whose projectiles all hit, HP: 20 / N; DNS (a full set) never quicker than FAU: when its
     * clicks would kill sooner, it takes FAU's number of clicks fired
     */
    public static double clickDamage(Pair pr, Armor a, Armor fau, Refs refs, Params p) {
        double dmg = HP / hitClicks(pr, a, fau, refs, p);
        if ("dns".equals(a.kind()) && fau != null) {
            double tot = HP + a.pool();
            double[] own = realTtk(dmg, pr, tot);
            double[] f = realTtk(HP / hitClicks(pr, fau, null, refs, p), pr, HP + fau.pool());
            if (floored(own, p) < floored(f, p)) dmg = tot / (f[1] * Math.max(pr.p(), 0.05)) * (1 - 1e-9);
        }
        return dmg;
    }

    /** a real TTK no quicker than the floor when it takes more than one click (the damage cap's window) */
    static double floored(double[] ttk, Params p) {
        return ttk[1] > 1 ? Math.max(ttk[0], p.tmin()) : ttk[0];
    }

    /** the head's rule: one of the head guns on a set of a tier by the hours at least headGap below the pair's */
    public static boolean headOneShot(Pair pr, double hours, Params p) {
        return p.headGuns().contains(pr.gun()) && pr.tier() - hours >= p.headGap() - 1e-9;
    }

    /** an incomplete or mixed set: tier and beam shift by the slots' weights (an empty slot or a piece not of the table: empty) */
    public static Armor mix(Armor[] slots, double[] weights, double empty) {
        double sw = 0, t = 0, h = 0, en = 0;
        for (int i = 0; i < 4; i++) {
            double w = weights[i];
            sw += w;
            Armor s = slots[i];
            t += w * (s == null ? empty : s.tier());
            h += w * (s == null ? empty : s.hours());
            en += w * (s == null ? 0 : s.en());
        }
        return new Armor("mix", t / sw, h / sw, en / sw, 0);
    }

    /** a line of the pairs' file, or null */
    public static Pair parse(String line) {
        if (line.isBlank() || line.startsWith("#")) return null;
        String[] c = line.split("\t");
        if (c.length < 16) return null;
        String role = c.length > 16 ? c[16].trim() : "rifle";
        try {
            return new Pair(c[0], c[1], Double.parseDouble(c[2]), !c[3].equals("0"), Double.parseDouble(c[4]), Integer.parseInt(c[5]), !c[6].equals("0"),
                    Integer.parseInt(c[7]), Integer.parseInt(c[8]), Double.parseDouble(c[9]), Integer.parseInt(c[10]), Integer.parseInt(c[11]),
                    Double.parseDouble(c[12]), c[13], Double.parseDouble(c[14]), !c[15].equals("0"), role);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** the pairs of the file by id (gun.ammo), {@code tiers} in place of their own (balance.final.pairTier) */
    public static Map<String, Pair> pairs(List<String> lines, Map<String, Double> tiers, List<String> bad) {
        Map<String, Pair> out = new java.util.LinkedHashMap<>();
        for (String l : lines) {
            Pair pr = parse(l);
            if (pr == null) {
                if (!l.isBlank() && !l.startsWith("#") && bad != null) bad.add(l);
                continue;
            }
            Double t = tiers.get(pr.id());
            out.put(pr.id(), t != null && Double.isFinite(t) ? pr.withTier(t) : pr);
        }
        return out;
    }

    /** the same with these pools of DNS and FAU (X912: 40 and 45) */
    public static Map<String, Armor> modelSets(double dns, double fau) {
        Map<String, Armor> out = new HashMap<>(SETS0);
        out.put("dns", new Armor("dns", 9, 8, 0, dns));
        out.put("fau", new Armor("fau", 8, 8, 0, fau));
        return Map.copyOf(out);
    }

    private static final Map<String, Armor> SETS0 = Map.ofEntries(Map.entry("none", new Armor("none", 0, 0, 0, 0)),
            Map.entry("steel", new Armor("steel", 1, 1, -1, 0)), Map.entry("taurun", new Armor("taurun", 1, 1, -1, 0)),
            Map.entry("titan", new Armor("titan", 1, 1, -1, 0)), Map.entry("diesel", new Armor("diesel", 2, 2, 0, 0)),
            Map.entry("t51", new Armor("t51", 3, 3, 0, 0)), Map.entry("ajr", new Armor("ajr", 4, 4, 0, 0)), Map.entry("hev", new Armor("hev", 4, 4, 0, 0)),
            Map.entry("sec", new Armor("sec", 2.5, 4, -1, 0)), Map.entry("steam", new Armor("steam", 2.5, 4, 0, 0)),
            Map.entry("bismuth", new Armor("bismuth", 6, 6, 0, 0)), Map.entry("bj", new Armor("bj", 6, 6, 0, 0)),
            Map.entry("ncrpa", new Armor("ncrpa", 6, 6, 0, 0)), Map.entry("star", new Armor("star", 6, 6, 0, 0)),
            Map.entry("trench", new Armor("trench", 6.5, 6, 0, 0)), Map.entry("cmb", new Armor("cmb", 7, 7, 0, 0)),
            Map.entry("rpa", new Armor("rpa", 7, 7, 0, 0)), Map.entry("schrab", new Armor("schrab", 7, 7, 0, 0)),
            Map.entry("dns", new Armor("dns", 9, 8, 0, 40)), Map.entry("fau", new Armor("fau", 8, 8, 0, 45)),
            Map.entry("euph", new Armor("euph", 9, 9, 0, 150)));

    /** the scheme's sets by the model's names: tier, tier by the hours, beam shift, pool of a full set (DNS 45, FAU 40: Fable 2.6) */
    public static final Map<String, Armor> MODEL_SETS = modelSets(45, 40);
}
