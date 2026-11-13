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
        Map<String, double[]> sb = Map.of("smg", new double[] {0.5, 24, 0.4, 64}, "pistol", new double[] {0.8, 24, 0.4, 64}, "slug", new double[] {1, 32, 0.3, 128}, "shotgun", new double[] {1, 16, 0.4, 64},
                "rifle", new double[] {1, 64, 0.5, 160}, "mg", new double[] {1, 64, 0.5, 160}, "sniper", new double[] {1, 160, 0.7, 256}, "beam",
                new double[] {1, 64, 0.5, 160});
        Map<String, Double> dr = Map.of("slug", 7.0, "shotgun", 7.0, "pistol", 12.0, "smg", 12.0, "mg", 12.0, "rifle", 20.0, "sniper", 30.0, "beam", 20.0);
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
        for (String role : new String[] {"smg", "pistol", "shotgun", "slug", "rifle", "mg", "sniper", "beam"})
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
        // Fable №3: the shotgun's shot from 16 blocks to 0.4 at 64, the slug from 32 to 0.3 at 128 (the task's table)
        assertEquals(0.4, BalanceRangeRule.damage(R, "shotgun", 100), 1e-9);
        assertEquals(0.9, BalanceRangeRule.damage(R, "shotgun", 24), 1e-9);
        assertEquals(0.8, BalanceRangeRule.damage(R, "shotgun", 32), 1e-9);
        assertEquals(0.6, BalanceRangeRule.damage(R, "shotgun", 48), 1e-9);
        assertEquals(0.4, BalanceRangeRule.damage(R, "shotgun", 64), 1e-9);
        assertEquals(1.0, BalanceRangeRule.damage(R, "slug", 32), 1e-9);
        assertEquals(1 - 0.7 * 16 / 96, BalanceRangeRule.damage(R, "slug", 48), 1e-9);
        assertEquals(1 - 0.7 * 64 / 96, BalanceRangeRule.damage(R, "slug", 96), 1e-9);
        assertEquals(0.3, BalanceRangeRule.damage(R, "slug", 200), 1e-9);
        // X942's: the shotgun's shot to 0.4 at 48, the slug to 0.4 at 64
        var x942 = new BalanceRangeRule.Rule(BalanceRangeRule.lines(BalanceRangeRule.LINES_X942), 8, BalanceFinalRule.roles(BalanceRangeRule.HOME));
        assertEquals(0.7, BalanceRangeRule.damage(x942, "shotgun", 32), 1e-9);
        assertEquals(0.4, BalanceRangeRule.damage(x942, "slug", 64), 1e-9);
        assertEquals(2.0 / 3, BalanceRangeRule.damage(R, "beam", 128), 1e-9);
        // X922's lines: no shotgun's
        var old = new BalanceRangeRule.Rule(BalanceRangeRule.lines(BalanceRangeRule.LINES_X922), 8, BalanceFinalRule.roles(BalanceRangeRule.HOME));
        assertEquals(1.0, BalanceRangeRule.damage(old, "shotgun", 100), 1e-9);
        assertEquals(1.0, BalanceRangeRule.damage(R, "airburst", 100), 1e-9);
    }

    /** Fable №3, stage 3: the explosive shell's direct hit takes the line shell, else its role's; X942's lines X942 in every cell */
    @Test
    void shellLine() {
        var l = BalanceRangeRule.lines(BalanceRangeRule.LINES);
        assertEquals(new BalanceRangeRule.Line(1, 24, 0.25, 80), l.get("shell"));
        // the task's table: 12, 16, 24 blocks 1.00; 32 0.89; 48 0.68; 64 0.46; 80 and on 0.25
        double[][] want = {{12, 1}, {16, 1}, {24, 1}, {32, 1 - 0.75 * 8 / 56}, {48, 1 - 0.75 * 24 / 56}, {64, 1 - 0.75 * 40 / 56}, {80, 0.25}, {96, 0.25}, {128, 0.25}};
        for (double[] w : want) {
            assertEquals(w[1], BalanceRangeRule.shell(R, "shotgun", w[0]), 1e-9, "shell at " + w[0]);
            assertEquals(w[1], BalanceRangeRule.shell(R, "slug", w[0]), 1e-9, "a slug's shell at " + w[0]);
        }
        assertEquals(0.89, BalanceRangeRule.shell(R, "shotgun", 32), 0.005);
        assertEquals(0.68, BalanceRangeRule.shell(R, "shotgun", 48), 0.005);
        assertEquals(0.46, BalanceRangeRule.shell(R, "shotgun", 64), 0.005);
        // no shell: the role's line, X942 in every cell
        var x942 = new BalanceRangeRule.Rule(BalanceRangeRule.lines(BalanceRangeRule.LINES_X942), 8, BalanceFinalRule.roles(BalanceRangeRule.HOME));
        for (String role : new String[] {"shotgun", "slug", "rifle", "beam"})
            for (double d = 0; d <= 300; d += 0.5) assertEquals(BalanceRangeRule.damage(x942, role, d), BalanceRangeRule.shell(x942, role, d), 1e-12, role + " at " + d);
        var noShell = new BalanceRangeRule.Rule(BalanceRangeRule.lines(BalanceRangeRule.LINES.replace("shell:x1/24/0.25@80 ", "")), 8, BalanceFinalRule.roles(BalanceRangeRule.HOME));
        assertEquals(BalanceRangeRule.damage(R, "shotgun", 100), BalanceRangeRule.shell(noShell, "shotgun", 100), 1e-12);
        // the line shell is no role's: a shot's pellet keeps its role's line
        assertEquals(0.6, BalanceRangeRule.damage(R, "shotgun", 48), 1e-9);
    }

    @Test
    void brokenNumbersLeftOut() {
        assertTrue(BalanceRangeRule.lines("smg:x1e999/24/0.4@64 rifle:xNaN/64/0.5@160 mg:x1/64/0.5@Infinity").isEmpty());
        assertEquals(1, BalanceRangeRule.lines("smg:x1e999/24/0.4@64 rifle:x1/64/0.5@160").size());
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

    /**
     * owner 2026-11-13: the Shredder's hits (its beam's flash, its drops, a drop's blast) take the lasers' line in place of
     * the shotgun's, the slug's and the shell's; any other gun its role's; lineOf empty or off: X967 in every cell
     */
    @Test
    void gunsOwnLine() {
        var own = BalanceRangeRule.lineOfs(BalanceRangeRule.LINE_OF);
        assertEquals(Map.of("autoshotgun_shredder", "beam"), own);
        var r = new BalanceRangeRule.Rule(BalanceRangeRule.lines(BalanceRangeRule.LINES), 8, BalanceFinalRule.roles(BalanceRangeRule.HOME), own);
        // the beam's line: 1 up to 64 blocks, 0.5 at 160 and on
        double[][] want = {{4, 1}, {16, 1}, {32, 1}, {64, 1}, {96, 1 - 0.5 * 32 / 96}, {128, 1 - 0.5 * 64 / 96}, {160, 0.5}, {200, 0.5}};
        for (double[] w : want)
            for (String role : new String[] {"shotgun", "slug"})
                for (boolean shell : new boolean[] {false, true})
                    assertEquals(w[1], BalanceRangeRule.hit(r, "autoshotgun_shredder", role, shell, w[0]), 1e-9, role + (shell ? " shell" : "") + " at " + w[0]);
        // another gun: its role's, the shell's for a shell
        for (double d = 0; d <= 300; d += 0.5) {
            assertEquals(BalanceRangeRule.damage(r, "shotgun", d), BalanceRangeRule.hit(r, "autoshotgun", "shotgun", false, d), 1e-12);
            assertEquals(BalanceRangeRule.shell(r, "slug", d), BalanceRangeRule.hit(r, "autoshotgun", "slug", true, d), 1e-12);
        }
        // off and empty: no gun of its own line, the Shredder back on the shotgun's (X967)
        for (String off : new String[] {"off", "", "  ", null}) {
            var x967 = new BalanceRangeRule.Rule(r.lines(), 8, r.home(), BalanceRangeRule.lineOfs(off));
            for (double d = 0; d <= 300; d += 0.5) {
                assertEquals(BalanceRangeRule.damage(R, "shotgun", d), BalanceRangeRule.hit(x967, "autoshotgun_shredder", "shotgun", false, d), 1e-12);
                assertEquals(BalanceRangeRule.shell(R, "slug", d), BalanceRangeRule.hit(x967, "autoshotgun_shredder", "slug", true, d), 1e-12);
            }
        }
        // a broken entry is left out; a line the rule lacks: 1
        assertEquals(Map.of("a", "b"), BalanceRangeRule.lineOfs("a:b :c d: e"));
        var none = new BalanceRangeRule.Rule(r.lines(), 8, r.home(), Map.of("autoshotgun_shredder", "nope"));
        assertEquals(1.0, BalanceRangeRule.hit(none, "autoshotgun_shredder", "shotgun", false, 200), 1e-12);
    }
}
