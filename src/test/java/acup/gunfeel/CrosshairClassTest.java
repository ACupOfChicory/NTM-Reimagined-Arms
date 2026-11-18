package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import acup.gunfeel.crosshair.CrosshairClass;
import acup.gunfeel.crosshair.CrosshairStyle;

// Every gun's crosshair style by its class (kickPreset, else its kind): src/test/pinned/crosshair.txt holds the table
class CrosshairClassTest {
    @Test
    void everyGun() {
        List<String> now = new ArrayList<>();
        for (String id : new TreeSet<>(GunWeight.all().keySet())) {
            CrosshairStyle s = CrosshairClass.of(id);
            GunWeight.Gun g = GunWeight.gun(id);
            now.add(id + " = " + (s == null ? "ntm" : s.key) + " (" + Grips.gunValue(id, "kickPreset") + ", " + g.kind() + ", " + g.weight() + ")");
        }
        PinnedTest.pin("crosshair", now);
    }

    @Test
    void asTaCZ() {
        assertEquals(CrosshairStyle.CORNER, CrosshairClass.of("hbm:gun_light_revolver"));
        assertEquals(CrosshairStyle.CORNER, CrosshairClass.of("hbm:gun_aberrator"));
        assertEquals(CrosshairStyle.BRACKETS, CrosshairClass.of("hbm:gun_uzi"));
        assertEquals(CrosshairStyle.SQUARE, CrosshairClass.of("hbm:gun_spas12"));
        assertEquals(CrosshairStyle.SQUARE, CrosshairClass.of("hbm:gun_maresleg"));
        assertEquals(CrosshairStyle.RULER, CrosshairClass.of("hbm:gun_panzerschreck"));
        assertEquals(CrosshairStyle.RULER, CrosshairClass.of("hbm:gun_charge_thrower"));
        assertEquals(CrosshairStyle.RULER, CrosshairClass.of("hbm:gun_tesla_cannon"));
        assertEquals(CrosshairStyle.CROSS, CrosshairClass.of("hbm:gun_g3"));
        assertEquals(CrosshairStyle.CROSS, CrosshairClass.of("hbm:gun_minigun"));
        assertEquals(CrosshairStyle.CROSS, CrosshairClass.of("hbm:gun_flamer"));
        assertNull(CrosshairClass.of("hbm:gun_fireext"));
    }
}
