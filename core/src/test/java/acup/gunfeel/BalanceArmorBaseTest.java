package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// The armour's numbers without the game: the sets by their item names and the table. (What reads the settings,
// ReaimTimes, waits for core's tests to run without the version's META-INF/services.)
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
}
