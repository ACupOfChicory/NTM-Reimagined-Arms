package acup.gunfeel;

// port: copy of 26.2's AmmoStatsTest.java (0762086); only DATA changed (the port's tests run in port/1.7.10). The
// formulas are NTM 1.7.10's too (Lego.calcDamage/getStandardWearDamage, DamageResistanceHandler.calculateDamage,
// Lego.doStandardFire's split, EntityBulletBaseMK4's damageMult, BulletConfig's headshotMult over 1); the numbers are
// 26.2's dump of NTM 1.0.3 and pin, as the menu works them out the same way.

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

// The ammo menu numbers (AmmoStats): NTM's damage, projectile, wear and armour formulas on hand-worked cases, then every
// gun with every ammo it takes as NTM has them (src/test/gundata, written by ./gradlew runDumpBlocks -PnoAssets).
class AmmoStatsTest {
    // port: the root's dump, wherever the tests run (26.2: relative to the root)
    static final Path DATA = PinnedPortTest.REPO.resolve("src/test/gundata");

    record Row(Map<String, String> f) {
        String s(String k) {
            return f.get(k);
        }

        float n(String k) {
            return Float.parseFloat(f.get(k));
        }

        int i(String k) {
            return Integer.parseInt(f.get(k));
        }

        AmmoStats stats() {
            return AmmoStats.of(n("baseDamage"), 1F, n("split"), n("damageMult"), i("projectilesMin"), i("projectilesMax"), n("headshotMult"),
                    n("thresholdNegation"), n("piercing"));
        }
    }

