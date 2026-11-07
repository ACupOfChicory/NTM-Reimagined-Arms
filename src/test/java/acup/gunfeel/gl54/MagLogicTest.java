package acup.gunfeel.gl54;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

// The rules of the 20 mm magazines on plain lists (MagLogic, MagSwap, GunMag): kinds, the pour, the swap with its 8 + 1 and
// 8, the semi-automatic feed, the wheel's entries by exact contents, the HUD's numbers.
class MagLogicTest {
    static List<String> n(String key, int count) {
        return new ArrayList<>(Collections.nCopies(count, key));
    }

    static GunMag gun(String key, int inMag, boolean barrel) {
        return new GunMag(n(key, inMag), barrel ? key : "", List.of(), true, false);
    }

    static List<List<String>> mags(List<String>... m) {
        return new ArrayList<>(List.of(m));
    }

    @Test
    void kindOfAMagazine() {
        assertEquals("empty", MagLogic.kind(List.of()));
        assertEquals("he", MagLogic.kind(n("he", 8)));
        assertEquals("he", MagLogic.kind(n("he", 1)));
        assertEquals("mixed", MagLogic.kind(List.of("he", "he", "inc")));
    }

    @Test
    void fullestOfAKindTakesTheFirstAmongEquals() {
        List<List<String>> m = new ArrayList<>();
        m.add(n("he", 3));
        m.add(null);
        m.add(n("ap", 8));
        m.add(n("he", 8));
        m.add(n("he", 8));
        assertEquals(3, MagLogic.fullest(m, "he"));
        assertEquals(2, MagLogic.fullest(m, "ap"));
        assertEquals(-1, MagLogic.fullest(m, "smoke"));
    }

    @Test
    void pourTargetIsTheFullestUnfinishedOfTheKind() {
        List<List<String>> m = new ArrayList<>(List.of(n("he", 8), n("he", 2), n("he", 6), n("he", 6), n("ap", 7)));
        assertEquals(2, MagLogic.pourTarget(m, "he"));
        assertEquals(4, MagLogic.pourTarget(m, "ap"));
        assertEquals(-1, MagLogic.pourTarget(m, "mixed"));
        assertEquals(-1, MagLogic.pourTarget(m, "empty"));
        assertEquals(-1, MagLogic.pourTarget(List.of(n("he", 8)), "he"));
    }

    @Test
    void aPourFillsAndLeavesAStub() {
        MagLogic.Poured p = MagLogic.pour(n("he", 5), n("he", 6));
        assertEquals(8, p.target().size());
        assertEquals(3, p.left().size());
        p = MagLogic.pour(n("he", 2), n("he", 3));
        assertEquals(5, p.target().size());
        assertTrue(p.left().isEmpty());
    }

    @Test
    void swapKeepsTheBarrelsGrenade() {
        // 3 + 1 in the gun, a full magazine wanted: 8 + 1 = 9
        MagSwap s = MagSwap.of(gun("he", 3, true), mags(n("he", 8)), n("he", 8), false);
        assertNotNull(s);
        assertEquals(8, s.gun().mag().size());
        assertEquals("he", s.gun().chamber());
        assertEquals(9, s.gun().count());
        assertTrue(s.gun().fresh(), "the decider asks canReload once more: no");
        assertEquals(3, s.back().size());
        assertEquals(-1, s.pourSlot());
        assertEquals(n("he", 8), s.gun().want(), "the next R takes the same contents");
    }

    @Test
    void anEmptyGunIsFedTheFirst() {
        GunMag g = new GunMag(List.of(), "", List.of(), true, false);
        MagSwap s = MagSwap.of(g, mags(n("he", 8)), n("he", 8), false);
        assertEquals(7, s.gun().mag().size());
        assertEquals("he", s.gun().chamber());
        assertEquals(8, s.gun().count());
        assertTrue(s.back().isEmpty(), "the empty magazine comes back empty");
    }

    @Test
    void aGunWithNoMagazineGivesNothingBack() {
        MagSwap s = MagSwap.of(GunMag.NONE, mags(n("he", 8)), n("he", 8), false);
        assertNull(s.back());
        assertEquals(-1, s.pourSlot());
        assertTrue(s.gun().inserted());
    }

