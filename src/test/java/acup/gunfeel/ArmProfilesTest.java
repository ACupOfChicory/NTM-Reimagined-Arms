package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class ArmProfilesTest {
    @Test
    void previewModelUsesSharedSettingsAndHandPositions() {
        String gun = "gunfeel:profile_test";
        Identifier id = Identifier.parse(gun);
        try {
            Grips.put(gun, "armScale", "0.8", false);
            Grips.setAt(gun, true, 1, 2, 3, false);
            Grips.put(gun, "armModel", "slim", false);
            assertEquals("0.8", Grips.gunValue(gun, "armScale"));
            Grips.put(gun, "armScale", "0.6", false);
            Grips.set(gun, true, 0.1F, 0.2F, 0.3F, false);
            assertNull(Grips.get(id).rightAt(), "fractions hide the inherited point");
            assertArrayEquals(new float[] {0.1F, 0.2F, 0.3F}, Grips.get(id).right());
            Grips.put(gun, "armModel", "classic", false);
            assertEquals("0.6", Grips.gunValue(gun, "armScale"));
            assertArrayEquals(new float[] {0.1F, 0.2F, 0.3F}, Grips.get(id).right());
            assertNull(Grips.get(id).rightAt());
            Grips.resetHand(gun, true, false);
            assertNull(Grips.get(id).rightAt());
            assertFalse(Grips.snapshot().keySet().stream().anyMatch(k -> k.startsWith(gun + ".slim.")));
        } finally {
            Grips.put(gun, "armScale", null, false);
            Grips.put(gun, "armModel", null, false);
            Grips.resetHand(gun, true, false);
        }
    }
}
