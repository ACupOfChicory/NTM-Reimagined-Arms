package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

// A gun that took arms of its own (Heretic, shared with Sexy) keeps its hands to itself, and the editor's "what moves
// the hand" knows a path key on a part the model does not draw is no path (as Arms.pathPoint).
class OwnArmsTest {
    static final String HERETIC = "hbm:gun_autoshotgun_heretic", SEXY = "hbm:gun_autoshotgun_sexy";

    @Test
    void handsOfOwnArmsAreReadAndWrittenOnTheGunItself() {
        var opened = Grips.snapshot();
        try {
            float[] sexyRight = Grips.get(Identifier.parse(SEXY)).right().clone(), sexyAt = Grips.get(Identifier.parse(SEXY)).rightAt();
            Grips.copyArms(HERETIC);
            Grips.setAt(HERETIC, true, 9F, 8F, 7F, false);
            assertArrayEquals(new float[] {9F, 8F, 7F}, Grips.get(Identifier.parse(HERETIC)).rightAt());
            assertArrayEquals(sexyAt, Grips.get(Identifier.parse(SEXY)).rightAt(), "Sexy's hand is untouched");
            assertArrayEquals(sexyRight, Grips.get(Identifier.parse(SEXY)).right());
            Grips.set(HERETIC + "-Barrel", false, 0.1F, 0.2F, 0.3F, false);
            assertTrue(Grips.variantHand(HERETIC + "-Barrel", false, false));
            assertFalse(Grips.variantHand(SEXY + "-Barrel", false, false));
            Grips.shareArms(HERETIC);
            assertNull(Grips.own(HERETIC, "rightAt"), "shared again: nothing of its own left");
            assertFalse(Grips.variantHand(HERETIC + "-Barrel", false, false), "nor of its variants");
            assertArrayEquals(sexyAt, Grips.get(Identifier.parse(HERETIC)).rightAt());
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    // own arms take the shared gun's variants too: Sacred Dragon gets the double barrel's sawn-off grip
    @Test
    void ownArmsCopyTheVariantsToo() {
        var opened = Grips.snapshot();
        String dragon = "hbm:gun_double_barrel_sacred_dragon", barrel = "hbm:gun_double_barrel";
        try {
            float[] sawnOff = Grips.get(Identifier.parse(barrel), "-Barrel").leftAt();
            assertNotNull(sawnOff);
            Grips.copyArms(dragon);
            assertTrue(Grips.ownArms(dragon));
            assertEquals(Grips.gunValue(barrel, "armLeftKeys"), Grips.own(dragon, "armLeftKeys"));
            assertTrue(Grips.variantHand(dragon + "-Barrel", false, false));
            assertArrayEquals(sawnOff, Grips.get(Identifier.parse(dragon), "-Barrel").leftAt());
            assertEquals(0, Grips.armsToCopy(dragon));
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    @Test
    void anEmptyOwnValueHidesTheSharedGunsOne() {
        var opened = Grips.snapshot();
        try {
            Config.select(HERETIC);
            assertFalse(Config.armLeftHide.isEmpty(), "Heretic hides the left hand as Sexy does");
            Grips.copyArms(HERETIC);
            Grips.put(HERETIC, "armLeftHide", "", false);
            Config.select(HERETIC);
            assertEquals("", Config.armLeftHide);
            Config.select(SEXY);
            assertFalse(Config.armLeftHide.isEmpty(), "Sexy still hides it");
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    @Test
    void aPathOnAPartNotDrawnIsNoPath() {
        var opened = Grips.snapshot();
        try {
            Grips.put("gunfeel:path_probe", "armLeftPath", "RELOAD 0:Missing@0,0,0 500:grip", false);
            Config.select("gunfeel:path_probe");
            var drive = new ArmRig.Drive(false);
            assertEquals(ArmRig.Driver.PATH, drive.at("RELOAD", 100, ArmRig.Buses.NONE));
            var noMissing = new ArmRig.Buses() {
                public boolean has(String bus) {
                    return false;
                }

                public double[] at(String bus, float ms) {
                    return null;
                }

                public boolean drawn(String part, int nth) {
                    return !List.of("Missing").contains(part);
                }
            };
            assertEquals(ArmRig.Driver.HOLD, drive.at("RELOAD", 100, noMissing));
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }
}