    @Test
    void theChangeOfContentsSendsTheBarrelsGrenadeFirst() {
        MagSwap s = MagSwap.of(gun("he", 6, true), mags(n("airburst", 8)), n("airburst", 8), false);
        assertEquals("he", s.gun().next(), "the first shot after the change is the old kind");
        assertEquals("airburst", s.gun().afterShot().next());
        assertEquals(9, s.gun().count());
        assertEquals(6, s.back().size());
        assertEquals("he", MagLogic.kind(s.back()));
    }

    @Test
    void theOldMagazinePoursIntoTheFullestUnfinishedOfItsKind() {
        // 5 left in the gun; the stock 4 HE, 8 HE (taken), 6 HE: the 5 pour into the 6, 3 are left as a stub
        MagSwap s = MagSwap.of(gun("he", 5, true), mags(n("he", 4), n("he", 8), n("he", 6)), n("he", 8), false);
        assertEquals(1, s.taken());
        assertEquals(2, s.pourSlot());
        assertEquals(8, s.pourContents().size());
        assertEquals(3, s.back().size());
    }

    @Test
    void aPourThatFitsLeavesNothingToGiveBack() {
        MagSwap s = MagSwap.of(gun("he", 2, false), mags(n("he", 8), n("he", 5)), n("he", 8), false);
        assertEquals(7, s.pourContents().size());
        assertNull(s.back());
    }

    @Test
    void aMixedMagazineIsNeverPoured() {
        GunMag g = new GunMag(List.of("he", "inc", "inc"), "he", List.of(), true, false);
        MagSwap s = MagSwap.of(g, mags(List.of("he", "inc"), n("he", 8)), n("he", 8), false);
        assertEquals(-1, s.pourSlot());
        assertEquals(List.of("he", "inc", "inc"), s.back());
    }

    @Test
    void noSwapWhenItWouldChangeNothing() {
        assertNull(MagSwap.of(gun("he", 8, true), mags(n("he", 8)), n("he", 8), false), "what is in is what is wanted");
        assertNull(MagSwap.of(gun("he", 8, true), mags(), n("he", 8), false), "none to take");
        // not the wanted contents about: a fuller or another kind is taken, a smaller one of the same kind is not
        assertNull(MagSwap.of(gun("he", 8, true), mags(n("he", 5)), n("he", 8), false));
        assertNotNull(MagSwap.of(gun("he", 5, true), mags(n("he", 6)), n("he", 8), false));
        assertNotNull(MagSwap.of(gun("he", 8, true), mags(n("ap", 2)), n("ap", 8), false));
        // the wanted contents are there and the gun's magazine is not them: take it, even a smaller one
        assertNotNull(MagSwap.of(gun("he", 8, true), mags(n("he", 5)), n("he", 5), false));
    }

    @Test
    void exactlyTheChosenContentsAreTakenNotJustTheKind() {
        List<List<String>> m = mags(n("he", 8), List.of("he", "he", "he", "he", "he", "he", "he", "ap"), n("he", 8));
        MagSwap s = MagSwap.of(gun("ap", 1, true), m, List.of("he", "he", "he", "he", "he", "he", "he", "ap"), false);
        assertEquals(1, s.taken(), "the mixed one with that exact order");
        assertEquals("ap", s.gun().next(), "the barrel stays");
        assertEquals("he", s.gun().mag().get(0));
        // the same grenades in another order are another magazine
        assertEquals(-1, MagLogic.exact(mags(List.of("ap", "he")), List.of("he", "ap")));
    }

    @Test
    void afterTheSwapTheNextAskIsNoUntilItIsCleared() {
        MagSwap s = MagSwap.of(gun("he", 1, true), mags(n("he", 8)), n("he", 8), false);
        assertTrue(s.gun().fresh());
        assertFalse(s.gun().withFresh(false).fresh());
    }

    @Test
    void semiAutoFeedsTheNextIn() {
        GunMag g = new GunMag(List.of("inc", "smoke"), "he", List.of(), true, false);
        g = g.afterShot();
        assertEquals("inc", g.chamber());
        assertEquals(List.of("smoke"), g.mag());
        g = g.afterShot();
        assertEquals("smoke", g.chamber());
        assertTrue(g.mag().isEmpty());
        g = g.afterShot();
        assertEquals(0, g.count());
        assertEquals("", g.next());
        assertEquals(0xFFFFFFFF, MagLogic.numberColor(g));
    }

