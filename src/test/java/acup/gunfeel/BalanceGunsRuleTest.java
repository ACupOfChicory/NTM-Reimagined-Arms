package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

// The gun table of the mod jar against NTM's guns as the game has them (src/test/gundata): every line read, the
// owner's decisions in, and the rule for an ammo with no line of its own. The pairs the rule makes go to
// build/balance-guns-resolved.tsv (the stage's notes list them).
class BalanceGunsRuleTest {
    private static BalanceGunsRule.Table table(List<String> bad) throws IOException {
        try (InputStream in = BalanceGunsRuleTest.class.getResourceAsStream("/ntmgunfeel-balance-guns.tsv")) {
            return BalanceGunsRule.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList(), bad);
        }
    }

    /** every gun's ammo (config 0's receivers and the rest, once each) and NTM's damage of a click with it */
    private static Map<String, List<BalanceGunsRule.Ammo>> guns() throws IOException {
        Map<String, List<BalanceGunsRule.Ammo>> out = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(Path.of("src/test/gundata/guns-mc26.2-ntm1.0.3.tsv"));
        List<String> head = List.of(lines.get(0).split("\t"));
        for (String l : lines.subList(1, lines.size())) {
            String[] f = l.split("\t");
            String gun = f[0].replace("hbm:gun_", ""), ammo = f[head.indexOf("ammo")].replace("hbm:", "");
            ammo = ammo.startsWith("ammo_standard_") ? ammo.substring(14) : ammo.startsWith("ammo_") ? ammo.substring(5) : ammo;
            float click = Float.parseFloat(f[head.indexOf("baseDamage")]) * Float.parseFloat(f[head.indexOf("damageMult")])
                    * (Integer.parseInt(f[head.indexOf("projectilesMin")]) + Integer.parseInt(f[head.indexOf("projectilesMax")])) / 2F
                    * Float.parseFloat(f[head.indexOf("split")]);
            List<BalanceGunsRule.Ammo> list = out.computeIfAbsent(gun, g -> new ArrayList<>());
            String a = ammo;
            float pellets = (Integer.parseInt(f[head.indexOf("projectilesMin")]) + Integer.parseInt(f[head.indexOf("projectilesMax")])) / 2F;
            if (list.stream().noneMatch(x -> x.name().equals(a))) list.add(new BalanceGunsRule.Ammo(ammo, click, pellets));
        }
        return out;
    }

    private static BalanceGunsRule.Resolved r(String gun, String ammo) throws IOException {
        return BalanceGunsRule.resolve(table(new ArrayList<>()), gun, guns().get(gun)).get(ammo);
    }

    @Test
    void everyLineIsReadAndNamesAGunsAmmo() throws IOException {
        List<String> bad = new ArrayList<>();
        BalanceGunsRule.Table t = table(bad);
        assertTrue(bad.isEmpty(), "lines not read: " + bad);
        Map<String, List<BalanceGunsRule.Ammo>> guns = guns();
        for (var e : t.pairs().entrySet()) {
            assertTrue(guns.containsKey(e.getKey()), "no gun " + e.getKey());
            for (String ammo : e.getValue().keySet())
                assertTrue(guns.get(e.getKey()).stream().anyMatch(a -> a.name().equals(ammo)), e.getKey() + " takes no " + ammo);
        }
        for (String g : t.guns().keySet()) assertTrue(guns.containsKey(g), "no gun " + g);
        // v4: every ammo of a gun in the table has its own pair, none made up (the coordinator, 2026-11-10)
        assertEquals(256, t.pairs().values().stream().mapToInt(Map::size).sum());
        for (String g : t.pairs().keySet())
            for (var a : guns.get(g)) assertEquals("table", r(g, a.name()).from(), g + " " + a.name());
    }

    @Test
    void ownersDecisions() throws IOException {
        BalanceGunsRule.Table t = table(new ArrayList<>());
        // Liberator and Coilgun not touched
        assertEquals("none", r("liberator", "g12_bp").from());
        assertEquals(1F, r("coilgun", "coil_ferrouranium").shot().mult());
        // Heretic H2: DU x0.2, each other ammo its own; one shot in 12 ticks, not auto, 24 rounds, no mult of the gun
        assertEquals(0.2F, r("autoshotgun_heretic", "g10_du").shot().mult());
        assertEquals(0.484F, r("autoshotgun_heretic", "g10_shrapnel").shot().mult());
        assertEquals(0.156F, r("autoshotgun_heretic", "g10_slug").shot().mult());
        var h = t.guns().get("autoshotgun_heretic");
        assertTrue(Float.isNaN(h.mult()));
        assertEquals(12, h.delay());
        assertEquals(false, h.auto());
        assertEquals(24, h.mag());
        // AM180 A2
        assertEquals(2.8F, r("am180", "p22_sp").shot().mult());
        assertEquals(2.66F, r("am180", "p22_fmj").shot().mult());
        assertEquals(2.97F, r("am180", "p22_ap").shot().mult());
        assertEquals(3, t.guns().get("am180").delay());
        assertEquals(60, t.guns().get("am180").mag());
        assertEquals(15, t.guns().get("amat_penance").delay());
        assertEquals(17, t.guns().get("amat_subtlety").delay());
        assertEquals(17, t.guns().get("mas36").delay());
        assertEquals(40F, t.guns().get("laser_pistol_morning_glory").dmg());
        // Tau as v3 has it
        assertEquals(new BalanceGunsRule.Shot(2F, 30F, 0F), r("tau", "tau_uranium").shot());
    }

    // the rule for an ammo of no line, on a table of a few pairs (the shipped one has every pair of its guns)
    private static final List<String> FEW = List.of("g3\tr556_jhp\t0.853\t0\t0", "amat\tbmg50_sm\t0.853\t0\t0",
            "amat\tbmg50_he\t0.762\t0\t0", "double_barrel\tg10_du\t0.762\t0\t0", "autoshotgun\tg12_explosive\t0.175\t0\t0",
            "autoshotgun_shredder\tg12_explosive\t0.386\t0\t0", "spas12\tg12_flechette\t1.07\t0\t0",
            "spas12\tg12_explosive\t0.386\t0\t0", "maresleg\tg12_bp\t4.17\t0\t0", "maresleg\tg12_flechette\t2.37\t0\t0",
            "laser_pistol_morning_glory\tcapacitor\t2\t30\t0");

    private static BalanceGunsRule.Resolved few(String gun, String ammo) throws IOException {
        List<String> bad = new ArrayList<>();
        var t = BalanceGunsRule.parse(FEW, bad);
        assertTrue(bad.isEmpty(), "lines not read: " + bad);
        return BalanceGunsRule.resolve(t, gun, guns().get(gun)).get(ammo);
    }

    @Test
    void theRule() throws IOException {
        // the same family: FMJ is soft as JHP is; DU and AP pierce as SM does
        assertEquals("family r556_jhp", few("g3", "r556_fmj").from());
        assertEquals(0.853F, few("g3", "r556_fmj").shot().mult());
        assertEquals("family bmg50_sm", few("amat", "bmg50_du").from());
        // no soft pair of its gun: no mult from another family (SM, HE), NTM's
        assertEquals("none", few("amat", "bmg50_jhp").from());
        assertEquals("none", few("double_barrel", "g10_shrapnel").from());
        assertEquals("none", few("double_barrel", "g10_explosive").from());
        // a blast's mult stays with blasts: Autoshotgun's only pair is its explosive one, the rest NTM's
        assertEquals("none", few("autoshotgun", "g12_slug").from());
        assertEquals("none", few("autoshotgun", "g12").from());
        assertEquals("none", few("autoshotgun_shredder", "g12_phosphorus").from());
        assertEquals("none", few("spas12", "g12_phosphorus").from());
        // buckshot and a slug or magnum shot do not share a mult (Maresleg's buckshot x4.17)
        assertEquals("family g12_bp", few("maresleg", "g12").from());
        assertEquals("none", few("maresleg", "g12_slug").from());
        assertEquals("none", few("maresleg", "g12_magnum").from());
        assertEquals("none", few("maresleg", "g12_bp_slug").from());
        // the pedestal's additions go with it
        assertEquals(new BalanceGunsRule.Shot(2F, 30F, 0F), few("laser_pistol_morning_glory", "capacitor_ir").shot());
        // a gun of no line: NTM's
        assertEquals(BalanceGunsRule.Shot.NONE, r("panzerschreck", guns().get("panzerschreck").get(0).name()).shot());
        assertEquals("piercing", BalanceGunsRule.family("g12_flechette"));
        assertEquals("blast", BalanceGunsRule.family("g12_explosive"));
        assertEquals("soft", BalanceGunsRule.family("m44_express"));
    }

    @Test
    void limits() {
        List<String> bad = new ArrayList<>();
        var t = BalanceGunsRule.parse(List.of("hangman\tm44_express\t5\t80\t0.9", "henry\tm44_express\t5\t80\t0.9", "x\ty", "am180\t*\t-\t0\t0\t2\tdelay=0"), bad);
        assertEquals(new BalanceGunsRule.Shot(2F, 50F, 0.5F), t.pairs().get("hangman").get("m44_express"));
        assertEquals(new BalanceGunsRule.Shot(5F, 50F, 0.5F), t.pairs().get("henry").get("m44_express"));
        assertEquals(2, bad.size());
    }

    @Test
    void floors() throws IOException {
        List<String> bad = new ArrayList<>();
        var t = BalanceGunsRule.parse(List.of("m2\tbmg50_ap\t0.345\t0\t0\t4\tfloor=2.3", "m2\tbmg50_jhp\t0.68\t0\t0\t4\tfloor=0.66 from=6",
                "m2\tbmg50_sp\t0.955\t0\t0\t4\tfloor=0", "m2\tbmg50_fmj\t0.853\t0\t0\t4\tfloor=99", "m2\tbmg50_he\t0.5\t0\t0\t4\tdelay=2 a note",
                "uzi\t*\t1.2\t0\t0\t4", "uzi\tp9_ap\t0.9\t0\t0\t4\tfloor=1.5"), bad);
        assertEquals(new BalanceGunsRule.Shot(0.345F, 0F, 0F, 2.3F, 4), t.pairs().get("m2").get("bmg50_ap"));
        assertEquals(new BalanceGunsRule.Shot(0.68F, 0F, 0F, 0.66F, 6), t.pairs().get("m2").get("bmg50_jhp"));
        assertEquals(BalanceGunsRule.MOST_FLOOR, t.pairs().get("m2").get("bmg50_fmj").floor());
        // floor=0 is not read; other words in a pair's column 7 are left alone, as before floor=
        assertEquals(1, bad.size(), bad.toString());
        assertEquals(1F, t.pairs().get("m2").get("bmg50_he").floor());
        assertTrue(!new BalanceGunsRule.Shot(1F, 0F, 0F, 2F, 4).none());
        // a gun's own line over its pairs keeps a pair's floor
        var r = BalanceGunsRule.resolve(t, "uzi", guns().get("uzi")).get("p9_ap").shot();
        assertEquals(1.2F, r.mult());
        assertEquals(1.5F, r.floor());
        // the shipped table: the Ma Deuce's piercing rounds keep more against armour, its soft ones less from tier 6
        var shipped = table(new ArrayList<>());
        assertTrue(shipped.pairs().get("m2").get("bmg50_ap").floor() > 1.5F);
        assertEquals(6, shipped.pairs().get("m2").get("bmg50_jhp").from());
        assertTrue(shipped.pairs().get("m2").get("bmg50_jhp").floor() < 1F);
        assertEquals(0.2F, shipped.pairs().get("pepperbox").get("stone").floor(), 1e-6F);
    }

    @Test
    void writeTheMadeUpPairs() throws IOException {
        BalanceGunsRule.Table t = table(new ArrayList<>());
        List<String> out = new ArrayList<>();
        out.add("gun\tammo\tmult\tneg\tpen\tfrom");
        for (var e : guns().entrySet()) {
            if (!t.pairs().containsKey(e.getKey()) && !t.guns().containsKey(e.getKey())) continue;
            for (var r : BalanceGunsRule.resolve(t, e.getKey(), e.getValue()).entrySet()) {
                if (r.getValue().from().equals("table")) continue;
                var s = r.getValue().shot();
                out.add(e.getKey() + "\t" + r.getKey() + "\t" + s.mult() + "\t" + s.neg() + "\t" + s.pen() + "\t" + r.getValue().from());
            }
        }
        Files.createDirectories(Path.of("build"));
        Files.write(Path.of("build/balance-guns-resolved.tsv"), out);
        assertEquals(1, out.size(), "made up: " + out);
    }
}
