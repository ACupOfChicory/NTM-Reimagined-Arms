package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Fable 2026-11-12, 10.4: the damage by range, a plateau and a floor (the model's follow1b.mult, as a damage share) */
class BalanceRangeRuleTest {
    private static final BalanceRangeRule.Rule R = new BalanceRangeRule.Rule(BalanceRangeRule.lines(BalanceRangeRule.LINES), 8,
            BalanceFinalRule.roles(BalanceRangeRule.HOME));

    /** follow1b.mult: the time's multiplier by role and distance */
    private static double mult(String role, double d) {
        Map<String, double[]> sb = Map.of("smg", new double[] {0.5, 24, 0.4, 64}, "pistol", new double[] {0.8, 24, 0.4, 64}, "slug", new double[] {1, 32, 0.4, 96},
                "rifle", new double[] {1, 64, 0.5, 160}, "mg", new double[] {1, 64, 0.5, 160}, "sniper", new double[] {1, 160, 0.7, 256}, "beam",
                new double[] {1, 32, 0.4, 96});
        Map<String, Double> dr = Map.of("slug", 7.0, "pistol", 12.0, "smg", 12.0, "mg", 12.0, "rifle", 20.0, "sniper", 30.0, "beam", 20.0);
        double[] l = sb.get(role);
        if (d <= 8) return l[0];
        double h = dr.get(role);
        if (d <= h && h > 8) return l[0] + (1 - l[0]) * (d - 8) / (h - 8);
        if (d <= l[1]) return 1;
        if (d >= l[3]) return 1 / l[2];
        return 1 / (1 - (1 - l[2]) * (d - l[1]) / (l[3] - l[1]));
    }

    @Test
    void theModelsCurve() {
        for (String role : new String[] {"smg", "pistol", "slug", "rifle", "mg", "sniper", "beam"})
            for (double d = 0; d <= 300; d += 0.5) assertEquals(1 / mult(role, d), BalanceRangeRule.damage(R, role, d), 1e-9, role + " at " + d);
    }

    @Test
    void numbersOfTable104() {
        assertEquals(1.25, BalanceRangeRule.damage(R, "pistol", 4), 1e-9);
        assertEquals(2.0, BalanceRangeRule.damage(R, "smg", 8), 1e-9);
        assertEquals(1.0, BalanceRangeRule.damage(R, "pistol", 12), 1e-9);
        assertEquals(0.4, BalanceRangeRule.damage(R, "pistol", 64), 1e-9);
        assertEquals(0.4, BalanceRangeRule.damage(R, "pistol", 200), 1e-9);
        assertEquals(2.0 / 3, BalanceRangeRule.damage(R, "rifle", 128), 1e-9);
        assertEquals(0.875, BalanceRangeRule.damage(R, "sniper", 200), 1e-9);
        // no line: the shotgun's pellets (their spread is their range), any other role
        assertEquals(1.0, BalanceRangeRule.damage(R, "shotgun", 100), 1e-9);
        assertEquals(1.0, BalanceRangeRule.damage(R, "airburst", 100), 1e-9);
    }

    @Test
    void offAndKeys() {
        assertTrue(BalanceRangeRule.lines("off").isEmpty());
        var off = new BalanceRangeRule.Rule(BalanceRangeRule.lines("off"), 8, Map.of());
        assertEquals(1.0, BalanceRangeRule.damage(off, "pistol", 4), 1e-9);
        var l = BalanceRangeRule.lines("rifle:x1/64/0.5@160 bad:1/2 smg:x0.5/24/0.4 junk:x1/a/0.4@9");
        assertEquals(2, l.size());
        assertEquals(24, l.get("smg").floorAt());
        assertEquals(1.0, BalanceRangeRule.damage(R, "rifle", Double.NaN), 1e-9);
    }
}