    static List<Row> rows() throws IOException {
        Path file;
        try (Stream<Path> s = Files.list(DATA)) {
            file = s.filter(p -> p.getFileName().toString().endsWith(".tsv")).sorted().reduce((a, b) -> b).orElseThrow();
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String[] head = lines.get(0).split("\t");
        List<Row> out = new ArrayList<>();
        for (String l : lines.subList(1, lines.size())) {
            String[] v = l.split("\t", -1);
            Map<String, String> m = new HashMap<>();
            for (int k = 0; k < head.length; k++) m.put(head[k], v[k]);
            out.add(new Row(m));
        }
        return out;
    }

    @Test
    void armourAsNtm() {
        // DamageResistanceHandler.calculateDamage: threshold off first, then the resistance, piercing cuts the resistance
        assertEquals(2.5F, AmmoStats.throughArmor(10, 5, .5F, 0, 0), 1e-6);
        assertEquals(5F, AmmoStats.throughArmor(10, 5, .5F, 5, 0), 1e-6);
        assertEquals(5F, AmmoStats.throughArmor(10, 5, .5F, 0, 1), 1e-6);
        assertEquals(3.75F, AmmoStats.throughArmor(10, 5, .5F, 0, .5F), 1e-6);
        assertEquals(0F, AmmoStats.throughArmor(5, 5, .5F, 0, 0), 1e-6); // the threshold eats it all
        assertEquals(0F, AmmoStats.throughArmor(4, 5, 0, 0, 0), 1e-6);
        assertEquals(10F, AmmoStats.throughArmor(10, 0, 0, 3, .4F), 1e-6); // nothing to pierce
        assertEquals(10F, AmmoStats.throughArmor(10, 5, .5F, 9, 1.5F), 1e-6); // over-piercing never adds damage
        // the reference armour stops a weak round outright
        assertEquals(0F, AmmoStats.of(2, 1, 1, 1, 1, 1, 1, 0, 0).perShotArmored(), 1e-6);
    }

    @Test
    void projectilesAsNtm() {
        assertEquals(1F, AmmoStats.projectiles(1, 1, 1));
        assertEquals(8F, AmmoStats.projectiles(8, 8, 1));
        assertEquals(4F, AmmoStats.projectiles(3, 5, 1));
        assertEquals(6F, AmmoStats.projectiles(3, 3, 2));
        assertEquals(3.5F, AmmoStats.projectiles(3, 4, 1));
        // cut to whole projectiles per shot, as (int) (count * split) in Lego.doStandardFire
        assertEquals((1 + 3 + 4) / 3F, AmmoStats.projectiles(1, 3, 1.5F), 1e-6);
        assertEquals(0F, AmmoStats.projectiles(1, 1, .5F));
        assertEquals(2F, AmmoStats.projectiles(2, 1, 1)); // max under min: min
    }

    @Test
    void wearAsNtm() {
        assertEquals(1F, AmmoStats.wear(0, 100));
        assertEquals(1F, AmmoStats.wear(74, 100));
        assertEquals(.9F, AmmoStats.wear(80, 100), 1e-6);
        assertEquals(.5F, AmmoStats.wear(100, 100), 1e-6);
        assertEquals(1F, AmmoStats.wear(50, 0)); // no durability: no wear
    }

    @Test
    void headAndShot() {
        AmmoStats s = AmmoStats.of(10, 1, 1, 1.5F, 8, 8, 1.5F, 0, 0);
        assertEquals(15F, s.damage(), 1e-6);
        assertEquals(120F, s.perShot(), 1e-5);
        assertEquals(22.5F, s.headshot(), 1e-5);
        assertTrue(s.shotgun());
        // headshotMult 1 or under: a head hit is a body hit (the bonus only applies over 1)
        assertEquals(10F, AmmoStats.of(10, 1, 1, 1, 1, 1, .8F, 0, 0).headshot(), 1e-6);
        assertEquals(5F, AmmoStats.of(10, .5F, 1, 1, 1, 1, 1, 0, 0).damage(), 1e-6);
    }

    @Test
    void text() {
        assertEquals("12", AmmoStats.fmt(12F));
        assertEquals("12.5", AmmoStats.fmt(12.5F));
        assertEquals("12.3", AmmoStats.fmt(12.34F));
        assertEquals("0", AmmoStats.fmt(0.04F));
        assertEquals("+3", AmmoStats.delta(15, 12));
        assertEquals("−1.5", AmmoStats.delta(10.5F, 12));
        assertEquals("", AmmoStats.delta(12.01F, 12));
    }

    // every gun and ammo in the dump: the numbers come out finite and match NTM's own tooltip (base damage × damageMult)
    @Test
    void everyGunAndAmmo() throws IOException {
        List<Row> rows = rows();
        assertTrue(rows.size() > 100, "the gun dump looks short: " + rows.size());
        List<String> bad = new ArrayList<>();
        for (Row r : rows) {
            AmmoStats s = r.stats();
            String what = r.s("gun") + " " + r.s("ammo");
            for (float v : new float[] {s.damage(), s.perShot(), s.headshot(), s.perShotArmored()})
                if (!Float.isFinite(v) || v < 0) bad.add(what + " gives " + v);
            assertEquals(r.n("baseDamage") * r.n("damageMult"), s.damage(), 1e-4, what);
            assertTrue(s.perShotArmored() <= s.perShot() + 1e-4, what + ": armour adds damage");
            assertFalse(s.headshot() < s.damage() - 1e-4, what + ": a head hit below a body hit");
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    // what the menu shows for every gun and ammo, pinned (PinnedTest.pin): an NTM update or a formula change shows here
    @Test
    void menuNumbersPinned() throws IOException {
        List<String> out = new ArrayList<>();
        for (Row r : rows()) {
            AmmoStats s = r.stats();
            out.add(r.s("gun") + " " + r.s("config") + "/" + r.s("receiver") + " " + r.s("ammo") + "  damage "
                    + AmmoStats.fmt(s.damage()) + (s.shotgun() ? " x" + AmmoStats.fmt(s.projectiles()) : "") + "  head "
                    + AmmoStats.fmt(s.headshot()) + "  armour " + AmmoStats.fmt(s.perShotArmored()));
        }
        PinnedTest.pin("ammo", out);
    }
}
