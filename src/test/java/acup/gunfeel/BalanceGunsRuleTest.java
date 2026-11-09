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
            if (list.stream().noneMatch(x -> x.name().equals(a))) list.add(new BalanceGunsRule.Ammo(ammo, click));
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
        // v3's 78, less Liberator's two, Coilgun's and Heretic DU (its gun line instead)
        assertEquals(74, t.pairs().values().stream().mapToInt(Map::size).sum());
    }

    @Test
    void ownersDecisions() throws IOException {
        BalanceGunsRule.Table t = table(new ArrayList<>());
        // Liberator and Coilgun not touched
        assertEquals("none", r("liberator", "g12_bp").from());
        assertEquals(1F, r("coilgun", "coil_ferrouranium").shot().mult());
        // Heretic H2: x0.2 on every ammo, one shot in 12 ticks, not auto, 24 rounds
        for (var a : guns().get("autoshotgun_heretic")) assertEquals(0.2F, r("autoshotgun_heretic", a.name()).shot().mult());
        var h = t.guns().get("autoshotgun_heretic");
        assertEquals(12, h.delay());
        assertEquals(false, h.auto());
        assertEquals(24, h.mag());
        // AM180 A2
        assertEquals(3.5F, r("am180", "p22_sp").shot().mult());
        assertEquals(3.5F, r("am180", "p22_fmj").shot().mult());
        assertEquals(3.11F, r("am180", "p22_ap").shot().mult());
        assertEquals(3, t.guns().get("am180").delay());
        assertEquals(60, t.guns().get("am180").mag());
        assertEquals(15, t.guns().get("amat_penance").delay());
        assertEquals(17, t.guns().get("amat_subtlety").delay());
        assertEquals(17, t.guns().get("mas36").delay());
        assertEquals(40F, t.guns().get("laser_pistol_morning_glory").dmg());
        // Tau as v3 has it
        assertEquals(new BalanceGunsRule.Shot(2F, 30F, 0F), r("tau", "tau_uranium").shot());
    }

    @Test
    void theRule() throws IOException {
        // the same family: FMJ is soft as JHP is; DU and AP pierce as SM does
        assertEquals("family r556_jhp", r("g3", "r556_fmj").from());
        assertEquals(0.853F, r("g3", "r556_fmj").shot().mult());
        assertEquals("family bmg50_sm", r("amat", "bmg50_du").from());
        // no soft pair of its gun: the nearest by the damage of a click (45 against HE 52.5 and SM 75)
        assertEquals("nearest bmg50_he", r("amat", "bmg50_jhp").from());
        // the pedestal's additions go with it
        assertEquals(new BalanceGunsRule.Shot(2F, 30F, 0F), r("laser_pistol_morning_glory", "capacitor_ir").shot());
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
        assertTrue(out.size() > 50);
    }
}
