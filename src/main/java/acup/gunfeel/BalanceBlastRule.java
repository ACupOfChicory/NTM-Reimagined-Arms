package acup.gunfeel;

import java.util.HashMap;
import java.util.Map;

/**
 * Blasts on the scheme's ladder without the game (BalanceFinal applies it; Fable 2026-11-12, 2.7, 11.2, 12.3): a hand
 * grenade's or a launcher's round's blast on a player, or on anyone in a set of the table, takes 1/N of the target's
 * health and pool near it (up to a block), N worked out from the TTK of the ladder for {@code d} = the blast's tier +
 * {@code shift} − the set's tier, like a bullet's clicks:
 * <ul>
 * <li>a launcher's round: the clicks whose real TTK (the launcher's interval, magazine and reload) comes closest to it;
 * the round's tier is the smaller of the launcher's and the round's + {@code cap} (a good tube lifts an old grenade two
 * tiers, no more);</li>
 * <li>a hand grenade: {@code near} hits at d = 0, as many more as the TTK is longer than the equal fight's.</li>
 * </ul>
 * A direct hit takes {@code direct} times the near blast, {@code farAt} blocks {@code far} times, the radius's edge
 * {@code edge} times (straight between); never more than half the health and pool (two hits at least). No head, no
 * limb. The radius, the blocks and the big charges are NTM's.
 */
public final class BalanceBlastRule {
    private BalanceBlastRule() {
    }

    /** a launcher: its tier, the seconds between two shots, its magazine (0: one round a reload) and its reload, s */
    public record Launcher(double tier, double interval, double mag, double reload) {
    }

    /** near hits at d = 0 (hand grenades) and the multipliers by where the blast is */
    public record Hits(double near, double direct, double far, double farAt, double edge) {
        public static final Hits DEFAULT = new Hits(3, 1.5, 0.75, 3, 0.4);
    }

    /**
     * a fragment grenade of the GL-54's: the share of its cone's fragments in the target and the piercing of each (Fable
     * 11.3, 12.3); a burst whose cone all hits is the click (design.md's shares are of the cone, the ring is the side spray)
     */
    public record Frag(double share, double pen) {
    }

    /** the rule's numbers (balance.final.blast*) */
    public record Rule(boolean on, Map<String, Launcher> launchers, Map<String, Double> rounds, Map<String, Double> grenades, double shift, double cap,
            Hits hits, Map<String, Frag> frags) {
    }

    /** Fable 12.3: the GL-54's fragment grenades by kind, share[/piercing] (the shares are Fable's guess, 11.6) */
    public static final String FRAGS = "airburst:0.35 ap:0.6/0.15 inc:0.35";

    /** "kind:share[/piercing]" by spaces */
    public static Map<String, Frag> frags(String text) {
        Map<String, Frag> out = new HashMap<>();
        if (text != null) for (String e : text.trim().split("\\s+")) {
            String[] p = e.split(":");
            if (p.length != 2) continue;
            try {
                String[] q = p[1].split("/");
                double share = Double.parseDouble(q[0]), pen = q.length > 1 ? Double.parseDouble(q[1]) : 0;
                if (share > 0 && share <= 1 && pen >= 0 && pen <= 1) out.put(p[0], new Frag(share, pen));
            } catch (NumberFormatException ignored) {
                // left out
            }
        }
        return out;
    }

    /** Fable 12.3 and 12.5: the launchers (tier:interval/magazine/reload; a one-shot one: tier:seconds from a shot to the next) */
    public static final String LAUNCHERS = "congolake:2:1.2/4/3.2 mk108:4:0.5/30/6.75 panzerschreck:4:2.5 stinger:4:2.5 quadro:4:0.5/4/2.75 missile_launcher:5:2.4 gl54:5:0.7/8/2.25";
    /** the launchers' rounds by NTM's ammo name, the GL-54's by its kind: the tier of the explosive in them */
    public static final String ROUNDS = "g40_he:1 g40_heat:4 g40_demo:4 g40_inc:3 rocket_he:1 rocket_heat:4 rocket_demo:4 rocket_inc:3 he:1 airburst:1 inc:1 ap:4";
    /** NTM's hand grenades by their filling */
    public static final String GRENADES = "powder:0 he:1 demo:4";

    /** "name:tier" or "name:tier:interval/magazine/reload" by spaces; a broken entry is left out */
    public static Map<String, Launcher> launchers(String text) {
        Map<String, Launcher> out = new HashMap<>();
        if (text != null) for (String e : text.trim().split("\\s+")) {
            String[] p = e.split(":");
            if (p.length < 3) continue;
            try {
                double t = Double.parseDouble(p[1]);
                String[] q = p[2].split("/");
                double iv = Double.parseDouble(q[0]);
                double mag = q.length > 1 ? Double.parseDouble(q[1]) : 0, rl = q.length > 2 ? Double.parseDouble(q[2]) : 0;
                if (Double.isFinite(t) && iv > 0 && mag >= 0 && rl >= 0) out.put(p[0], new Launcher(t, iv, mag, rl));
            } catch (NumberFormatException ignored) {
                // left out
            }
        }
        return out;
    }

    /** "name:tier" by spaces, any finite tier (the powder grenade's is 0); a broken entry is left out */
    public static Map<String, Double> tiers(String text) {
        Map<String, Double> out = new HashMap<>();
        if (text != null) for (String e : text.trim().split("\\s+")) {
            int c = e.indexOf(':');
            if (c <= 0) continue;
            try {
                double v = Double.parseDouble(e.substring(c + 1));
                if (Double.isFinite(v) && v >= -5 && v <= 20) out.put(e.substring(0, c), v);
            } catch (NumberFormatException ignored) {
                // left out
            }
        }
        return out;
    }

