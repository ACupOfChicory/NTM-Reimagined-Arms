package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// The armour's numbers without the game: the sets by their item names, the table, the blast's power by tier (defaults).
class BalanceArmorBaseTest {
    @Test
    void setByItemName() {
        assertEquals("hev", BalanceArmorBase.setName("hev_helmet", 0));
        assertEquals("bj", BalanceArmorBase.setName("bj_plate_jetpack", 1));
        assertNull(BalanceArmorBase.setName("bj_plate_jetpack", 0));
        assertNull(BalanceArmorBase.setName("hev_helmet", 1));
        assertEquals("dns", BalanceArmorBase.setName("dns_boots", 3));
    }

    @Test
    void theTable() {
        assertEquals(186F, BalanceArmorBase.SETS.get("dns").s());
        assertEquals(4, BalanceArmorBase.SETS.get("hev").tier());
        assertEquals(7.5F, BalanceArmorBase.SETS.get("dns").tau());
        assertTrue(BalanceArmorBase.ROFL.contains("dnt"));
        assertFalse(BalanceArmorBase.SETS.containsKey("dnt"));
    }

    @Test
    void ratingsBySetting() {
        assertEquals(186F, BalanceArmorBase.rating("dns"));
        assertEquals(0F, BalanceArmorBase.rating("dnt"));
        assertTrue(BalanceArmorBase.rofl("dnt"));
        assertFalse(BalanceArmorBase.rofl("dns"));
    }

    @Test
    void blastPowerByTier() {
        assertEquals(1.7F, BalanceArmorBase.blastPowerFor(4, false, 10F, 3F));
        assertEquals(2.2F, BalanceArmorBase.blastPowerFor(7, true, 31F, 3F));
        assertEquals(3F, BalanceArmorBase.blastPowerFor(7, true, 25F, 3F));
        assertEquals(4, BalanceArmorBase.blastTier("trenchmaster", 6));
        assertEquals(6, BalanceArmorBase.blastTier("bismuth", 6));
        // a weak blast on tier 4: at least 0.9 S, less below 7 raw
        assertEquals(0.9F * 60F, BalanceArmorBase.blastInto(4, 10F, 1.7F, 60F), 1e-4F);
        assertEquals(10F * 1.7F, BalanceArmorBase.blastInto(6, 10F, 1.7F, 60F), 1e-4F);
    }

    @Test
    void theBlastNowIsSetAroundItsHurtOnly() {
        float inside = BalanceArmorBase.withBlastPower(true, true, false, 20F, () -> {
            assertTrue(BalanceArmorBase.vntBlastNow());
            assertTrue(BalanceArmorBase.ROCKET_DIRECT.get());
            return BalanceArmorBase.BLAST_POWER.get();
        });
        assertEquals(3F, inside);
        assertFalse(BalanceArmorBase.vntBlastNow());
        assertEquals(1F, BalanceArmorBase.BLAST_POWER.get());
        // a big charge (more raw damage than balanceExplRawLimit) is not a weapon's
        assertEquals(1F, BalanceArmorBase.withBlastPower(true, false, false, 61F, BalanceArmorBase.BLAST_POWER::get));
    }
}
