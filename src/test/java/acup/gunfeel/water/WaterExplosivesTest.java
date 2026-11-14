package acup.gunfeel.water;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import acup.gunfeel.water.WaterClass.Fuze;
import acup.gunfeel.water.WaterEvents.Liquid;
import acup.gunfeel.water.WaterEvents.Type;
import acup.gunfeel.water.WaterPath.Cell;
import acup.gunfeel.water.WaterPath.Settings;
import net.minecraft.world.phys.Vec3;

// The explosives' part without a world: which ammo has a fuze or sets alight, where a blast stands to water, how a
// stopped explosive sinks, and the fuze's skip angle.
class WaterExplosivesTest {
    @Test
    void fuzesByTheAmmo() {
        Map<String, Fuze> expected = Map.ofEntries(
                Map.entry("ammo_standard_r762_he", Fuze.IMPACT), Map.entry("ammo_standard_bmg50_he", Fuze.IMPACT),
                Map.entry("ammo_standard_b75", Fuze.IMPACT), Map.entry("ammo_standard_b75_exp", Fuze.IMPACT),
                Map.entry("ammo_standard_b75_inc", Fuze.WEAK),
                Map.entry("ammo_standard_g12_explosive", Fuze.IMPACT), Map.entry("ammo_standard_g10_explosive", Fuze.IMPACT),
                Map.entry("ammo_standard_g40_he", Fuze.IMPACT), Map.entry("ammo_standard_g40_phosphorus", Fuze.IMPACT),
                Map.entry("ammo_standard_rocket_he", Fuze.IMPACT), Map.entry("ammo_standard_rocket_inc", Fuze.IMPACT),
                Map.entry("ammo_standard_nuke_standard", Fuze.IMPACT), Map.entry("ammo_standard_ct_mortar", Fuze.IMPACT),
                Map.entry("ammo_secret_folly_nuke", Fuze.IMPACT),
                Map.entry("ammo_standard_g12_phosphorus", Fuze.NONE), Map.entry("ammo_standard_g26_flare", Fuze.NONE),
                Map.entry("ammo_standard_r762_fmj", Fuze.NONE), Map.entry("ammo_standard_g12", Fuze.NONE),
                Map.entry("", Fuze.NONE));
        for (var e : expected.entrySet()) assertEquals(e.getValue(), Fuze.of(e.getKey()), e.getKey());
        assertTrue(Fuze.WEAK.any());
        assertFalse(Fuze.NONE.any());
    }

    @Test
    void whatSetsAlight() {
        for (String a : List.of("ammo_standard_g12_phosphorus", "ammo_standard_b75_inc", "ammo_standard_g40_inc", "ammo_standard_g40_phosphorus",
                "ammo_standard_rocket_inc", "ammo_standard_rocket_phosphorus", "ammo_standard_g26_flare", "ammo_standard_g26_flare_weapon"))
            assertTrue(WaterClass.incendiary(a), a);
        for (String a : List.of("ammo_standard_r762_he", "ammo_standard_g40_he", "ammo_standard_g12", "ammo_standard_b75"))
            assertFalse(WaterClass.incendiary(a), a);
    }

    // a still pool, floor y 0 to the top cell 2 (8/9 high): the surface at 2.889
    private static WaterPathTest.World pool() {
        return new WaterPathTest.World().pool(-4, 4, 0, 2, -4, 4);
    }

    private static final double SURFACE = 2 + (float) (8.0 / 9.0);

