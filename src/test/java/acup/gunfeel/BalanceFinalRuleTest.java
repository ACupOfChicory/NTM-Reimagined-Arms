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

    // X922 (Fable 2026-11-12, notes/balance/fable/model, gen_tables.py) by the keys' values before Fable №2: ladder H,
    // roles, slug, pools 45/40 and the magazine's rule, the model's P
    @Test
    void reproducesX922ByTheOldKeys() throws IOException {
        reproduces("final-table-fable.tsv", BalanceFinalRule.Params.X922, BalanceFinalRule.MODEL_SETS_X922);
    }

    // the same with the magazine's rule off (fm.use('fable', edge=False))
    @Test
    void reproducesX922WithoutTheMagazinesRule() throws IOException {
        reproduces("final-table-fable-noedge.tsv", BalanceFinalRule.Params.X922.withMag(BalanceFinalRule.MagRule.OFF), BalanceFinalRule.MODEL_SETS_X922);
    }

    // X912 is the keys' old values: its ladder and roles, no slug, no magazine's rule, pools 40/45
    @Test
    void reproducesX912ByTheOldKeys() throws IOException {
        reproduces("final-table-x912.tsv", BalanceFinalRule.Params.X912, BalanceFinalRule.modelSets(40, 45));
    }

    /** the pairs with P of a column of a pairs' table of Fable's ({@code gun ammo ... P_x922 P_est}) */
    private static Map<String, BalanceFinalRule.Pair> withP(String file, int col) throws IOException {
        var pairs = new java.util.LinkedHashMap<>(pairs());
        for (String l : Files.readAllLines(Path.of("src/test/resources/balance/" + file), StandardCharsets.UTF_8)) {
            if (l.startsWith("#") || l.startsWith("gun\t") || l.isBlank()) continue;
            String[] c = l.split("\t");
            var pr = pairs.get(c[0] + "." + c[1]);
            assertTrue(pr != null, c[0] + "." + c[1]);
            double p = Double.parseDouble(c[col]);
            pairs.put(pr.id(), new BalanceFinalRule.Pair(pr.gun(), pr.ammo(), pr.tier(), pr.ped(), p, pr.delay(), pr.auto(), pr.mag(), pr.reload(), pr.pellets(),
                    pr.roundsPerClick(), pr.hands(), pr.pen(), pr.cat(), pr.rawDamage(), pr.explosive(), pr.role()));
        }
        return pairs;
    }

    /** the solver on {@code pairs} with {@code p} against a table "gun ammo tier role sets…" of hit clicks: every cell exact */
    private static void cellForCell(Map<String, BalanceFinalRule.Pair> pairs, String table, BalanceFinalRule.Params p) throws IOException {
        var refs = BalanceFinalRule.refs(pairs.values());
        String[] cols = null;
        int cells = 0;
        List<String> off = new ArrayList<>();
        for (String l : Files.readAllLines(Path.of("src/test/resources/balance/" + table), StandardCharsets.UTF_8)) {
            if (l.startsWith("#") || l.isBlank()) continue;
            String[] c = l.split("\t");
            if (c[0].equals("gun")) {
                cols = c;
                continue;
            }
            var pr = pairs.get(c[0] + "." + c[1]);
            assertTrue(pr != null, c[0] + "." + c[1]);
            assertEquals(c[3], BalanceFinalRule.roleOf(pr, p), pr.id());
            for (int i = 4; i < c.length; i++) {
                double want = Double.parseDouble(c[i]);
                double got = BalanceFinalRule.HP / BalanceFinalRule.clickDamage(pr, SETS.get(cols[i]), FAU, refs, p);
                cells++;
                if (Math.abs(want - got) > 1e-3) off.add(pr.id() + "/" + cols[i] + ": " + want + " vs " + got);
            }
        }
        assertEquals(251 * 21, cells);
        assertTrue(off.isEmpty(), off.size() + " cells off: " + off.subList(0, Math.min(12, off.size())));
    }

    // Fable №2 (w-fable/fable2_table.py): the rules of the review with Fable's estimate of P, cell for cell
    @Test
    void reproducesFable2ByItsEstimateOfP() throws IOException {
        cellForCell(withP("fable2-pairs-est.tsv", 6), "fable2-table-est.tsv", P);
    }

    /** the pairs with the stand's P (balance.final.hits = stand) */
    private static Map<String, BalanceFinalRule.Pair> standPairs() throws IOException {
        List<String> bad = new ArrayList<>();
        var out = BalanceFinalRule.pairs(lines("ntmgunfeel-balance-final-pairs.tsv"), Map.of(), bad, true);
        assertTrue(bad.isEmpty(), bad.toString());
        return out;
    }

    private static final String[] BY_ARMOR = {"none", "steel", "diesel", "sec", "t51", "hev", "bj", "trench", "cmb", "fau", "dns"};

    // Fable №2 (acceptance of stage 1): two clicks at least, DNS never quicker than FAU, the target never down along the
    // armour's order, a fading role's target never down from d 0 to −3, the magazine's rule never below 0.65 of the target
    @Test
    void fable2Invariants() throws IOException {
        var pairs = standPairs();
        var refs = BalanceFinalRule.refs(pairs.values());
        int moved = 0;
        for (var pr : pairs.values()) {
            double prev = 0;
            for (String k : BY_ARMOR) {
                double t = BalanceFinalRule.target(pr, SETS.get(k), FAU, refs, P);
                assertTrue(t >= prev - 1e-9, pr.id() + ": the target down on " + k + " (" + t + " < " + prev + ")");
                prev = t;
            }
            for (var a : SETS.values()) {
                double tot = BalanceFinalRule.HP + a.pool(), tt = BalanceFinalRule.target(pr, a, FAU, refs, P);
                int nh = BalanceFinalRule.hitClicks(pr, a, FAU, refs, P);
                assertTrue(nh >= 2, pr.id() + " on " + a.kind() + ": " + nh);
                assertTrue(BalanceFinalRule.HP / BalanceFinalRule.clickDamage(pr, a, FAU, refs, P) >= 2 - 1e-9, pr.id() + " on " + a.kind());
                int plain = BalanceFinalRule.closest(pr, tot, tt);
                if (nh != plain) {
                    moved++;
                    double[] r = BalanceFinalRule.realTtk(BalanceFinalRule.HP / nh, pr, tot);
                    assertTrue(Math.max(r[0], P.tmin()) >= 0.65 * tt - 1e-9, pr.id() + " on " + a.kind() + ": the magazine's rule " + r[0] + " of " + tt);
                }
            }
            double dns = BalanceFinalRule.realTtk(BalanceFinalRule.clickDamage(pr, SETS.get("dns"), FAU, refs, P), pr, 20 + SETS.get("dns").pool())[0];
            double fau = BalanceFinalRule.realTtk(BalanceFinalRule.clickDamage(pr, FAU, null, refs, P), pr, 20 + FAU.pool())[0];
            assertTrue(dns >= fau - 1e-9, pr.id() + ": DNS " + dns + " < FAU " + fau);
            // the fade: a pair's role share from d 0 down to −3 never down
            double was = 0;
            for (double d = 0; d >= -3; d -= 0.05) {
                double share = BalanceFinalRule.roleShare(pr, d, P);
                assertTrue(share >= was - 1e-12, pr.id() + " at " + d);
                was = share;
            }
        }
        assertTrue(moved > 0);
        // the shotgun at 0.35 on its own tier, 0.675 a tier above, 1 two and more
        var maresleg = pairs.get("maresleg.g12_bp");
        assertEquals(0.35, BalanceFinalRule.roleShare(maresleg, 0, P), 1e-12);
        assertEquals(0.675, BalanceFinalRule.roleShare(maresleg, -1, P), 1e-12);
        assertEquals(1.0, BalanceFinalRule.roleShare(maresleg, -2.5, P), 1e-12);
        // X922: no fade, 0.75 on every tier
        assertEquals(0.75, BalanceFinalRule.roleShare(maresleg, -1, BalanceFinalRule.Params.X922), 1e-12);
        // the Shredder a shotgun (its slug and explosive shell a slug); X922: a beam
        assertEquals("shotgun", BalanceFinalRule.roleOf(pairs.get("autoshotgun_shredder.g12"), P));
        assertEquals("slug", BalanceFinalRule.roleOf(pairs.get("autoshotgun_shredder.g12_slug"), P));
        assertEquals("beam", BalanceFinalRule.roleOf(pairs.get("autoshotgun_shredder.g12"), BalanceFinalRule.Params.X922));
    }

    // Fable №2 (2.4): the acid takes a set's tier down by up to one, in full to tier 5, nothing from 8
    @Test
    void acid() {
        var a = BalanceFinalRule.Acid.parse(BalanceFinal.ACID, BalanceFinalRule.Acid.OFF);
        assertEquals(BalanceFinalRule.Acid.DEFAULT, a);
        assertEquals(3.0, a.tierOf(4, 0.75), 1e-9);
        assertEquals(3.5, a.tierOf(4, 0.375), 1e-9);
        assertEquals(4.0, a.tierOf(5, 0.75), 1e-9);
        assertEquals(6 - 0.65, a.tierOf(6, 0.75), 1e-9);
        assertEquals(7 - 0.35, a.tierOf(7, 0.75), 1e-9);
        assertEquals(8.0, a.tierOf(8, 0.75), 1e-9);
        assertEquals(9.0, a.tierOf(9, 0.75), 1e-9);
        assertEquals(4.0, a.tierOf(4, 0), 1e-9);
        // bare and nearly bare: not below 0
        assertEquals(0.0, a.tierOf(0, 0.75), 1e-9);
        assertEquals(0.0, a.tierOf(0.5, 0.75), 1e-9);
        assertEquals(4.0, BalanceFinalRule.Acid.parse("off", a).tierOf(4, 0.75), 1e-9);
        assertEquals(2.5, BalanceFinalRule.Acid.parse("tiers=1.5 junk", BalanceFinalRule.Acid.DEFAULT).tierOf(4, 0.75), 1e-9);
    }

    // Fable №2 with the stand's P (notes/balance/fable2/model/gen_table.py): the game's table, cell for cell
    @Test
    void reproducesFable2ByTheStandsP() throws IOException {
        cellForCell(standPairs(), "fable2-table-stand.tsv", P);
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

    // Fable №3 (owner's answer 2): no cell in one click — every pair on every set, all its projectiles in the target, at 4
    // to 128 blocks with the range rule as the game takes it (an explosive shotgun shell's direct hit by the shell's
    // line, a gun of a line of its own by it: the Shredder's beam, BalanceFinal.range). The click before the damage cap must not kill on the body; on the head (x1.75) it may
    // only where the cap holds it, and the cap's shares stay below the whole health
    @Test
    void noCellInOneClick() throws IOException {
        var pairs = pairs();
        var refs = BalanceFinalRule.refs(pairs.values());
        var range = new BalanceRangeRule.Rule(BalanceRangeRule.lines(BalanceRangeRule.LINES), 8, Map.copyOf(BalanceFinalRule.roles(BalanceRangeRule.HOME)),
                BalanceRangeRule.lineOfs(BalanceRangeRule.LINE_OF));
        var cap = DamageCapRule.Limits.DEFAULT;
        assertTrue(cap.click() < 1F && cap.head() < 1F, "the damage cap's shares");
        int cells = 0, headByCap = 0;
        List<String> body = new ArrayList<>();
        for (var pr : pairs.values()) {
            String role = BalanceFinalRule.roleOf(pr, P);
            boolean shell = pr.explosive() && (role.equals("shotgun") || role.equals("slug"));
            for (var a : SETS.values()) {
                double click = BalanceFinalRule.clickDamage(pr, a, a == FAU ? null : FAU, refs, P);
                double hp = BalanceFinalRule.HP + a.pool();
                for (int d : new int[] {4, 8, 16, 32, 64, 128}) {
                    double m = BalanceRangeRule.hit(range, pr.gun(), role, shell, d);
                    if (click * m >= hp - 1e-9) body.add(pr.id() + " on " + a.kind() + " at " + d + ": " + click * m);
                    double head = pr.explosive() ? click * m : click * m * 1.75;
                    if (head >= hp - 1e-9) headByCap++;
                    assertTrue(Math.ceil(hp / Math.min(cap.head() * BalanceFinalRule.HP, head) - 1e-9) >= 2, pr.id() + " on " + a.kind() + " at " + d + ": head in one click");
                    assertTrue(!BalanceFinalRule.headOneShot(pr, a.hours(), P), pr.id());
                    cells++;
                }
            }
        }
        assertTrue(body.isEmpty(), body.size() + " cells in one click before the cap: " + body.subList(0, Math.min(5, body.size())));
        assertEquals(251 * 21 * 6, cells);
        System.out.println("noCellInOneClick: " + cells + " cells, on the head held by the cap " + headByCap);
    }

    // the magazine's rule (Fable 10.3, X922): G3 on HEV 26 rounds of 30, the heavy revolver 5 or 6 of 6
    @Test
    void magazinesRule() throws IOException {
        var pairs = pairs();
        var refs = BalanceFinalRule.refs(pairs.values());
        var hev = BalanceFinalRule.MODEL_SETS_X922.get("hev");
        var x922 = BalanceFinalRule.Params.X922;
        int g3 = BalanceFinalRule.hitClicks(pairs.get("g3.r556_fmj"), hev, FAU, refs, x922);
        double[] t = BalanceFinalRule.realTtk(BalanceFinalRule.HP / g3, pairs.get("g3.r556_fmj"), 20);
        assertEquals(26, (int) t[1]);
        int rev = BalanceFinalRule.hitClicks(pairs.get("heavy_revolver.m44_fmj"), hev, FAU, refs, x922);
        double[] r = BalanceFinalRule.realTtk(BalanceFinalRule.HP / rev, pairs.get("heavy_revolver.m44_fmj"), 20);
        assertTrue(r[1] >= 5 && r[1] <= 6, "heavy revolver " + r[1]);
        var m = BalanceFinalRule.MagRule.parse("reserve=0.3 junk small=2", BalanceFinalRule.MagRule.DEFAULT);
        assertEquals(0.3, m.reserve());
        assertEquals(2, m.small());
        assertEquals(0.65, m.min());
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
