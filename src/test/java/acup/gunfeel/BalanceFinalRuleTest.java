package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

// The solver against the model's own tables (notes/balance/fable/model: gen_tables.py; X912: notes/balance/final/model).
class BalanceFinalRuleTest {
    private static final BalanceFinalRule.Params P = BalanceFinalRule.Params.DEFAULT;
    private static final Map<String, BalanceFinalRule.Armor> SETS = BalanceFinalRule.MODEL_SETS;
    private static final BalanceFinalRule.Armor FAU = SETS.get("fau");

    private static List<String> lines(String name) throws IOException {
        return Files.readAllLines(Path.of("src/main/resources/" + name), StandardCharsets.UTF_8);
    }

    private static Map<String, BalanceFinalRule.Pair> pairs() throws IOException {
        List<String> bad = new ArrayList<>();
        var out = BalanceFinalRule.pairs(lines("ntmgunfeel-balance-final-pairs.tsv"), Map.of(), bad);
        assertTrue(bad.isEmpty(), bad.toString());
        return out;
    }

    /** the solver with {@code p} on the model's sets {@code sets} against a table of the model's: within one click, 99 % exact */
    private static void reproduces(String table, BalanceFinalRule.Params p, Map<String, BalanceFinalRule.Armor> sets) throws IOException {
        var pairs = pairs();
        var refs = BalanceFinalRule.refs(pairs.values());
        String[] cols = null;
        int cells = 0, exact = 0;
        List<String> off = new ArrayList<>();
        for (String l : Files.readAllLines(Path.of("src/test/resources/balance/" + table), StandardCharsets.UTF_8)) {
            if (l.startsWith("# pair")) cols = l.substring(2).split("\t");
            if (l.startsWith("#") || l.isBlank()) continue;
            String[] c = l.split("\t");
            var pr = pairs.get(c[0]);
            assertTrue(pr != null, c[0]);
            for (int i = 2; i < c.length; i++) {
                var a = sets.get(cols[i]);
                double nWant = Double.parseDouble(c[i]);
                double nGot = BalanceFinalRule.HP / BalanceFinalRule.clickDamage(pr, a, sets.get("fau"), refs, p);
                cells++;
                if (Math.abs(nWant - nGot) < 1e-3) exact++;
                if (Math.abs(nWant - nGot) > 1 + 1e-6) off.add(c[0] + "/" + cols[i] + ": " + nWant + " vs " + nGot);
            }
        }
        assertEquals(251 * 21, cells);
        assertTrue(off.isEmpty(), off.size() + " cells off: " + off.subList(0, Math.min(10, off.size())));
        assertTrue(exact >= cells * 0.99, exact + " of " + cells + " exact");
    }

    // Fable 2026-11-12 (notes/balance/fable/model, gen_tables.py): ladder H, roles, slug, pools 45/40 and the magazine's rule
    @Test
    void reproducesFable() throws IOException {
        reproduces("final-table-fable.tsv", P, SETS);
    }

    // stage 1: the same with the magazine's rule off (fm.use('fable', edge=False))
    @Test
    void reproducesFableWithoutTheMagazinesRule() throws IOException {
        reproduces("final-table-fable-noedge.tsv", P.withMag(BalanceFinalRule.MagRule.OFF), SETS);
    }

    // X912 is the keys' old values: its ladder and roles, no slug, no magazine's rule, pools 40/45
    @Test
    void reproducesX912ByTheOldKeys() throws IOException {
        reproduces("final-table-x912.tsv", BalanceFinalRule.Params.X912, BalanceFinalRule.modelSets(40, 45));
    }

    // no one-shot on the head: a click on the head does at most the head's cap of 16 HP, below every set's 20 and more
    @Test
    void headTakesTwoClicksOrMore() throws IOException {
        var pairs = pairs();
        var refs = BalanceFinalRule.refs(pairs.values());
        for (var pr : pairs.values())
            for (var a : SETS.values()) {
                double click = BalanceFinalRule.clickDamage(pr, a, FAU, refs, P);
                double head = Math.min(0.8 * BalanceFinalRule.HP, click * 1.75);
                assertTrue(Math.ceil((BalanceFinalRule.HP + a.pool()) / head - 1e-9) >= 2, pr.id() + " on " + a.kind());
                assertTrue(!BalanceFinalRule.headOneShot(pr, a.hours(), P), pr.id());
            }
    }

    // the magazine's rule (Fable 10.3): G3 on HEV 26 rounds of 30, the heavy revolver 5 or 6 of 6
    @Test
    void magazinesRule() throws IOException {
        var pairs = pairs();
        var refs = BalanceFinalRule.refs(pairs.values());
        var hev = SETS.get("hev");
        int g3 = BalanceFinalRule.hitClicks(pairs.get("g3.r556_fmj"), hev, FAU, refs, P);
        double[] t = BalanceFinalRule.realTtk(BalanceFinalRule.HP / g3, pairs.get("g3.r556_fmj"), 20);
        assertEquals(26, (int) t[1]);
        int rev = BalanceFinalRule.hitClicks(pairs.get("heavy_revolver.m44_fmj"), hev, FAU, refs, P);
        double[] r = BalanceFinalRule.realTtk(BalanceFinalRule.HP / rev, pairs.get("heavy_revolver.m44_fmj"), 20);
        assertTrue(r[1] >= 5 && r[1] <= 6, "heavy revolver " + r[1]);
        var m = BalanceFinalRule.MagRule.parse("reserve=0.3 junk small=2", BalanceFinalRule.MagRule.DEFAULT);
        assertEquals(0.3, m.reserve());
        assertEquals(2, m.small());
        assertEquals(0.6, m.min());
        assertTrue(!BalanceFinalRule.MagRule.parse("reserve=0", m).on());
    }

