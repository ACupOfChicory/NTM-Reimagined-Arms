package acup.gunfeel.gl54;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

// Loading a magazine in the ammo press (PressLoading): the order, the gaps, partial magazines, a taken output, a chosen recipe.
class PressLoadingTest {
    static List<String> slots(String... s) {
        String[] all = new String[8];
        java.util.Arrays.fill(all, "");
        System.arraycopy(s, 0, all, 0, s.length);
        return List.of(all);
    }

    @Test
    void readLeftToRightTopToBottomSkippingGaps() {
        // slots 0 1 2 / 3 4 5 / 6 7: a mix with holes
        List<String> in = slots("he", "", "smoke", "airburst", "", "", "he", "acid");
        assertEquals(List.of("he", "smoke", "airburst", "he", "acid"), PressLoading.contents(-1, in, true, true));
    }

    @Test
    void theTopLeftFiresFirst() {
        List<String> got = PressLoading.contents(-1, slots("inc", "he", "he"), true, true);
        assertEquals("inc", got.get(0));
        // and the order is kept as a magazine's
        assertEquals("mixed", MagLogic.kind(got));
    }

    @Test
    void partialMagazinesAreFine() {
        assertEquals(List.of("he"), PressLoading.contents(-1, slots("", "", "", "", "", "", "", "he"), true, true));
        assertEquals(8, PressLoading.contents(-1, slots("he", "he", "he", "he", "he", "he", "he", "he"), true, true).size());
    }

    @Test
    void nothingToMakeWithoutGrenadesOrTheEmptyMagazine() {
        assertNull(PressLoading.contents(-1, slots(), true, true));
        assertNull(PressLoading.contents(-1, slots("he"), false, true));
    }

    @Test
    void anOccupiedOutputStopsIt() {
        assertNull(PressLoading.contents(-1, slots("he", "ap"), true, false));
    }

    @Test
    void anotherItemInASlotStopsIt() {
        assertNull(PressLoading.contents(-1, slots("he", PressLoading.FOREIGN), true, true));
    }

    @Test
    void aChosenRecipeLeavesTheNtmPathAlone() {
        for (int selected : new int[] {0, 1, 17, 300}) {
            assertFalse(PressLoading.ours(selected));
            assertNull(PressLoading.contents(selected, slots("he", "ap"), true, true));
            for (int slot = 0; slot < 10; slot++) assertFalse(PressLoading.accepts(selected, slot, true, true), "slot " + slot);
        }
        assertTrue(PressLoading.ours(-1));
    }

    @Test
    void withNoRecipeOnlyOurPartsGoIn() {
        for (int slot = 0; slot < 8; slot++) {
            assertTrue(PressLoading.accepts(-1, slot, true, false));
            assertFalse(PressLoading.accepts(-1, slot, false, true));
        }
        assertTrue(PressLoading.accepts(-1, 8, false, true));
        assertFalse(PressLoading.accepts(-1, 8, true, false));
        assertFalse(PressLoading.accepts(-1, 9, true, true), "the output takes nothing");
    }
}
