package acup.gunfeel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExplosiveDamageTest {
    @Test void contactAndBlastHaveSeparateBudgets() {
        assertEquals(25,ExplosiveDamage.total(25,25,1,true),.001);
        assertEquals(36.25,ExplosiveDamage.total(25,25,1.75F,true),.001);
        assertEquals(17.5,ExplosiveDamage.total(25,25,.5F,true),.001);
        assertEquals(10,ExplosiveDamage.total(25,25,1.75F,false),.001);
        assertEquals(26.25,ExplosiveDamage.total(0,25,1.75F,true),.001);
    }
    @Test void largeImpactFamilyIsIndependentOfProjectileSpeed() {
        assertTrue(ExplosiveAmmo.heavyPath("ammo_standard_g40_he"));
        assertTrue(ExplosiveAmmo.heavyPath("ammo_standard_g40_heat"));
        assertTrue(ExplosiveAmmo.heavyPath("ammo_standard_rocket_he"));
        assertTrue(ExplosiveAmmo.heavyPath("ammo_standard_rocket_phosphorus"));
        assertFalse(ExplosiveAmmo.heavyPath("ammo_standard_g26_flare_supply"));
        assertFalse(ExplosiveAmmo.heavyPath("ammo_standard_bmg50_he"));
    }
}