    @Test
    void dnsNeverQuickerThanFau() throws IOException {
        var pairs = pairs();
        var refs = BalanceFinalRule.refs(pairs.values());
        for (var pr : pairs.values()) {
            double dns = BalanceFinalRule.realTtk(BalanceFinalRule.clickDamage(pr, SETS.get("dns"), FAU, refs, P), pr, 20 + SETS.get("dns").pool())[0];
            double fau = BalanceFinalRule.realTtk(BalanceFinalRule.clickDamage(pr, FAU, null, refs, P), pr, 20 + FAU.pool())[0];
            assertTrue(dns >= fau - 1e-9, pr.id() + ": DNS " + dns + " < FAU " + fau);
        }
    }

    private static BalanceFinalRule.Params heads(java.util.Set<String> guns) {
        return P.withHeadGuns(guns);
    }

    @Test
    void headOneShot() throws IOException {
        // owner 2026-11-12 (3): no one-shots on the head by default; the four sniper rifles' rule by the key
        var four = heads(java.util.Set.of("amat", "amat_penance", "amat_subtlety", "mas36"));
        int amat = 0, all = 0, byDefault = 0;
        for (var pr : pairs().values())
            for (var e : SETS.entrySet()) {
                if (e.getKey().equals("euph")) continue;
                if (BalanceFinalRule.headOneShot(pr, e.getValue().hours(), P)) byDefault++;
                if (!BalanceFinalRule.headOneShot(pr, e.getValue().hours(), four)) continue;
                all++;
                if (pr.gun().equals("amat")) amat++;
            }
        assertEquals(0, byDefault);
        // out_final.md, D (the rule of answer 7): AMAT 46 cells, the four guns 266
        assertEquals(46, amat);
        assertEquals(266, all);
        var only = heads(java.util.Set.of("amat"));
        var mas = pairs().get("mas36.r762_du");
        assertTrue(BalanceFinalRule.headOneShot(mas, 4, four));
        assertTrue(!BalanceFinalRule.headOneShot(mas, 4, only));
        assertTrue(!BalanceFinalRule.headOneShot(mas, 6, four));
    }

    @Test
    void mixedSets() throws IOException {
        var pairs = pairs();
        var refs = BalanceFinalRule.refs(pairs.values());
        var carbine = pairs.get("carbine.r762_sp");
        double[] w = {3, 8, 6, 3};
        var hev = SETS.get("hev");
        // the model's numbers (A_eff, hit clicks; X912's ladder)
        Object[][] cases = {{new BalanceFinalRule.Armor[] {hev, hev, hev, null}, 3.48, 11}, {new BalanceFinalRule.Armor[] {SETS.get("steel"), hev, hev, SETS.get("steel")}, 3.10, 10},
                {new BalanceFinalRule.Armor[] {null, hev, null, null}, 1.90, 8}, {new BalanceFinalRule.Armor[] {null, SETS.get("cmb"), SETS.get("cmb"), null}, 5.05, 17},
                {new BalanceFinalRule.Armor[] {null, SETS.get("dns"), SETS.get("dns"), null}, 6.45, 88}};
        for (Object[] c : cases) {
            var mix = BalanceFinalRule.mix((BalanceFinalRule.Armor[]) c[0], w, 0.5);
            assertEquals((double) c[1], mix.tier(), 0.005);
            assertEquals(0.0, mix.pool());
            assertEquals((int) c[2], BalanceFinalRule.hitClicks(carbine, mix, null, refs, BalanceFinalRule.Params.X912));
        }
    }

    @Test
    void keysText() {
        var a = BalanceFinalRule.anchors("+3:1 0:2 -1:4 junk 2:x");
        assertEquals(3, a.size());
        assertEquals(2.0, BalanceFinalRule.ttkOf(0, P.anchors().size() > 0 ? new BalanceFinalRule.Params(a, 0.7, 900, 2, 0.3, 0.7, 1.4, 1.15, 4, 0.15, 1, -1, 0.5, 1, 1.15,
                java.util.Set.of(), 1, 1, Map.of(), 0, BalanceFinalRule.MagRule.OFF) : P), 1e-9);
        // between 0 and +3 by the logarithm: no anchors at +1 and +2
        var q = new BalanceFinalRule.Params(a, 0.1, 900, 2, 0.3, 0.7, 1.4, 1.15, 4, 0.15, 1, -1, 0.5, 1, 1.15, java.util.Set.of(), 1, 1, Map.of(), 0,
                BalanceFinalRule.MagRule.OFF);
        assertEquals(Math.exp(Math.log(2) * 2 / 3 + Math.log(1) / 3), BalanceFinalRule.ttkOf(1, q), 1e-9);
        assertEquals(16.0, BalanceFinalRule.ttkOf(-3, q), 1e-9);
        assertEquals(P.anchors(), BalanceFinalRule.anchors(""));
        assertEquals(1.6, BalanceFinalRule.roles(BalanceFinalRule.Params.ROLES).get("smg"));
    }
}
