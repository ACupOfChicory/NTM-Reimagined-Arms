package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

// 26.2's ArmLayersTest.powerArmorHalvesRecoil, the part the port has (PowerArmor without ArmLayers)
class PowerArmorTest {
    @Test
    void powerArmorHalvesRecoil() {
        // no armour, ordinary armour: x1; power armour: the key (0.5 by default); 1 = off
        assertEquals(1F, PowerArmor.factor(false, 0.5F, false));
        assertEquals(0.5F, PowerArmor.factor(true, 0.5F, false));
        assertEquals(1F, PowerArmor.factor(true, 1F, false));
        assertEquals(0F, PowerArmor.factor(true, -2F, false));
        // a server minimum: the camera's no lower than half
        assertEquals(0.5F, PowerArmor.factor(true, 0.1F, true));
        assertEquals(0.8F, PowerArmor.factor(true, 0.8F, true));
        assertEquals(1F, PowerArmor.factor(false, 0.1F, true));
        assertEquals(1F, PowerArmor.factor(true, Float.NaN, false));
        assertEquals(0.5F, Config.powerArmorRecoil);
    }
}