    /** "near=3 direct=1.5 far=0.75@3 edge=0.4" */
    public static Hits hits(String text, Hits def) {
        double near = def.near(), direct = def.direct(), far = def.far(), at = def.farAt(), edge = def.edge();
        if (text != null) for (String e : text.trim().split("\\s+")) {
            int c = e.indexOf('=');
            if (c <= 0) continue;
            try {
                String v = e.substring(c + 1);
                switch (e.substring(0, c)) {
                    case "near" -> near = pos(Double.parseDouble(v), near);
                    case "direct" -> direct = pos(Double.parseDouble(v), direct);
                    case "edge" -> edge = pos(Double.parseDouble(v), edge);
                    case "far" -> {
                        int a = v.indexOf('@');
                        far = pos(Double.parseDouble(a < 0 ? v : v.substring(0, a)), far);
                        if (a > 0) at = Math.max(1.0001, Double.parseDouble(v.substring(a + 1)));
                    }
                    default -> {
                    }
                }
            } catch (NumberFormatException ignored) {
                // left out
            }
        }
        return new Hits(near, direct, far, at, edge);
    }

    private static double pos(double v, double def) {
        return Double.isFinite(v) && v > 0 ? v : def;
    }

    /** a launcher's round's tier: the smaller of the launcher's and the round's + cap; cap NaN (balance.final.blast.pair = max): the larger of the two */
    public static double pairTier(Launcher l, double round, double cap) {
        return Double.isNaN(cap) ? Math.max(l.tier(), round) : Math.min(l.tier(), round + cap);
    }

    /** balance.final.blast.pair: "cap:2" (Fable 12.3) or "max" (the larger of the launcher's and the round's, 11.1); NaN for max */
    public static double cap(String text, double def) {
        String t = text == null ? "" : text.trim();
        if (t.equals("max")) return Double.NaN;
        if (t.startsWith("cap:")) try {
            double v = Double.parseDouble(t.substring(4));
            if (Double.isFinite(v) && v >= 0 && v <= 20) return v;
        } catch (NumberFormatException ignored) {
            // the default
        }
        return def;
    }

    /** the TTK the blast of {@code tier} is to kill the set in, its pool too, s */
    public static double target(double tier, BalanceFinalRule.Armor a, BalanceFinalRule.Params p, Rule r) {
        return BalanceFinalRule.ttkOf(tier + r.shift() - a.tier(), p) * (BalanceFinalRule.HP + a.pool()) / BalanceFinalRule.HP;
    }

    /** a launcher's real TTK of {@code n} rounds fired, s */
    static double fired(int n, Launcher l) {
        if (l.mag() <= 0) return (n - 1) * l.interval();
        return (n - 1) * l.interval() + Math.floor((n - 1) / l.mag()) * l.reload();
    }

    /** a launcher's near hits to kill: the rounds (2 or more) whose real TTK comes closest to {@code target} */
    public static int nearLauncher(Launcher l, double target) {
        return nearLauncher(l, target, 2);
    }

    /** the same from {@code nmin} rounds up */
    public static int nearLauncher(Launcher l, double target, int nmin) {
        int best = Math.max(1, nmin);
        double bt = fired(best, l);
        for (int n = best + 1; n < 5000; n++) {
            double t = fired(n, l);
            if (Math.abs(t - target) < Math.abs(bt - target)) {
                best = n;
                bt = t;
            }
            if (t > target * 1.5 + 5) break;
        }
        return best;
    }

    /** a hand grenade's near hits to kill (not a whole number): near at the equal fight, as many times more as the TTK */
    public static double nearGrenade(double target, BalanceFinalRule.Params p, Rule r) {
        return r.hits().near() * target / BalanceFinalRule.ttkOf(0, p);
    }

    /** the multiplier of the near blast where the target is: struck, {@code dist} blocks off its box, {@code radius} the blast's reach */
    public static double where(boolean direct, double dist, double radius, Hits h) {
        if (direct) return h.direct();
        if (dist <= 1) return 1;
        if (dist <= h.farAt()) return 1 + (h.far() - 1) * (dist - 1) / (h.farAt() - 1);
        if (radius <= h.farAt() || dist >= radius) return dist >= radius ? h.edge() : h.far();
        return h.far() + (h.edge() - h.far()) * (dist - h.farAt()) / (radius - h.farAt());
    }

    /** the share of the target's health and pool a blast takes: 1/near times where, at most a half */
    public static double share(double near, double where) {
        return Math.min(0.5, where / Math.max(near, 1e-9));
    }

    /**
     * a fragment grenade's burst whose fragments all hit, of the target's health and pool: the bursts (at least so many
     * that one does not pass the click's ceiling) whose real TTK comes closest to the ladder's at {@code tier} + its
     * piercing's lift against armour of tier 3 and up, times the role
     */
    public static double fragClick(Launcher l, Frag f, double tier, BalanceFinalRule.Armor a, BalanceFinalRule.Params p, double role) {
        double lift = Math.min(1, 4 * f.pen()) * Math.min(1, Math.max(0, a.tier()) / 3);
        double total = BalanceFinalRule.HP + a.pool();
        double t = BalanceFinalRule.ttkOf(tier + lift - a.tier(), p) * role * total / BalanceFinalRule.HP;
        int nmin = Math.max(2, (int) Math.ceil(total / (CLICK_MAX * f.share()) - 1e-9));
        int n = nearLauncher(l, t, nmin);
        return total / (n * f.share());
    }

    /** the most a click may do, HP of 20 (the damage cap's click, 0.6) */
    static final double CLICK_MAX = 12;

    /** hits of a share to kill (the stand's count) */
    public static int hitsOf(double share) {
        return share <= 0 ? Integer.MAX_VALUE : (int) Math.ceil(1 / share - 1e-9);
    }
}
