package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

// The bullets' rule file as text, without the game: what a line says, and which of the lines counts.
class BulletRuleTextTest {
    @Test
    void aLineIsItsKindSelectorsOptionsAndWords() {
        var r = BulletRuleText.parse(List.of("  Break minecraft:melon,#minecraft:leaves DAMAGE 2 drop pierce=0.5 # a comment", "# only a comment", "",
                "whizz sound=none radius=4")).toArray(BulletRuleText.Rule[]::new);
        assertEquals(2, r.length);
        assertEquals("break", r[0].kind());
        assertEquals(List.of("minecraft:melon", "#minecraft:leaves"), r[0].selectors());
        assertArrayEquals(new String[] {"damage", "2", "drop"}, r[0].words());
        assertEquals("0.5", r[0].opts().get("pierce"));
        // whizz and fly take no selectors
        assertEquals(List.of(), r[1].selectors());
        assertEquals("4", r[1].opts().get("radius"));
    }

    @Test
    void breakRules() {
        var b = BulletRuleText.breakOf(BulletRuleText.parse(List.of("break x damage 550 min=20")).get(0));
        assertEquals(3, b.mode());
        assertEquals(550F, b.amount());
        assertEquals(20F, b.min());
        assertFalse(b.pass());
        // never, with nothing to pierce or fly through: no rule
        assertNull(BulletRuleText.breakOf(BulletRuleText.parse(List.of("break x never")).get(0)));
        assertNull(BulletRuleText.breakOf(null));
        var through = BulletRuleText.breakOf(BulletRuleText.parse(List.of("break x never through")).get(0));
        assertTrue(through.through() && through.pass());
        var door = BulletRuleText.breakOf(BulletRuleText.parse(List.of("break x hits 3 pierce=2 keep=0.5")).get(0));
        // 20 damage, hardness 1, half a block: 20 * 0.5^0.5 - 2 * 1 * 0.5
        assertEquals((float) (20 * Math.pow(0.5, 0.5) - 1.0), door.pierced(20F, 1F, 0.5), 1e-6F);
        assertEquals(-1F, door.pierced(1.5F, 1F, 1.0));
    }

    @Test
    void wearOfOneHit() {
        var concrete = BulletRuleText.breakOf(BulletRuleText.parse(List.of("break x damage 550 min=20")).get(0));
        assertTrue(concrete.worn());
        assertTrue(concrete.tooWeak(20F));
        assertFalse(concrete.tooWeak(21F));
        // only the damage above min counts, against the amount times the hardness
        assertEquals(5F, concrete.wear(25F));
        assertEquals(990F, concrete.need(1.8F), 1e-3F);
        var hits = BulletRuleText.breakOf(BulletRuleText.parse(List.of("break x hits 4")).get(0));
        assertEquals(1F, hits.wear(100F));
        assertEquals(4F, hits.need(50F));
        assertFalse(hits.tooWeak(0.1F));
        assertFalse(BulletRuleText.breakOf(BulletRuleText.parse(List.of("break x instant")).get(0)).worn());
        assertEquals(5, BulletRuleText.Break.cracks(2F, 4F));
        assertEquals(9, BulletRuleText.Break.cracks(3.99F, 4F));
    }

    @Test
    void theLastMatchingLineCounts() {
        var all = BulletRuleText.parse(List.of("hole a glass hitbox", "hole b plain", "hole a pixels", "ricochet a no", "path a through"));
        assertArrayEquals(new String[] {"glass", "pixels"}, BulletRuleText.hole(all, r -> r.selectors().contains("a")));
        assertArrayEquals(new String[] {"plain", null}, BulletRuleText.hole(all, r -> r.selectors().contains("b")));
        assertFalse(BulletRuleText.ricochet(BulletRuleText.last(all, "ricochet", r -> r.selectors().contains("a"))));
        assertTrue(BulletRuleText.ricochet(BulletRuleText.last(all, "ricochet", r -> r.selectors().contains("b"))));
        assertTrue(BulletRuleText.anyThrough(all));
        assertFalse(BulletRuleText.anyThrough(all.subList(0, 4)));
    }

    @Test
    void effectsAndSounds() {
        var e = BulletRuleText.effect(BulletRuleText.parse(List.of("hit @metal particles=block*4,minecraft:crit sound=hbm:metal_impact/0.6 to=all")).get(0));
        assertEquals(List.of(new BulletRuleText.Particle("block", 4), new BulletRuleText.Particle("minecraft:crit", 1)), e.particles());
        assertEquals(new BulletRuleText.Sound("hbm:metal_impact", 0.6F, 1F), e.sound());
        assertTrue(e.toAll());
        assertEquals(16F, e.range());
        assertTrue(BulletRuleText.NO_EFFECT.empty());
        var w = BulletRuleText.whizz(BulletRuleText.parse(List.of("whizz sound=none radius=4")).get(0));
        assertNull(w.sound());
        assertEquals(4F, w.radius());
        assertEquals(1.2F, w.crackRadius());
        assertEquals(0F, BulletRuleText.whizz(null).radius());
        assertNull(BulletRuleText.fly(null));
    }

    @Test
    void materialLines() {
        var m = BulletRuleText.materialRules(BulletRuleText.parse(List.of("material minecraft:ice glass", "material x nonsense", "material y")));
        assertEquals(1, m.size());
        assertEquals(Materials.Material.GLASS, m.get(0).material());
    }
}
