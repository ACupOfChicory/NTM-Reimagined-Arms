package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

// Mister Topaz takes every setting of the hand flamethrower (the jar's and the player's, its variants' too) where it
// has none of its own; its own win and are written under it; the Daybreaker takes nothing.
class InheritedGunTest {
    static final String FLAMER = "hbm:gun_flamer", TOPAZ = "hbm:gun_flamer_topaz", DAYBREAKER = "hbm:gun_flamer_daybreaker";

    @Test
    void topazTakesTheFlamersSettingsAndItsOwnWin() {
        var opened = Grips.snapshot();
        try {
            assertEquals(FLAMER, Grips.inheritsFrom(TOPAZ));
            assertNull(Grips.inheritsFrom(DAYBREAKER));
            assertNull(Grips.inheritsFrom(FLAMER));
            // the jar's hands
            assertArrayEquals(Grips.get(Identifier.parse(FLAMER)).leftAt(), Grips.get(Identifier.parse(TOPAZ)).leftAt());
            assertTrue(Grips.inherited(TOPAZ, "leftAt"));

            // the player's settings of the flamethrower, a variant's hand too
            Grips.put(FLAMER, "kickPitch", "0.1,0.03,0.04,0,0,0", false);
            Grips.put(FLAMER, "armRollLeft", "7", false);
            Grips.set(FLAMER + "-HeatShield", false, 0.1F, 0.2F, 0.3F, false);
            assertEquals("0.1,0.03,0.04,0,0,0", Grips.gunValue(TOPAZ, "kickPitch"));
            assertEquals("7", Grips.gunValue(TOPAZ, "armRollLeft"));
            assertTrue(Grips.inherited(TOPAZ, "kickPitch"));
            assertNull(Grips.own(TOPAZ, "kickPitch"), "inherited is not Topaz's own");
            assertArrayEquals(new float[] {0.1F, 0.2F, 0.3F}, Grips.get(Identifier.parse(TOPAZ), "-HeatShield").left());
            Config.select(TOPAZ);
            assertEquals(7F, Config.armRollLeft);
            assertNull(Grips.gunValue(DAYBREAKER, "kickPitch"), "the Daybreaker takes nothing");

            // Topaz's own key wins and is written under Topaz; the flamethrower stays as it is
            Grips.put(TOPAZ, "cameraScale", "0.55", false);
            Grips.put(TOPAZ, "kickPitch", "2,0.03,0.04,0,0,0", false);
            assertEquals("2,0.03,0.04,0,0,0", Grips.gunValue(TOPAZ, "kickPitch"));
            assertEquals("2,0.03,0.04,0,0,0", Grips.own(TOPAZ, "kickPitch"));
            assertFalse(Grips.inherited(TOPAZ, "kickPitch"));
            assertEquals("0.1,0.03,0.04,0,0,0", Grips.gunValue(FLAMER, "kickPitch"));
            assertNull(Grips.gunValue(FLAMER, "cameraScale"));

            // the flamethrower changed: Topaz follows where it has nothing of its own
            Grips.put(FLAMER, "armRollLeft", "12", false);
            Grips.put(FLAMER, "kickPitch", "5,0.03,0.04,0,0,0", false);
            assertEquals("12", Grips.gunValue(TOPAZ, "armRollLeft"));
            assertEquals("2,0.03,0.04,0,0,0", Grips.gunValue(TOPAZ, "kickPitch"));

            // Topaz's own value taken off (or set to the flamethrower's): it follows the flamethrower again
            Grips.put(TOPAZ, "kickPitch", null, false);
            assertEquals("5,0.03,0.04,0,0,0", Grips.gunValue(TOPAZ, "kickPitch"));
            Grips.put(TOPAZ, "armRollLeft", "12", false);
            assertNull(Grips.own(TOPAZ, "armRollLeft"));
            Grips.put(FLAMER, "armRollLeft", "3", false);
            assertEquals("3", Grips.gunValue(TOPAZ, "armRollLeft"));

            // an empty own value hides the flamethrower's
            Grips.put(TOPAZ, "armRollLeft", "", false);
            assertNull(Grips.gunValue(TOPAZ, "armRollLeft"));
            assertEquals("3", Grips.gunValue(FLAMER, "armRollLeft"));

            // a hand moved on Topaz is Topaz's; the flamethrower's stays
            float[] flamerLeft = Grips.get(Identifier.parse(FLAMER)).leftAt().clone();
            Grips.setAt(TOPAZ, false, 1F, 2F, 3F, false);
            assertArrayEquals(new float[] {1F, 2F, 3F}, Grips.get(Identifier.parse(TOPAZ)).leftAt());
            assertArrayEquals(flamerLeft, Grips.get(Identifier.parse(FLAMER)).leftAt());
            Grips.resetHand(TOPAZ, false, false);
            assertArrayEquals(flamerLeft, Grips.get(Identifier.parse(TOPAZ)).leftAt());
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    // loading Topaz's arms back as armsOf gives them changes nothing, and a hand missing from the loaded arms is hidden
    @Test
    void armsOfTopazLoadBackAsTheyAre() {
        var opened = Grips.snapshot();
        try {
            Grips.put(FLAMER, "armRollLeft", "7", false);
            var arms = Grips.armsOf(TOPAZ);
            assertEquals("7", arms.get(TOPAZ + ".armRollLeft"));
            Grips.putArms(TOPAZ, arms);
            assertNull(Grips.own(TOPAZ, "armRollLeft"), "the same as inherited: not written as Topaz's own");
            arms.remove(TOPAZ + ".armRollLeft");
            Grips.putArms(TOPAZ, arms);
            assertNull(Grips.gunValue(TOPAZ, "armRollLeft"), "left out: hidden, not taken from the flamethrower");
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    // a variant's hand Topaz takes from the flamethrower can be taken off ("as the whole gun") on Topaz alone; the
    // player's file never gets the inherited keys; a jar value hidden on the flamethrower is hidden for Topaz too
    @Test
    void inheritedVariantHandAndWhatIsWritten() {
        var opened = Grips.snapshot();
        try {
            Grips.set(FLAMER + "-HeatShield", false, 0.1F, 0.2F, 0.3F, false);
            assertTrue(Grips.variantHand(TOPAZ + "-HeatShield", false, false));
            assertTrue(Grips.variantHandInherited(TOPAZ + "-HeatShield", false));
            assertFalse(Grips.variantHandInherited(FLAMER + "-HeatShield", false));
            assertTrue(Grips.snapshot().keySet().stream().noneMatch(k -> k.startsWith(TOPAZ)), "nothing of Topaz written");
            Grips.commonHand(TOPAZ + "-HeatShield", false);
            assertFalse(Grips.variantHand(TOPAZ + "-HeatShield", false, false));
            assertArrayEquals(Grips.get(Identifier.parse(TOPAZ)).leftAt(), Grips.get(Identifier.parse(TOPAZ), "-HeatShield").leftAt());
            assertTrue(Grips.variantHand(FLAMER + "-HeatShield", false, false), "the flamethrower's stays");

            assertEquals(Grips.shipped(FLAMER, "leftAt"), Grips.shipped(TOPAZ, "leftAt"));
            assertNotNull(Grips.shipped(TOPAZ, "leftAt"));
            Grips.put(FLAMER, "leftAt", "", false);
            assertNull(Grips.shipped(TOPAZ, "leftAt"), "hidden on the flamethrower: hidden for Topaz");
            assertNull(Grips.get(Identifier.parse(TOPAZ)).leftAt());
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }
}