    @Test
    void theNumberHasTheColourOfTheNextGrenade() {
        GunMag g = new GunMag(List.of("airburst"), "he", List.of(), true, false);
        assertEquals(GrenadeKind.HE.argb(), MagLogic.numberColor(g));
        assertEquals(GrenadeKind.AIRBURST.argb(), MagLogic.numberColor(g.afterShot()));
        assertEquals(GrenadeKind.AIRBURST.argb(), MagLogic.numberColor(new GunMag(List.of("airburst"), "", List.of(), true, false)));
    }

    @Test
    void wantedContents() {
        List<List<String>> m = mags(n("he", 3), n("smoke", 8), n("he", 8));
        assertEquals(n("he", 8), MagLogic.wantedContents(n("he", 8), false, m));
        assertEquals(n("ap", 8), MagLogic.wantedContents(n("ap", 8), true, m), "a locked gun waits for them");
        assertEquals(n("smoke", 8), MagLogic.wantedContents(n("ap", 8), false, m), "unlocked: the fullest, the first among equals");
        assertEquals(n("smoke", 8), MagLogic.wantedContents(List.of(), false, m));
        assertEquals(n("ap", 8), MagLogic.wantedContents(n("ap", 8), false, mags()));
        // the exact contents are gone: the same kind first, even a smaller one
        assertEquals(n("he", 8), MagLogic.wantedContents(n("he", 5), false, m), "no HE x5, the fullest HE");
        assertEquals(n("he", 3), MagLogic.wantedContents(n("he", 5), false, mags(n("he", 3), n("smoke", 8))));
    }

    @Test
    void reserveIsTheGrenadesInMagazinesWithTheSameContents() {
        List<List<String>> m = mags(n("he", 8), n("he", 8), n("he", 3), n("ap", 8));
        assertEquals(16, MagLogic.reserve(m, new int[] {1, 1, 1, 1}, n("he", 8)));
        assertEquals(24, MagLogic.reserve(m, new int[] {2, 1, 1, 1}, n("he", 8)), "a stack of 2 counts twice");
        assertEquals(3, MagLogic.reserve(m, new int[] {1, 1, 1, 1}, n("he", 3)));
        assertEquals(0, MagLogic.reserve(m, new int[] {1, 1, 1, 1}, List.of()));
    }

    @Test
    void wheelEntriesAreOnePerDistinctContents() {
        List<List<String>> m = mags(n("he", 8), List.of("he", "ap", "he"), n("he", 8), n("airburst", 5), List.of("ap", "he", "he"), n("he", 3));
        var e = MagLogic.entries(m, new int[] {1, 1, 2, 1, 1, 1});
        // kinds in the wheel's order, a mixed one last; the fuller first; the same grenades in another order are another entry
        assertEquals(List.of(n("airburst", 5), n("he", 8), n("he", 3), List.of("ap", "he", "he"), List.of("he", "ap", "he")), e.stream().map(MagLogic.Entry::contents).toList());
        assertEquals(3, e.get(1).magazines(), "two magazines in a stack of 2 and one in a slot of its own");
        assertEquals(1, e.get(0).magazines());
    }

    @Test
    void lowAmmoAtTwoOrFewerNotWhileReloading() {
        assertFalse(MagLogic.low(3, false));
        assertTrue(MagLogic.low(2, false));
        assertTrue(MagLogic.low(0, false));
        assertFalse(MagLogic.low(1, true));
    }

    @Test
    void everyKindHasAKeyAColourAndAPlaceInTheWheel() {
        Set<Integer> colours = new java.util.HashSet<>();
        for (GrenadeKind k : GrenadeKind.values()) {
            assertEquals(k, GrenadeKind.of(k.key));
            assertTrue(colours.add(k.rgb), k.key);
        }
        assertEquals(9, GrenadeKind.values().length);
        assertEquals(0x4FA3F7, GrenadeKind.AIRBURST.rgb);
        assertEquals(0xE5483C, GrenadeKind.HE.rgb);
        assertTrue(GrenadeKind.order("he") < GrenadeKind.order("mixed"));
        assertTrue(GrenadeKind.HE.hasNumbers());
        assertEquals("18", GrenadeKind.HE.damage);
        assertEquals(18F, GrenadeKind.HE.perShot);
        assertFalse(GrenadeKind.SMOKE.hasNumbers());
    }
}
