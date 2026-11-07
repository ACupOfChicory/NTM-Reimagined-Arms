package acup.gunfeel.gl54;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

// The layouts an ammo press remembers (PressLayouts) and loading with one selected (PressLoading): up to 6, no duplicates,
// never a preset, the oldest goes, a re-used one is the newest, a delete, and the selection index.
class PressLayoutsTest {
    static final List<List<String>> PRESETS = List.of(PressLoadingTest.rounds("he", 8), PressLoadingTest.rounds("smoke", 8));

    static List<String> l(String... keys) {
        return List.of(keys);
    }

    static List<List<String>> fill(int n) {
        List<List<String>> s = List.of();
        for (int i = 1; i <= n; i++) s = PressLayouts.remember(s, PressLoadingTest.rounds("he", i), PRESETS);
        return s;
    }

    @Test
    void aNewLayoutIsStoredLast() {
        var s = PressLayouts.remember(List.of(l("he", "ap")), l("inc", "he"), PRESETS);
        assertEquals(List.of(l("he", "ap"), l("inc", "he")), s);
    }

    @Test
    void noDuplicatesAndTheReusedOneIsTheNewest() {
        var s = PressLayouts.remember(List.of(l("he", "ap"), l("inc"), l("smoke", "he")), l("he", "ap"), PRESETS);
        assertEquals(List.of(l("inc"), l("smoke", "he"), l("he", "ap")), s);
        // the same order is the same layout, another order is another
        assertEquals(4, PressLayouts.remember(s, l("ap", "he"), PRESETS).size());
    }

    @Test
    void aPresetIsNeverStored() {
        var s = List.of(l("inc"));
        assertSame(s, PressLayouts.remember(s, PressLoadingTest.rounds("he", 8), PRESETS));
        // 7 of a kind is not the preset of 8
        assertEquals(2, PressLayouts.remember(s, PressLoadingTest.rounds("he", 7), PRESETS).size());
    }

    @Test
    void nothingOrTooMuchIsNotStored() {
        var s = List.of(l("inc"));
        assertSame(s, PressLayouts.remember(s, List.of(), PRESETS));
        assertSame(s, PressLayouts.remember(s, PressLoadingTest.rounds("ap", 9), PRESETS));
    }

    @Test
    void sixAndTheOldestGoes() {
        var s = fill(6);
        assertEquals(6, s.size());
        assertEquals(1, s.get(0).size());
        var more = PressLayouts.remember(s, l("ap", "ap", "acid"), PRESETS);
        assertEquals(6, more.size());
        assertEquals(2, more.get(0).size());
        assertEquals(l("ap", "ap", "acid"), more.get(5));
    }

    @Test
    void reusingTheOldestSavesItFromGoing() {
        var s = fill(6);
        var again = PressLayouts.remember(s, s.get(0), PRESETS);
        assertEquals(s.get(0), again.get(5));
        var more = PressLayouts.remember(again, l("acid"), PRESETS);
        // the one that went is now the second oldest: the layout of 2
        assertTrue(!more.contains(PressLoadingTest.rounds("he", 2)));
        assertTrue(more.contains(PressLoadingTest.rounds("he", 1)));
    }

    @Test
    void forgetDropsOneAndIgnoresABadIndex() {
        var s = fill(3);
        assertEquals(List.of(s.get(0), s.get(2)), PressLayouts.forget(s, 1));
        assertSame(s, PressLayouts.forget(s, 3));
        assertSame(s, PressLayouts.forget(s, -1));
    }

    @Test
    void theSelectionCountsNtmsRecipesFirst() {
        // 52 recipes of NTM, 3 stored: indexes 52..54 are the stored ones
        assertEquals(-1, PressLayouts.stored(-1, 52, 3));
        assertEquals(-1, PressLayouts.stored(51, 52, 3));
        assertEquals(0, PressLayouts.stored(52, 52, 3));
        assertEquals(2, PressLayouts.stored(54, 52, 3));
        assertEquals(-1, PressLayouts.stored(55, 52, 3));
        // a recipe list that changed: the layouts are the same, only their indexes move
        assertEquals(0, PressLayouts.stored(40, 40, 3));
    }

    @Test
    void aSelectedLayoutIsFollowedByItsLayout() {
        var s = fill(3);
        var sel = s.get(0);
        var moved = PressLayouts.remember(s, sel, PRESETS);
        assertEquals(2, PressLayouts.indexOf(moved, sel));
        assertEquals(-1, PressLayouts.indexOf(PressLayouts.forget(moved, 2), sel));
        assertEquals(-1, PressLayouts.indexOf(moved, null));
    }

    @Test
    void theLayoutsAreSavedAndReadBack() {
        var s = fill(4);
        var json = PressLayouts.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, s).getOrThrow();
        assertEquals(s, PressLayouts.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, json).getOrThrow());
    }

    @Test
    void aSelectedLayoutTakesOnlyItsGrenadePerSlot() {
        var layout = l("he", "ap", "he");
        assertTrue(PressLoading.acceptsLayout(layout, 0, "he", false));
        assertTrue(!PressLoading.acceptsLayout(layout, 0, "ap", false));
        assertTrue(PressLoading.acceptsLayout(layout, 1, "ap", false));
        // slots past the layout take nothing, the magazine slot takes the empty magazine only
        assertTrue(!PressLoading.acceptsLayout(layout, 3, "he", false));
        assertTrue(!PressLoading.acceptsLayout(layout, 8, "he", false));
        assertTrue(PressLoading.acceptsLayout(layout, 8, "", true));
        assertTrue(!PressLoading.acceptsLayout(layout, 9, "he", true));
    }

    @Test
    void aSelectedLayoutLoadsOnlyWhenComplete() {
        var layout = l("he", "ap", "he");
        assertEquals(layout, PressLoading.contentsLayout(layout, PressLoadingTest.slots("he", "ap", "he"), true, true));
        assertNull(PressLoading.contentsLayout(layout, PressLoadingTest.slots("he", "ap"), true, true));
        assertNull(PressLoading.contentsLayout(layout, PressLoadingTest.slots("he", "ap", "he"), false, true));
        assertNull(PressLoading.contentsLayout(layout, PressLoadingTest.slots("he", "ap", "he"), true, false));
        assertNull(PressLoading.contentsLayout(layout, PressLoadingTest.slots("he", "ap", "he", "he"), true, true));
        assertNull(PressLoading.contentsLayout(layout, PressLoadingTest.slots("he", "he", "ap"), true, true));
        assertNull(PressLoading.contentsLayout(layout, PressLoadingTest.slots("he", "ap", PressLoading.FOREIGN), true, true));
    }
}
