package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.hbm.util.DamageResistanceHandler;

// core's names of NTM's categories (BalanceArmorBase.CAT_*) are this NTM's (compile-time constants, written into the
// mod's code by javac as they were before core had them)
class HurtCategoriesTest {
    @Test
    void ntms() {
        assertEquals(DamageResistanceHandler.CATEGORY_EXPLOSION, BalanceArmorBase.CAT_EXPLOSION);
        assertEquals(DamageResistanceHandler.CATEGORY_FIRE, BalanceArmorBase.CAT_FIRE);
        assertEquals(DamageResistanceHandler.CATEGORY_PHYSICAL, BalanceArmorBase.CAT_PHYSICAL);
        assertEquals(DamageResistanceHandler.CATEGORY_ENERGY, BalanceArmorBase.CAT_ENERGY);
    }
}
