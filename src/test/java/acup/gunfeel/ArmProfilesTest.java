package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class ArmProfilesTest {
    // The arm model comes from the skin alone: a gun keeps one copy of its arm settings and hand positions,
    // and keys left over from per-model storage (`<gun>.slim.*`, `<gun>.armModel`) change nothing.
    @Test
    void gunKeepsOneCopyOfArmSettingsForBothModels() {
        String gun = "gunfeel:profile_test";
        Identifier id = Identifier.parse(gun);
        try {
            Grips.put(gun, "armScale", "0.65", false);
            Grips.put(gun, "slim.armScale", "0.5", false);
            Grips.put(gun, "armModel", "slim", false);
            Grips.setAt(gun, true, 1, 2, 3, false);
            assertEquals("0.65", Grips.gunValue(gun, "armScale"));
            assertFalse(Grips.overrides(gun).keySet().stream().anyMatch(k -> k.startsWith("slim.")));
            Config.select(gun);
            assertEquals(0.65F, Config.armScale);
            Grips.set(gun, true, 0.1F, 0.2F, 0.3F, false);
            assertNull(Grips.get(id).rightAt(), "fractions hide the inherited point");
            assertArrayEquals(new float[] {0.1F, 0.2F, 0.3F}, Grips.get(id).right());
            Grips.resetHand(gun, true, false);
            assertNull(Grips.get(id).rightAt());
        } finally {
            Grips.put(gun, "armScale", null, false);
            Grips.put(gun, "slim.armScale", null, false);
            Grips.put(gun, "armModel", null, false);
            Grips.resetHand(gun, true, false);
            Config.select(null);
        }
    }
}