    @Test
    void whereABlastStandsToWater() {
        var w = pool();
        var deep = WaterBlast.place(w, new Vec3(0.5, 0.5, 0.5));
        assertNotNull(deep);
        assertTrue(deep.inWater());
        assertEquals(Liquid.WATER, deep.liquid());
        assertEquals(SURFACE, deep.surface().y, 1e-6);
        assertEquals(0, deep.above(), 1e-12);
        // on the surface (NTM's small blast 0.25 over the point hit): in it
        assertTrue(WaterBlast.place(w, new Vec3(0.5, SURFACE + 0.25, 0.5)).inWater());
        // half a block over: a plume, not in the water
        var over = WaterBlast.place(w, new Vec3(0.5, SURFACE + 0.5, 0.5));
        assertNotNull(over);
        assertFalse(over.inWater());
        assertEquals(0.5, over.above(), 1e-6);
        // more than a block over, or no water: nothing
        assertNull(WaterBlast.place(w, new Vec3(0.5, SURFACE + 1.2, 0.5)));
        assertNull(WaterBlast.place(w, new Vec3(10.5, 1, 0.5)));
        // a column deeper than 8 blocks: no surface over the centre
        var sea = new WaterPathTest.World().pool(0, 0, 0, 20, 0, 0);
        assertNull(WaterBlast.place(sea, new Vec3(0.5, 0.5, 0.5)).surface());
        assertTrue(WaterBlast.place(sea, new Vec3(0.5, 0.5, 0.5)).inWater());
    }

    @Test
    void aStoppedExplosiveSinksNoFasterThanABlockASecond() {
        assertEquals(-0.05, WaterTick.sinkSpeed(-0.3), 1e-12);
        assertEquals(-0.02, WaterTick.sinkSpeed(-0.02), 1e-12);
        // it does not rise
        assertEquals(0, WaterTick.sinkSpeed(0.1), 1e-12);
        assertEquals(20 * WaterTick.SINK, 1.0, 1e-12);
    }

    // a rifle-class bullet (r762_he) at 8° on still water: a plain bullet goes in (6°), an explosive one skips (10°)
    @Test
    void anExplosiveBulletSkipsByTheFuzeAngle() {
        double a = Math.toRadians(8);
        Vec3 dir = new Vec3(Math.cos(a), -Math.sin(a), 0);
        Vec3 hit = new Vec3(0.5, SURFACE, 0.5);
        Settings s = Settings.DEFAULT;
        var plain = WaterPath.trace(pool(), hit.subtract(dir.scale(3)), hit.add(dir.scale(3)), 1, WaterClass.RIFLE, 0, 1L, s, null);
        var he = WaterPath.trace(pool(), hit.subtract(dir.scale(3)), hit.add(dir.scale(3)), 1, WaterClass.RIFLE, 0, 1L, s.fuzed(), null);
        assertNull(plain.skip);
        assertEquals(Type.ENTER, plain.events.get(0).type());
        assertNotNull(he.skip);
        // steeper than 10°: in, the fuze goes off on the entry
        double b = Math.toRadians(30);
        Vec3 d30 = new Vec3(Math.cos(b), -Math.sin(b), 0);
        var steep = WaterPath.trace(pool(), hit.subtract(d30.scale(3)), hit.add(d30.scale(3)), 1, WaterClass.RIFLE, 0, 1L, s.fuzed(), null);
        assertNull(steep.skip);
        assertEquals(Type.ENTER, steep.events.get(0).type());
        assertEquals(SURFACE, steep.events.get(0).at().y, 1e-6);
    }

    // fired under water there is no entry from the air: nothing for the surface fuze
    @Test
    void underWaterThereIsNoEntry() {
        var r = WaterPath.trace(pool(), new Vec3(0.5, 1, 0.5), new Vec3(3.5, 1, 0.5), 1, WaterClass.ROCKET, 0, 1L, Settings.DEFAULT.fuzed(), null);
        assertTrue(r.events.stream().noneMatch(e -> e.type() == Type.ENTER));
        assertTrue(r.water > 2.9);
    }

    @Test
    void lavaHasNoSurfaceCountedAsWater() {
        var w = new WaterPathTest.World().put(0, 0, 0, new Cell(Liquid.LAVA, 1F, false));
        var p = WaterBlast.place(w, new Vec3(0.5, 0.5, 0.5));
        assertEquals(Liquid.LAVA, p.liquid());
    }
}
