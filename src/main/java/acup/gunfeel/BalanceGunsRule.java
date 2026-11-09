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
 * to the ammo's armour threshold negation, {@code pen} to its armour piercing. A gun's own line (ammo {@code *}): a
 * mult for all its ammo (over its pairs; {@code -} for none) and the receiver's changes ({@code delay=}, {@code auto=},
 * {@code mag=}, {@code dmg=}).
 *
 * <p>An ammo with no line, of a gun with lines:
 * <ol>
 * <li>the gun's pair of the same family (piercing: AP, DU, SM, flechette, coil slugs; blast: HE, explosive, phosphorus;
 * the rest soft: SP, FMJ, JHP, express, shot, slugs), the nearest by NTM's damage of a click if more than one;</li>
 * <li>no pair of that family: the gun's pair nearest by NTM's damage of a click.</li>
 * </ol>
 * Its neg and pen go with the mult. A gun with no line at all: NTM as it is.
 */
public final class BalanceGunsRule {
    private BalanceGunsRule() {
    }

    /** what a pair does to a shot */
    public record Shot(float mult, float neg, float pen) {
        public static final Shot NONE = new Shot(1F, 0F, 0F);

        public boolean none() {
            return mult == 1F && neg == 0F && pen == 0F;
        }
    }

    /** a gun's own line: its mult for every ammo (NaN: none) and its receiver's changes (null: as NTM has it) */
    public record GunLine(float mult, Integer delay, Boolean auto, Integer mag, Float dmg) {
    }

    /** a gun's ammo as NTM has it: its name (the item's path less ammo_standard_ or ammo_) and the damage of a click */
    public record Ammo(String name, float clickDamage) {
    }

    /** what an ammo gets and where it came from: "table", "gun", "family &lt;ammo&gt;", "nearest &lt;ammo&gt;" or "none" */
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
                    pairs.computeIfAbsent(gun, g -> new LinkedHashMap<>()).put(ammo, new Shot(mult, neg, pen));
                }
            } catch (RuntimeException e) {
                bad.add(raw + "  (" + e.getMessage() + ")");
            }
        }
        return new Table(pairs, guns);
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

    /** every ammo of a gun: its own line, its gun's mult, or the rule */
    public static Map<String, Resolved> resolve(Table t, String gun, List<Ammo> ammo) {
        Map<String, Resolved> out = new LinkedHashMap<>();
        GunLine line = t.guns().get(gun);
        Map<String, Shot> own = t.pairs().getOrDefault(gun, Map.of());
        if (line != null && !Float.isNaN(line.mult())) {
            for (Ammo a : ammo) {
                Shot s = own.get(a.name());
                out.put(a.name(), new Resolved(new Shot(line.mult(), s != null ? s.neg() : 0F, s != null ? s.pen() : 0F), "gun"));
            }
            return out;
        }
        Map<String, Float> click = new HashMap<>();
        for (Ammo a : ammo) click.put(a.name(), a.clickDamage());
        for (Ammo a : ammo) {
            Shot s = own.get(a.name());
            if (s != null) {
                out.put(a.name(), new Resolved(s, "table"));
                continue;
            }
            // the gun's pairs whose ammo it takes (a pair of an ammo it does not take is a typo of the file's)
            List<String> known = new ArrayList<>();
            for (String k : own.keySet()) if (click.containsKey(k)) known.add(k);
            if (known.isEmpty()) {
                out.put(a.name(), new Resolved(Shot.NONE, "none"));
                continue;
            }
            String fam = family(a.name());
            List<String> same = new ArrayList<>();
            for (String k : known) if (family(k).equals(fam)) same.add(k);
            String pick = nearest(same.isEmpty() ? known : same, click, a.clickDamage());
            out.put(a.name(), new Resolved(own.get(pick), (same.isEmpty() ? "nearest " : "family ") + pick));
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
