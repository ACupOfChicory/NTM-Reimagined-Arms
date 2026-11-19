package acup.gunfeel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExplosiveRuleTest {
    @Test void contactAndBlastHaveSeparateBudgets() {
        assertEquals(25,ExplosiveRule.total(25,25,1,true),.001);
        assertEquals(36.25,ExplosiveRule.total(25,25,1.75F,true),.001);
        assertEquals(17.5,ExplosiveRule.total(25,25,.5F,true),.001);
        assertEquals(10,ExplosiveRule.total(25,25,1.75F,false),.001);
        assertEquals(26.25,ExplosiveRule.total(0,25,1.75F,true),.001);
    }
    @Test void largeImpactFamilyIsIndependentOfProjectileSpeed() {
        assertTrue(ExplosiveRule.heavyPath("ammo_standard_g40_he"));
        assertTrue(ExplosiveRule.heavyPath("ammo_standard_g40_heat"));
        assertTrue(ExplosiveRule.heavyPath("ammo_standard_rocket_he"));
        assertTrue(ExplosiveRule.heavyPath("ammo_standard_rocket_phosphorus"));
        assertFalse(ExplosiveRule.heavyPath("ammo_standard_g26_flare_supply"));
        assertFalse(ExplosiveRule.heavyPath("ammo_standard_bmg50_he"));
        assertTrue(ExplosiveRule.rocketPath("ammo_standard_rocket_he"));
        assertFalse(ExplosiveRule.rocketPath("ammo_standard_g40_he"));
        assertTrue(ExplosiveRule.shellPath("ammo_shell"));
        assertTrue(ExplosiveRule.shellPath("ammo_shell_w9"));
        assertFalse(ExplosiveRule.shellPath("ammo_shells"));
        assertEquals(.5F,ExplosiveRule.onSelf(true));
        assertEquals(1F,ExplosiveRule.onSelf(false));
        assertEquals(10F,ExplosiveRule.area(25F),1e-6F);
    }
}
