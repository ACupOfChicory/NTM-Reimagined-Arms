package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import acup.gunfeel.GunWeight.Weight;

/** The guns' weight classes as the file has them, and what slows. */
class GunWeightTest {
    @Test
    void theFileHasEveryClassedGun() {
        Map<Weight, Long> n = GunWeight.all().values().stream().collect(Collectors.groupingBy(GunWeight.Gun::weight, Collectors.counting()));
        assertEquals(28L, n.get(Weight.LIGHT));
        // NTM's 23 and the GL-54
        assertEquals(24L, n.get(Weight.MEDIUM));
        assertEquals(19L, n.get(Weight.HEAVY));
        assertEquals(9L, n.get(Weight.SUPER_HEAVY));
        assertEquals(80, GunWeight.all().size(), "82 of NTM's less its 3 that are no weapons, and the GL-54");
        for (String id : GunWeight.all().keySet()) assertTrue(id.startsWith("hbm:gun_") || id.equals("ntmgunfeel:gun_gl54"), id);
    }

    @Test
    void theOwnersHeavyGunsSlow() {
        // the owner's own list: tesla cannon, minigun, missile launcher, drill, fatman
        for (String id : new String[] {"hbm:gun_tesla_cannon", "hbm:gun_minigun", "hbm:gun_fatman"}) assertEquals(Weight.SUPER_HEAVY, GunWeight.of(id), id);
        for (String id : new String[] {"hbm:gun_missile_launcher", "hbm:gun_drill"}) assertEquals(Weight.HEAVY, GunWeight.of(id), id);
        assertEquals(Weight.LIGHT, GunWeight.of("hbm:gun_uzi"));
        assertEquals(new GunWeight.Gun("smg", Weight.LIGHT), GunWeight.gun("hbm:gun_uzi"));
        assertEquals("minigun", GunWeight.gun("hbm:gun_minigun_dual").kind());
        assertNull(GunWeight.of("hbm:gun_fireext"), "an extinguisher is no weapon");
        assertNull(GunWeight.of("hbm:gun_some_new_one"), "a gun not listed has no class");
    }

    @Test
    void everyKindHasItsNameInBothLanguages() throws Exception {
        for (String lang : new String[] {"en_us", "ru_ru"}) {
            String json;
            try (var in = GunWeightTest.class.getResourceAsStream("/assets/ntmgunfeel/lang/" + lang + ".json")) {
                json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            for (GunWeight.Gun g : GunWeight.all().values()) assertTrue(json.contains("\"ntmgunfeel.gun_kind." + g.kind() + "\""), lang + ": " + g.kind());
            for (Weight w : Weight.values()) assertTrue(json.contains("\"ntmgunfeel.gun_weight." + w.key() + "\""), lang + ": " + w);
        }
    }

    @Test
    void aLineOfTheFile() {
        assertEquals(new GunWeight.Gun("rifle", Weight.MEDIUM), GunWeight.line("hbm:gun_g3 = rifle, medium"));
        assertEquals(new GunWeight.Gun("laser_whip", Weight.HEAVY), GunWeight.line("  hbm:gun_new =  Laser_Whip ,HEAVY  "), "a new kind needs no code");
        assertEquals(new GunWeight.Gun("rifle", null), GunWeight.line("hbm:gun_x = rifle"), "a kind with no weight");
        assertNull(GunWeight.line("hbm:gun_fireext = none, none"));
        assertNull(GunWeight.line("# hbm:gun_g3 = rifle, medium"));
        assertNull(GunWeight.line("hbm:gun_g3 rifle medium"));
        assertNull(GunWeight.line("= rifle, medium"));
        assertEquals(new GunWeight.Gun("minigun", Weight.SUPER_HEAVY), GunWeight.line("hbm:gun_x = minigun, super-heavy"));
        assertEquals(new GunWeight.Gun("rifle", null), GunWeight.line("hbm:gun_x = rifle, hevy"), "a typo: no weight, and a warning");
    }

    @Test
    void aShareThatIsNoNumberIsTheDefault() {
        assertEquals(GunWeight.HEAVY, GunWeight.share(Float.NaN, GunWeight.HEAVY));
        assertEquals(0.5F, GunWeight.share(0.1F, GunWeight.HEAVY));
        assertEquals(1F, GunWeight.share(Float.POSITIVE_INFINITY, GunWeight.HEAVY));
        assertEquals(0.9F, GunWeight.share(0.9F, GunWeight.HEAVY));
    }

    @Test
    void theHeavierHandCountsAndOnlyHeavyGunsSlow() {
        assertEquals(Weight.SUPER_HEAVY, GunWeight.heavier(Weight.SUPER_HEAVY, Weight.SUPER_HEAVY), "two miniguns weigh as one");
        assertEquals(Weight.HEAVY, GunWeight.heavier(Weight.LIGHT, Weight.HEAVY));
        assertEquals(Weight.MEDIUM, GunWeight.heavier(null, Weight.MEDIUM));
        assertNull(GunWeight.heavier(null, null));
        assertEquals(1F, GunWeight.speed(null));
        assertEquals(1F, GunWeight.speed(Weight.LIGHT));
        assertEquals(1F, GunWeight.speed(Weight.MEDIUM));
        assertEquals(GunWeight.HEAVY, GunWeight.speed(Weight.HEAVY), 1e-6F);
        assertEquals(GunWeight.SUPER_HEAVY, GunWeight.speed(Weight.SUPER_HEAVY), 1e-6F);
    }

    @Test
    void theShippedServerFileHasTheDefaults() throws Exception {
        java.util.Properties p = new java.util.Properties();
        try (var in = GunWeightTest.class.getResourceAsStream("/ntmgunfeel-server.properties")) {
            p.load(in);
        }
        assertEquals("true", p.getProperty(GunWeight.SLOW_KEY));
        assertEquals(GunWeight.HEAVY, Float.parseFloat(p.getProperty(GunWeight.HEAVY_KEY)), 1e-6F);
        assertEquals(GunWeight.SUPER_HEAVY, Float.parseFloat(p.getProperty(GunWeight.SUPER_KEY)), 1e-6F);
    }
}
