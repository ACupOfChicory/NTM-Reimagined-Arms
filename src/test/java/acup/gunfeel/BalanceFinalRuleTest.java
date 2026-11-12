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

// The solver against the model's own table (notes/balance/final/model: final_check.py with the keys' defaults).
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

    @Test
    void reproducesTheModelsTable() throws IOException {
        var pairs = pairs();
        var refs = BalanceFinalRule.refs(pairs.values());
        String[] cols = null;
        int cells = 0, exact = 0;
        List<String> off = new ArrayList<>();
        for (String l : lines("ntmgunfeel-balance-final-table.tsv")) {
            if (l.startsWith("# pair")) cols = l.substring(2).split("\t");
            if (l.startsWith("#") || l.isBlank()) continue;
            String[] c = l.split("\t");
            var pr = pairs.get(c[0]);
            assertTrue(pr != null, c[0]);
            for (int i = 2; i < c.length; i++) {
                var a = SETS.get(cols[i]);
                double want = BalanceFinalRule.HP / Double.parseDouble(c[i]);
                double got = BalanceFinalRule.clickDamage(pr, a, FAU, refs, P);
                double nWant = BalanceFinalRule.HP / want, nGot = BalanceFinalRule.HP / got;
                cells++;
                if (Math.abs(nWant - nGot) < 1e-3) exact++;
                if (Math.abs(nWant - nGot) > 1 + 1e-6) off.add(c[0] + "/" + cols[i] + ": " + nWant + " vs " + nGot);
            }
        }
        assertEquals(251 * 21, cells);
        assertTrue(off.isEmpty(), off.size() + " cells off: " + off.subList(0, Math.min(10, off.size())));
        assertTrue(exact >= cells * 0.99, exact + " of " + cells + " exact");
    }

    @Test
    void dnsNeverQuickerThanFau() throws IOException {
        var pairs = pairs();
        var refs = BalanceFinalRule.refs(pairs.values());
        for (var pr : pairs.values()) {
            double dns = BalanceFinalRule.realTtk(BalanceFinalRule.clickDamage(pr, SETS.get("dns"), FAU, refs, P), pr, 60)[0];
            double fau = BalanceFinalRule.realTtk(BalanceFinalRule.clickDamage(pr, FAU, null, refs, P), pr, 65)[0];
            assertTrue(dns >= fau - 1e-9, pr.id() + ": DNS " + dns + " < FAU " + fau);
        }
    }

    @Test
    void headOneShot() throws IOException {
        int amat = 0, all = 0;
        for (var pr : pairs().values())
            for (var e : SETS.entrySet()) {
                if (e.getKey().equals("euph")) continue;
                if (!BalanceFinalRule.headOneShot(pr, e.getValue().hours(), P)) continue;
                all++;
                if (pr.gun().equals("amat")) amat++;
            }
        // out_final.md, D: AMAT 46 cells, the four guns 266
        assertEquals(46, amat);
        assertEquals(266, all);
        var only = new BalanceFinalRule.Params(P.anchors(), P.tmin(), P.tmax(), P.below(), P.beta(), P.lo(), P.hi(), P.energy(), P.ammoK(), P.ammoFrom(),
                P.ammoMax(), P.jhp(), P.ped(), P.explShift(), P.dnsOverFau(), java.util.Set.of("amat"), P.headGap(), P.laserTrench(), P.roles());
        var mas = pairs().get("mas36.r762_du");
        assertTrue(BalanceFinalRule.headOneShot(mas, 4, P));
        assertTrue(!BalanceFinalRule.headOneShot(mas, 4, only));
        assertTrue(!BalanceFinalRule.headOneShot(mas, 6, P));
    }

    @Test
    void mixedSets() throws IOException {
        var pairs = pairs();
        var refs = BalanceFinalRule.refs(pairs.values());
        var carbine = pairs.get("carbine.r762_sp");
        double[] w = {3, 8, 6, 3};
        var hev = SETS.get("hev");
        // the model's numbers (A_eff, hit clicks)
        Object[][] cases = {{new BalanceFinalRule.Armor[] {hev, hev, hev, null}, 3.48, 11}, {new BalanceFinalRule.Armor[] {SETS.get("steel"), hev, hev, SETS.get("steel")}, 3.10, 10},
                {new BalanceFinalRule.Armor[] {null, hev, null, null}, 1.90, 8}, {new BalanceFinalRule.Armor[] {null, SETS.get("cmb"), SETS.get("cmb"), null}, 5.05, 18},
                {new BalanceFinalRule.Armor[] {null, SETS.get("dns"), SETS.get("dns"), null}, 6.45, 115}};
        for (Object[] c : cases) {
            var mix = BalanceFinalRule.mix((BalanceFinalRule.Armor[]) c[0], w, 0.5);
            assertEquals((double) c[1], mix.tier(), 0.005);
            assertEquals(0.0, mix.pool());
            assertEquals((int) c[2], BalanceFinalRule.hitClicks(carbine, mix, null, refs, P));
        }
    }

    @Test
    void keysText() {
        var a = BalanceFinalRule.anchors("+3:1 0:2 -1:4 junk 2:x");
        assertEquals(3, a.size());
        assertEquals(2.0, BalanceFinalRule.ttkOf(0, P.anchors().size() > 0 ? new BalanceFinalRule.Params(a, 0.7, 900, 2, 0.3, 0.7, 1.4, 1.15, 4, 0.15, 1, -1, 0.5, 1, 1.15,
                java.util.Set.of(), 1, 1, Map.of()) : P), 1e-9);
        // between 0 and +3 by the logarithm: no anchors at +1 and +2
        var q = new BalanceFinalRule.Params(a, 0.1, 900, 2, 0.3, 0.7, 1.4, 1.15, 4, 0.15, 1, -1, 0.5, 1, 1.15, java.util.Set.of(), 1, 1, Map.of());
        assertEquals(Math.exp(Math.log(2) * 2 / 3 + Math.log(1) / 3), BalanceFinalRule.ttkOf(1, q), 1e-9);
        assertEquals(16.0, BalanceFinalRule.ttkOf(-3, q), 1e-9);
        assertEquals(P.anchors(), BalanceFinalRule.anchors(""));
        assertEquals(1.6, BalanceFinalRule.roles(BalanceFinalRule.Params.ROLES).get("smg"));
    }
}
