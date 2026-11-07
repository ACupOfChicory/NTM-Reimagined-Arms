package acup.gunfeel.gl54;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.hbm.items.weapon.sedna.ItemGunBaseNT.GunState;
import com.hbm.render.anim.AnimationEnums.GunAnimation;

// The numbers of the GL-54's HUD (Gl54Hud): the number and its colour, LOW AMMO, the reserve, the dots, the reload bar.
class Gl54HudTest {
    @Test
    void numberIsBarrelPlusMagazineInTheNextGrenadesColour() {
        GunMag g = new GunMag(MagLogicTest.n("he", 8), "airburst", "he", true, false);
        Gl54Hud.Model m = Gl54Hud.model(g, 21, -1F);
        assertEquals("009", m.big());
        assertEquals("0021", m.small());
        assertEquals(GrenadeKind.AIRBURST.argb(), m.color(), "the barrel's grenade goes first");
        assertFalse(m.dim());
        assertFalse(m.lowAmmo());
    }

    @Test
    void lowAmmoAtTwoNotRed() {
        for (int n = 0; n <= 2; n++) {
            GunMag g = n == 0 ? new GunMag(List.of(), "", "he", true, false) : new GunMag(MagLogicTest.n("he", n - 1), "he", "he", true, false);
            Gl54Hud.Model m = Gl54Hud.model(g, 5, -1F);
            assertTrue(m.lowAmmo(), "count " + n);
            assertTrue(m.color() != Gl54Hud.LOW_COLOR);
        }
        assertFalse(Gl54Hud.model(new GunMag(MagLogicTest.n("he", 2), "he", "he", true, false), 5, -1F).lowAmmo());
        assertEquals(0xFFFFFFFF, Gl54Hud.model(GunMag.NONE, 5, -1F).color(), "nothing to fire: white");
        assertFalse(Gl54Hud.model(GunMag.NONE, 5, 0.5F).lowAmmo(), "not while reloading");
    }

    @Test
    void blinks() {
        assertTrue(Gl54Hud.blinkOn(0));
        assertTrue(Gl54Hud.blinkOn(399));
        assertFalse(Gl54Hud.blinkOn(400));
        assertTrue(Gl54Hud.blinkOn(800));
    }

    @Test
    void dotsOnlyForMoreThanOneColour() {
        assertEquals(0, Gl54Hud.dots(new GunMag(MagLogicTest.n("he", 7), "he", "he", true, false)).length);
        GunMag mixed = new GunMag(List.of("he", "inc", "smoke"), "airburst", "mixed", true, false);
        assertArrayEquals(new int[] {GrenadeKind.HE.argb(), GrenadeKind.INCENDIARY.argb(), GrenadeKind.SMOKE.argb()}, Gl54Hud.dots(mixed));
        // another kind's barrel grenade after a change shows the order too
        assertEquals(7, Gl54Hud.dots(new GunMag(MagLogicTest.n("he", 7), "airburst", "he", true, false)).length);
    }

    @Test
    void reloadBarRunsFromTheBeginCueThroughTheEndCue() {
        // not empty: 28 + 12 = 40 ticks
        assertEquals(0F, Gl54Hud.reloadFrac(GunAnimation.RELOAD, 0, GunState.RELOADING, false));
        assertEquals(14F / 40F, Gl54Hud.reloadFrac(GunAnimation.RELOAD, 14, GunState.RELOADING, false), 1e-6);
        assertEquals(28F / 40F, Gl54Hud.reloadFrac(GunAnimation.RELOAD_END, 0, GunState.DRAWING, false), 1e-6);
        assertEquals(39F / 40F, Gl54Hud.reloadFrac(GunAnimation.RELOAD_END, 11, GunState.DRAWING, false), 1e-6);
        // an empty gun: 50 ticks, the handle at the end
        assertEquals(28F / 50F, Gl54Hud.reloadFrac(GunAnimation.RELOAD_END, 0, GunState.DRAWING, true), 1e-6);
        assertEquals(45F / 50F, Gl54Hud.reloadFrac(GunAnimation.RELOAD_END, 17, GunState.DRAWING, true), 1e-6);
        // over, or a stale cue
        assertEquals(-1F, Gl54Hud.reloadFrac(GunAnimation.RELOAD_END, 12, GunState.DRAWING, false));
        assertEquals(-1F, Gl54Hud.reloadFrac(GunAnimation.RELOAD_END, 5, GunState.IDLE, false));
        assertEquals(-1F, Gl54Hud.reloadFrac(GunAnimation.CYCLE, 5, GunState.RELOADING, false));
    }

    @Test
    void reloadDimsTheNumberAndFillsTheBar() {
        Gl54Hud.Model m = Gl54Hud.model(new GunMag(MagLogicTest.n("he", 8), "he", "he", true, false), 0, 0.5F);
        assertTrue(m.dim());
        assertEquals(0.5F, m.bar());
        assertEquals("0000", m.small());
    }
}
