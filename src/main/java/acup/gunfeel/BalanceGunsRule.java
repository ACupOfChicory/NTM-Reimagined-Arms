package acup.gunfeel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The balance's gun table (stage 1b), without the game (BalanceGuns applies it): the lines of
 * {@code ntmgunfeel-balance-guns.tsv} and the rule for an ammo of a gun that has no line of its own.
 *
 * <p>A pair's line: {@code gun ammo mult neg pen}. The shot's damage is NTM's times {@code mult}; {@code neg} is added
 * to the ammo's armour threshold negation, {@code pen} to its armour piercing. Its extra column may give
 * {@code floor=} (the armour formula's least share b times this, on the sets of tier {@code from=}, 4 when not given, and
 * up: an armour-piercing round keeps more of itself against heavy armour, a soft one less; balanceArmorFloor) and
 * {@code under=T:mult:neg:pen} (the pair's numbers on sets below tier T; balanceGunUnder). A gun's own line (ammo {@code *}): a
 * mult for all its ammo (over its pairs; {@code -} for none) and the receiver's changes ({@code delay=}, {@code auto=},
 * {@code mag=}, {@code dmg=}).
 *
 * <p>An ammo with no line, of a gun with lines, takes a pair it may take from:
 * <ol>
 * <li>the gun's pair of the same family (piercing: AP, DU, SM, flechette, coil slugs; blast: HE, explosive, phosphorus;
 * the rest soft: SP, FMJ, JHP, express, shot, slugs), the nearest by NTM's damage of a click if more than one;</li>
 * <li>no pair of that family it may take from: NTM's (x1). A mult never goes from one family to another (the owner,
 * 2026-11-10: DU's x0.762 made the double barrel's shrapnel weak); such an ammo needs a line of its own.</li>
 * </ol>
 * It may not take from an explosive pair (HE, explosive, exp) unless it is an explosive ammo itself (the owner,
 * 2026-11-10: an explosive's mult is not for the rest; phosphorus burns, it is not one), and buckshot ({@link #SHOT} pellets and more) and a slug, a bullet or magnum shot (fewer) do not share a
 * mult (buckshot's was raised for its misses). Its neg, pen and floor go with the mult. A gun with no line at all: NTM as it is.
 */
public final class BalanceGunsRule {
    private BalanceGunsRule() {
    }

    /**
     * what a pair does to a shot; {@code floor}: the armour formula's least share b times this on the sets of tier
     * {@code from} and up (balanceArmorFloor; 1: the formula's own); {@code tier}: the pair's tier, the table's sixth
     * column (-1: none given), which balanceArmorTierFloor goes by
     */
    public record Shot(float mult, float neg, float pen, float floor, int from, int tier, List<Under> under) {
        public static final Shot NONE = new Shot(1F, 0F, 0F);

        public Shot {
            under = List.copyOf(under);
        }

        public Shot(float mult, float neg, float pen) {
            this(mult, neg, pen, 1F, FLOOR_FROM, -1);
        }

        public Shot(float mult, float neg, float pen, float floor, int from) {
            this(mult, neg, pen, floor, from, -1);
        }

        public Shot(float mult, float neg, float pen, float floor, int from, int tier) {
            this(mult, neg, pen, floor, from, tier, List.of());
        }

        public boolean none() {
            return mult == 1F && neg == 0F && pen == 0F && floor == 1F && tier < 0 && under.isEmpty();
        }

        /** the same with another mult (a gun's own line over its pairs) */
        Shot withMult(float m) {
            return new Shot(m, neg, pen, floor, from, tier, under);
        }

        /** the pair's numbers on a set of {@code setTier}: its {@link Under} for that tier, null for its own line */
        public Under on(int setTier) {
            return BalanceGunsRule.under(under, setTier);
        }
    }

    /**
     * {@code under=T:mult:neg:pen} in a pair's extra column: on a set of tier below {@code T} the pair hits with this
     * mult, neg and pen in place of its line's (balanceGunUnder; the owner, 2026-11-12: Tau made tier 6 and 7 armour
     * paper). Of several, the least {@code T} above the set's tier.
     */
    public record Under(int below, float mult, float neg, float pen) {
    }

    /**
     * the hit's changes for the pair's under= on a set of {@code setTier}: {damage share, neg added, pen added}, null
     * for none. The shot left with the line's mult (a gun's {@code *} line's, when it has one: under='s mult is the
     * pair's whole mult below T all the same). A line of mult 0 shoots nothing, an under= cannot lift it (share 1). The
     * pen is added to the hit's, which NTM holds to 1: an under= with less pen than its line on a round already at 1
     * keeps more than its own pen (no line of the table has pen and under= together).
     */
    public static float[] underShift(Shot s, int setTier) {
        Under u = s.on(setTier);
        if (u == null) return null;
        return new float[] {s.mult() > 0F ? u.mult() / s.mult() : 1F, u.neg() - s.neg(), u.pen() - s.pen()};
    }

    /** the Under of {@code list} for a set of {@code setTier} (null: none, the line's own numbers) */
    static Under under(List<Under> list, int setTier) {
        Under out = null;
        for (Under u : list) if (setTier < u.below() && (out == null || u.below() < out.below())) out = u;
        return out;
    }

    /**
     * balanceArmorTierFloor's rules, by spaces: {@code pair:set:share}, the armour formula's least share b times
     * {@code share} for a pair of tier {@code pair} or below on a set of tier {@code set} or above (a cheap gun's round
     * does not get through armour two tiers and more above it; the owner, 2026-11-11: "a man in HEV smashes three in
     * steel"). Of several rules the least share; 1 for none. It multiplies the pair's own floor. A word that is no rule
     * is skipped.
     */
    public static float tierFloor(String rules, int pairTier, int setTier) {
        if (rules == null || pairTier < 0) return 1F;
        float out = 1F;
        for (float[] r : tierRules(rules)) if (pairTier <= r[0] && setTier >= r[1]) out = Math.min(out, r[2]);
        return out;
    }

    private static String lastRules;
    private static float[][] lastParsed = new float[0][];

    /** the rules of the key's text (pair, set, share), the last text's kept */
    static synchronized float[][] tierRules(String rules) {
        if (rules.equals(lastRules)) return lastParsed;
        List<float[]> out = new ArrayList<>();
        for (String w : rules.strip().split("[\\s,;]+")) {
            String[] p = w.split(":");
            if (p.length != 3) continue;
            try {
                int pair = Integer.parseInt(p[0].strip()), set = Integer.parseInt(p[1].strip());
                float share = Float.parseFloat(p[2].strip());
                if (!(share > 0F) || !Float.isFinite(share)) continue;
                out.add(new float[] {pair, set, Math.max(LEAST_FLOOR, Math.min(1F, share))});
            } catch (NumberFormatException e) {
                // not a rule
            }
        }
        lastParsed = out.toArray(new float[0][]);
        lastRules = rules;
        return lastParsed;
    }

    /** the tier a pair's floor counts from when its line names none */
    static final int FLOOR_FROM = 4;
    /** a pair's floor: no less, no more than this */
    static final float LEAST_FLOOR = 0.05F, MOST_FLOOR = 10F;

    /** a gun's own line: its mult for every ammo (NaN: none) and its receiver's changes (null: as NTM has it) */
    public record GunLine(float mult, Integer delay, Boolean auto, Integer mag, Float dmg) {
    }

    /**
     * a gun's ammo as NTM has it: its name (the item's path less ammo_standard_ or ammo_), the damage of a click and the
     * projectiles of a shot (the mean of NTM's least and most)
     */
    public record Ammo(String name, float clickDamage, float pellets) {
    }

    /** buckshot: a shot of this many projectiles or more (g12 8, g10 10, stone_shot 6; magnum 4 is not) */
    static final float SHOT = 6F;

    /** what an ammo gets and where it came from: "table", "gun", "family &lt;ammo&gt;" or "none" */
    public record Resolved(Shot shot, String from) {
    }

    public record Table(Map<String, Map<String, Shot>> pairs, Map<String, GunLine> guns) {
        public static final Table EMPTY = new Table(Map.of(), Map.of());
    }

    // the most a pedestal gun gets (the coordinator's limits for the balance's secret guns)
    static final float PEDESTAL_MULT = 2F, MOST_NEG = 50F, MOST_PEN = 0.5F;
    static final Set<String> PEDESTAL = Set.of("light_revolver_dani", "heavy_revolver_lilmac", "maresleg_broken",
            "heavy_revolver_protege", "hangman", "mas36", "amat_penance", "amat_subtlety", "minigun_lacunae",
            "laser_pistol_morning_glory", "autoshotgun_sexy", "aberrator", "aberrator_eott");

    /** the table from the file's lines; a line it cannot read goes to {@code bad} */
    public static Table parse(List<String> lines, List<String> bad) {
        Map<String, Map<String, Shot>> pairs = new LinkedHashMap<>();
        Map<String, GunLine> guns = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\t");
            try {
                if (f.length < 5) throw new IllegalArgumentException("fewer than 5 columns");
                String gun = f[0].strip(), ammo = f[1].strip();
                float mult = f[2].strip().equals("-") ? Float.NaN : Float.parseFloat(f[2].strip());
                float neg = Float.parseFloat(f[3].strip()), pen = Float.parseFloat(f[4].strip());
                if (!Float.isNaN(mult) && !(mult >= 0F && Float.isFinite(mult))) throw new IllegalArgumentException("mult " + f[2]);
                boolean ped = PEDESTAL.contains(gun);
                if (ped && mult > PEDESTAL_MULT) mult = PEDESTAL_MULT;
                neg = Math.max(0F, Math.min(MOST_NEG, neg));
                pen = Math.max(0F, Math.min(MOST_PEN, pen));
                if (ammo.equals("*")) {
                    Integer delay = null, mag = null;
                    Boolean auto = null;
                    Float dmg = null;
                    String extra = f.length > 6 ? f[6].strip() : "";
                    for (String kv : extra.isEmpty() ? new String[0] : extra.split("\\s+")) {
                        String[] p = kv.split("=", 2);
                        if (p.length != 2) throw new IllegalArgumentException(kv);
                        switch (p[0]) {
                            case "delay" -> delay = positive(Integer.parseInt(p[1]), kv);
                            case "mag" -> mag = positive(Integer.parseInt(p[1]), kv);
                            case "auto" -> auto = Boolean.parseBoolean(p[1]);
                            case "dmg" -> {
                                float d = Float.parseFloat(p[1]);
                                if (!(d > 0F && Float.isFinite(d))) throw new IllegalArgumentException(kv);
                                dmg = d;
                            }
                            default -> throw new IllegalArgumentException(kv);
                        }
                    }
                    guns.put(gun, new GunLine(mult, delay, auto, mag, dmg));
                } else {
                    if (Float.isNaN(mult)) throw new IllegalArgumentException("a pair with no mult");
                    float floor = 1F;
                    int from = FLOOR_FROM;
                    List<Under> under = new ArrayList<>();
                    String extra = f.length > 6 ? f[6].strip() : "";
                    // a pair's column 7 was free text before floor=/from=: other words in it are left as they are
                    for (String kv : extra.isEmpty() ? new String[0] : extra.split("\\s+")) {
                        String[] p = kv.split("=", 2);
                        if (p.length != 2) continue;
                        switch (p[0]) {
                            case "floor" -> {
                                float v = Float.parseFloat(p[1]);
                                if (!(v > 0F && Float.isFinite(v))) throw new IllegalArgumentException(kv);
                                floor = Math.max(LEAST_FLOOR, Math.min(MOST_FLOOR, v));
                            }
                            case "from" -> from = positive(Integer.parseInt(p[1]), kv);
                            case "under" -> under.add(under(p[1], kv, ped));
                            default -> { }
                        }
                    }
                    int tier = -1;
                    // the tier column was for reading only before balanceArmorTierFloor: a word there is no tier
                    if (f.length > 5) try {
                        tier = Math.max(-1, Integer.parseInt(f[5].strip()));
                    } catch (NumberFormatException e) {
                        tier = -1;
                    }
                    pairs.computeIfAbsent(gun, g -> new LinkedHashMap<>()).put(ammo, new Shot(mult, neg, pen, floor, from, tier, under));
                }
            } catch (RuntimeException e) {
                bad.add(raw + "  (" + e.getMessage() + ")");
            }
        }
        return new Table(pairs, guns);
    }

    /** "T:mult:neg:pen" of under=, held to the line's limits */
    private static Under under(String v, String kv, boolean ped) {
        String[] p = v.split(":");
        if (p.length != 4) throw new IllegalArgumentException(kv);
        int below = positive(Integer.parseInt(p[0].strip()), kv);
        float mult = Float.parseFloat(p[1].strip()), neg = Float.parseFloat(p[2].strip()), pen = Float.parseFloat(p[3].strip());
        if (!(mult >= 0F && Float.isFinite(mult)) || !Float.isFinite(neg) || !Float.isFinite(pen)) throw new IllegalArgumentException(kv);
        if (ped && mult > PEDESTAL_MULT) mult = PEDESTAL_MULT;
        return new Under(below, mult, Math.max(0F, Math.min(MOST_NEG, neg)), Math.max(0F, Math.min(MOST_PEN, pen)));
    }

    private static int positive(int v, String kv) {
        if (v <= 0) throw new IllegalArgumentException(kv);
        return v;
    }

    /** piercing, blast or soft, by the ammo's name (its last word) */
    static String family(String ammo) {
        String last = ammo.substring(ammo.lastIndexOf('_') + 1);
        return switch (last) {
            case "ap", "du", "sm", "flechette", "ferrouranium", "tungsten", "black" -> "piercing";
            case "he", "explosive", "phosphorus", "exp" -> "blast";
            default -> "soft";
        };
    }

    /** an ammo that blows up: its mult (an explosive's damage is its blast's) is not for the rest */
    static boolean explosive(String ammo) {
        String last = ammo.substring(ammo.lastIndexOf('_') + 1);
        return last.equals("he") || last.equals("explosive") || last.equals("exp");
    }

    /** every ammo of a gun: its own line, its gun's mult, or the rule */
    public static Map<String, Resolved> resolve(Table t, String gun, List<Ammo> ammo) {
        Map<String, Resolved> out = new LinkedHashMap<>();
        GunLine line = t.guns().get(gun);
        Map<String, Shot> own = t.pairs().getOrDefault(gun, Map.of());
        if (line != null && !Float.isNaN(line.mult())) {
            for (Ammo a : ammo) {
                Shot s = own.get(a.name());
                out.put(a.name(), new Resolved(s != null ? s.withMult(line.mult()) : new Shot(line.mult(), 0F, 0F), "gun"));
            }
            return out;
        }
        Map<String, Float> click = new HashMap<>();
        Map<String, Boolean> shot = new HashMap<>();
        for (Ammo a : ammo) {
            click.put(a.name(), a.clickDamage());
            shot.put(a.name(), a.pellets() >= SHOT);
        }
        for (Ammo a : ammo) {
            Shot s = own.get(a.name());
            if (s != null) {
                out.put(a.name(), new Resolved(s, "table"));
                continue;
            }
            // the gun's pairs whose ammo it takes (a pair of an ammo it does not take is a typo of the file's)
            List<String> known = new ArrayList<>();
            String fam = family(a.name());
            for (String k : own.keySet()) {
                if (!click.containsKey(k)) continue;
                if (explosive(k) && !explosive(a.name())) continue;
                if (!shot.get(k).equals(shot.get(a.name()))) continue;
                known.add(k);
            }
            if (known.isEmpty()) {
                out.put(a.name(), new Resolved(Shot.NONE, "none"));
                continue;
            }
            List<String> same = new ArrayList<>();
            for (String k : known) if (family(k).equals(fam)) same.add(k);
            if (same.isEmpty()) {
                out.put(a.name(), new Resolved(Shot.NONE, "none"));
                continue;
            }
            String pick = nearest(same, click, a.clickDamage());
            out.put(a.name(), new Resolved(own.get(pick), "family " + pick));
        }
        return out;
    }

    private static String nearest(List<String> from, Map<String, Float> click, float d) {
        String best = null;
        double far = Double.MAX_VALUE;
        for (String k : from) {
            double v = Math.abs(Math.log(Math.max(1e-4, click.get(k))) - Math.log(Math.max(1e-4, d)));
            if (v < far) {
                far = v;
                best = k;
            }
        }
        return best;
    }
}
