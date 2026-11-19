package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import acup.gunfeel.GunWeightBase.Weight;

/** The guns' weight classes as the file has them, and what slows. */
class GunWeightTest {
    @Test
    void theFileHasEveryClassedGun() {
        Map<Weight, Long> n = GunWeightBase.all().values().stream().collect(Collectors.groupingBy(GunWeightBase.Gun::weight, Collectors.counting()));
        assertEquals(23L, n.get(Weight.LIGHT));
        // NTM's 18 and the GL-54
        assertEquals(19L, n.get(Weight.MEDIUM));
        assertEquals(19L, n.get(Weight.HEAVY));
        assertEquals(9L, n.get(Weight.SUPER_HEAVY));
        assertEquals(70, GunWeightBase.all().size(), "72 of NTM's less its 3 that are no weapons, and the GL-54");
        for (String id : GunWeightBase.all().keySet()) assertTrue(id.startsWith("hbm:gun_") || id.equals("ntmgunfeel:gun_gl54"), id);
    }

    @Test
    void theOwnersHeavyGunsSlow() {
        // the owner's own list: tesla cannon, minigun, missile launcher, drill, fatman
        for (String id : new String[] {"hbm:gun_tesla_cannon", "hbm:gun_minigun", "hbm:gun_fatman"}) assertEquals(Weight.SUPER_HEAVY, GunWeightBase.of(id), id);
        for (String id : new String[] {"hbm:gun_missile_launcher", "hbm:gun_drill"}) assertEquals(Weight.HEAVY, GunWeightBase.of(id), id);
        assertEquals(Weight.LIGHT, GunWeightBase.of("hbm:gun_uzi"));
        assertEquals(new GunWeightBase.Gun("smg", Weight.LIGHT), GunWeightBase.gun("hbm:gun_uzi"));
        assertEquals("minigun", GunWeightBase.gun("hbm:gun_minigun_dual").kind());
        assertNull(GunWeightBase.of("hbm:gun_fireext"), "an extinguisher is no weapon");
        assertNull(GunWeightBase.of("hbm:gun_some_new_one"), "a gun not listed has no class");
    }

    @Test
    void everyKindHasItsNameInBothLanguages() throws Exception {
        for (String lang : new String[] {"en_us", "ru_ru"}) {
            String json;
            try (var in = GunWeightTest.class.getResourceAsStream("/assets/ntmgunfeel/lang/" + lang + ".json")) {
                json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            for (GunWeightBase.Gun g : GunWeightBase.all().values()) assertTrue(json.contains("\"ntmgunfeel.gun_kind." + g.kind() + "\""), lang + ": " + g.kind());
            for (Weight w : Weight.values()) assertTrue(json.contains("\"ntmgunfeel.gun_weight." + w.key() + "\""), lang + ": " + w);
        }
    }

    @Test
    void aLineOfTheFile() {
        assertEquals(new GunWeightBase.Gun("rifle", Weight.MEDIUM), GunWeightBase.line("hbm:gun_g3 = rifle, medium"));
        assertEquals(new GunWeightBase.Gun("laser_whip", Weight.HEAVY), GunWeightBase.line("  hbm:gun_new =  Laser_Whip ,HEAVY  "), "a new kind needs no code");
        assertEquals(new GunWeightBase.Gun("rifle", null), GunWeightBase.line("hbm:gun_x = rifle"), "a kind with no weight");
        assertNull(GunWeightBase.line("hbm:gun_fireext = none, none"));
        assertNull(GunWeightBase.line("# hbm:gun_g3 = rifle, medium"));
        assertNull(GunWeightBase.line("hbm:gun_g3 rifle medium"));
        assertNull(GunWeightBase.line("= rifle, medium"));
        assertEquals(new GunWeightBase.Gun("minigun", Weight.SUPER_HEAVY), GunWeightBase.line("hbm:gun_x = minigun, super-heavy"));
        assertEquals(new GunWeightBase.Gun("rifle", null), GunWeightBase.line("hbm:gun_x = rifle, hevy"), "a typo: no weight, and a warning");
    }

    @Test
    void aShareThatIsNoNumberIsTheDefault() {
        assertEquals(GunWeightBase.HEAVY, GunWeightBase.share(Float.NaN, GunWeightBase.HEAVY));
        assertEquals(0.5F, GunWeightBase.share(0.1F, GunWeightBase.HEAVY));
        assertEquals(1F, GunWeightBase.share(Float.POSITIVE_INFINITY, GunWeightBase.HEAVY));
        assertEquals(0.9F, GunWeightBase.share(0.9F, GunWeightBase.HEAVY));
    }

    @Test
    void theHeavierHandCountsAndOnlyHeavyGunsSlow() {
        assertEquals(Weight.SUPER_HEAVY, GunWeightBase.heavier(Weight.SUPER_HEAVY, Weight.SUPER_HEAVY), "two miniguns weigh as one");
        assertEquals(Weight.HEAVY, GunWeightBase.heavier(Weight.LIGHT, Weight.HEAVY));
        assertEquals(Weight.MEDIUM, GunWeightBase.heavier(null, Weight.MEDIUM));
        assertNull(GunWeightBase.heavier(null, null));
        assertEquals(1F, GunWeightBase.speed(null));
        assertEquals(1F, GunWeightBase.speed(Weight.LIGHT));
        assertEquals(1F, GunWeightBase.speed(Weight.MEDIUM));
        assertEquals(GunWeightBase.HEAVY, GunWeightBase.speed(Weight.HEAVY), 1e-6F);
        assertEquals(GunWeightBase.SUPER_HEAVY, GunWeightBase.speed(Weight.SUPER_HEAVY), 1e-6F);
    }

    @Test
    void theShippedServerFileHasTheDefaults() throws Exception {
        java.util.Properties p = new java.util.Properties();
        try (var in = GunWeightTest.class.getResourceAsStream("/ntmgunfeel-server.properties")) {
            p.load(in);
        }
        assertEquals("true", p.getProperty(GunWeightBase.SLOW_KEY));
        assertEquals(GunWeightBase.HEAVY, Float.parseFloat(p.getProperty(GunWeightBase.HEAVY_KEY)), 1e-6F);
        assertEquals(GunWeightBase.SUPER_HEAVY, Float.parseFloat(p.getProperty(GunWeightBase.SUPER_KEY)), 1e-6F);
    }
}
