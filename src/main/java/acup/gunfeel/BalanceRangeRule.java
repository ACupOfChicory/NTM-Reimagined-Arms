package acup.gunfeel;

import java.util.HashMap;
import java.util.Map;

/**
 * The scheme's damage by range (balance.final.range; Fable 2026-11-12, 10.4, after WARDOGS): a bullet's or a beam's
 * hit of the scheme takes its click's damage times a share by its role and the distance from the shooter to the target.
 * Up to {@code close} blocks a role of its own close-up time (an SMG x0.5, a pistol x0.8) hits as many times harder
 * (twice, 1.25 times), back to 1 by its {@code home} distance; full damage up to {@code full} blocks; then straight down
 * to {@code floor} of it at {@code floorAt} and no lower. A role of no line, blasts (but a shotgun's explosive shell's
 * direct hit, balance.final.range.shells) and the Tesla Cannon's arc: 1.
 */
public final class BalanceRangeRule {
    private BalanceRangeRule() {
    }

    /** a role's line: the time's multiplier close up, full damage up to, the floor and where it is reached */
    public record Line(double closeTime, double full, double floor, double floorAt) {
    }

    /** the rule: its lines by role, the close range and the roles' home distances */
    public record Rule(Map<String, Line> lines, double close, Map<String, Double> home) {
        public static final Rule OFF = new Rule(Map.of(), 8, Map.of());

        public boolean on() {
            return !lines.isEmpty();
        }
    }

    /** Fable №3 (3.3): the shotgun's shot to 0.4 at 64 blocks, the slug from 32 to 0.3 at 128 */
    public static final String LINES = "smg:x0.5/24/0.4@64 pistol:x0.8/24/0.4@64 shotgun:x1/16/0.4@64 slug:x1/32/0.3@128 beam:x1/64/0.5@160 rifle:x1/64/0.5@160 mg:x1/64/0.5@160 sniper:x1/160/0.7@256";
    /** X942's (Fable №2, 2.1, 2.3): the shotgun's shot from 16 blocks to 0.4 at 48, the slug from 24 to 0.4 at 64, the beams as a rifle */
    public static final String LINES_X942 = "smg:x0.5/24/0.4@64 pistol:x0.8/24/0.4@64 shotgun:x1/16/0.4@48 slug:x1/24/0.4@64 beam:x1/64/0.5@160 rifle:x1/64/0.5@160 mg:x1/64/0.5@160 sniper:x1/160/0.7@256";
    /** X922's (Fable's first review, 10.4) */
    public static final String LINES_X922 = "smg:x0.5/24/0.4@64 pistol:x0.8/24/0.4@64 slug:x1/32/0.4@96 beam:x1/32/0.4@96 rifle:x1/64/0.5@160 mg:x1/64/0.5@160 sniper:x1/160/0.7@256";
    public static final String HOME = "pistol:12 smg:12";

    /** "role:xT/full/floor@at" by spaces; "off" or none: no line; a broken entry is left out */
    public static Map<String, Line> lines(String text) {
        Map<String, Line> out = new HashMap<>();
        if (text == null || text.trim().equalsIgnoreCase("off")) return out;
        for (String e : text.trim().split("\\s+")) {
            int c = e.indexOf(':');
            if (c <= 0) continue;
            String v = e.substring(c + 1);
            try {
                String[] p = v.split("/");
                if (p.length != 3 || !p[0].startsWith("x")) continue;
                double x = Double.parseDouble(p[0].substring(1)), full = Double.parseDouble(p[1]);
                int at = p[2].indexOf('@');
                double floor = Double.parseDouble(at < 0 ? p[2] : p[2].substring(0, at));
                double floorAt = at < 0 ? full : Double.parseDouble(p[2].substring(at + 1));
                if (Double.isFinite(x) && Double.isFinite(floorAt) && x > 0 && full >= 0 && floor > 0 && floor <= 1 && floorAt >= full) out.put(e.substring(0, c), new Line(x, full, floor, floorAt));
            } catch (NumberFormatException ignored) {
                // left out
            }
        }
        return out;
    }

    /** the damage's multiplier of a hit of a pair of {@code role} at {@code dist} blocks */
    public static double damage(Rule r, String role, double dist) {
        Line l = r.lines().get(role);
        if (l == null || !Double.isFinite(dist)) return 1.0;
        // the time's multiplier close up, back to 1 at home
        double time = 1.0;
        if (l.closeTime() != 1.0) {
            Double home = r.home().get(role);
            if (dist <= r.close()) time = l.closeTime();
            else if (home != null && home > r.close() && dist < home) time = l.closeTime() + (1 - l.closeTime()) * (dist - r.close()) / (home - r.close());
        }
        double share = 1.0;
        if (dist > l.full()) share = dist >= l.floorAt() || l.floorAt() <= l.full() ? l.floor() : 1 - (1 - l.floor()) * (dist - l.full()) / (l.floorAt() - l.full());
        return share / time;
    }
}
