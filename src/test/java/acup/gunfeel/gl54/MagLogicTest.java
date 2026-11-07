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

// The rules of the 20 mm magazines on plain lists (MagLogic, MagSwap, GunMag): kinds, the fullest, the pour, the swap with its
// 8 + 1 and 8, the semi-automatic feed, the wheel's label and the HUD's numbers.
class MagLogicTest {
    static List<String> n(String key, int count) {
        return new ArrayList<>(Collections.nCopies(count, key));
    }

    static GunMag gun(String key, int inMag, boolean barrel) {
        return new GunMag(n(key, inMag), barrel ? key : "", key, true, false);
    }

    @Test
    void kindOfAMagazine() {
        assertEquals("empty", MagLogic.kind(List.of()));
        assertEquals("he", MagLogic.kind(n("he", 8)));
        assertEquals("he", MagLogic.kind(n("he", 1)));
        assertEquals("mixed", MagLogic.kind(List.of("he", "he", "inc")));
    }

    @Test
    void fullestTakesTheFirstAmongEquals() {
        List<List<String>> mags = new ArrayList<>();
        mags.add(n("he", 3));
        mags.add(null);
        mags.add(n("ap", 8));
        mags.add(n("he", 8));
        mags.add(n("he", 8));
        mags.add(n("he", 5));
        assertEquals(3, MagLogic.fullest(mags, "he"));
        assertEquals(2, MagLogic.fullest(mags, "ap"));
        assertEquals(-1, MagLogic.fullest(mags, "smoke"));
        // mixed: one kind, the fullest of any mixed
        mags.add(List.of("he", "inc", "inc"));
        mags.add(List.of("he", "inc", "inc", "he", "he"));
        assertEquals(6 + 1, MagLogic.fullest(mags, "mixed"));
    }

    @Test
    void pourTargetIsTheFullestUnfinishedOfTheKind() {
        List<List<String>> mags = new ArrayList<>(List.of(n("he", 8), n("he", 2), n("he", 6), n("he", 6), n("ap", 7)));
        assertEquals(2, MagLogic.pourTarget(mags, "he"));
        assertEquals(4, MagLogic.pourTarget(mags, "ap"));
        assertEquals(-1, MagLogic.pourTarget(mags, "mixed"));
        assertEquals(-1, MagLogic.pourTarget(mags, "empty"));
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
        // 3 + 1 in the gun, a full magazine of the same kind: 8 + 1 = 9
        GunMag g = gun("he", 3, true);
        MagSwap s = MagSwap.of(g, new ArrayList<>(List.of(n("he", 8))), "he", false);
        assertNotNull(s);
        assertEquals(8, s.gun().mag().size());
        assertEquals("he", s.gun().chamber());
        assertEquals(9, s.gun().count());
        assertTrue(s.gun().fresh(), "the decider asks canReload once more: no");
        // no other magazine to pour into: the three come back as a magazine of their own
        assertEquals(3, s.back().size());
        assertEquals(-1, s.pourSlot());
    }

    @Test
    void anEmptyGunIsFedTheFirst() {
        GunMag g = new GunMag(List.of(), "", "he", true, false);
        MagSwap s = MagSwap.of(g, new ArrayList<>(List.of(n("he", 8))), "he", false);
        assertEquals(7, s.gun().mag().size());
        assertEquals("he", s.gun().chamber());
        assertEquals(8, s.gun().count());
        // the empty magazine comes back empty
        assertTrue(s.back().isEmpty());
    }

    @Test
    void aGunWithNoMagazineGivesNothingBack() {
        MagSwap s = MagSwap.of(GunMag.NONE, new ArrayList<>(List.of(n("he", 8))), "he", false);
        assertNull(s.back());
        assertEquals(-1, s.pourSlot());
        assertTrue(s.gun().inserted());
    }

    @Test
    void theChangeOfKindSendsTheBarrelsGrenadeFirst() {
        GunMag g = gun("he", 6, true);
        MagSwap s = MagSwap.of(g, new ArrayList<>(List.of(n("airburst", 8))), "airburst", false);
        assertEquals("he", s.gun().next(), "the first shot after the change is the old kind");
        GunMag a = s.gun().afterShot();
        assertEquals("airburst", a.next());
        assertEquals("airburst", s.gun().sel());
        assertEquals(9, s.gun().count());
        // the old six HE go back as a magazine
        assertEquals(6, s.back().size());
        assertEquals("he", MagLogic.kind(s.back()));
    }

    @Test
    void theOldMagazinePoursIntoTheFullestUnfinishedOfItsKind() {
        // 5 left in the gun; inventory: 8 HE (taken), 4 HE and 6 HE: the 5 pour into the 6, 3 are left as a stub
        GunMag g = gun("he", 5, true);
        List<List<String>> mags = new ArrayList<>(List.of(n("he", 4), n("he", 8), n("he", 6)));
        MagSwap s = MagSwap.of(g, mags, "he", false);
        assertEquals(1, s.taken());
        assertEquals(2, s.pourSlot());
        assertEquals(8, s.pourContents().size());
        assertEquals(3, s.back().size());
    }

    @Test
    void aPourThatFitsLeavesNothingToGiveBack() {
        GunMag g = gun("he", 2, false);
        List<List<String>> mags = new ArrayList<>(List.of(n("he", 8), n("he", 5)));
        MagSwap s = MagSwap.of(g, mags, "he", false);
        assertEquals(7, s.pourContents().size());
        assertNull(s.back());
    }

    @Test
    void aMixedMagazineIsNeverPoured() {
        GunMag g = new GunMag(List.of("he", "inc", "inc"), "he", "mixed", true, false);
        List<List<String>> mags = new ArrayList<>(List.of(List.of("he", "inc"), n("he", 8)));
        MagSwap s = MagSwap.of(g, mags, "he", false);
        assertEquals(-1, s.pourSlot());
        assertEquals(List.of("he", "inc", "inc"), s.back());
    }

    @Test
    void noSwapWhenItWouldNotHoldMore() {
        GunMag g = gun("he", 8, true);
        assertNull(MagSwap.of(g, new ArrayList<>(List.of(n("he", 8))), "he", false));
        assertNull(MagSwap.of(g, new ArrayList<>(List.of(n("he", 5))), "he", false));
        assertNull(MagSwap.of(g, new ArrayList<>(List.of(n("ap", 0))), "ap", false));
        // a fuller one is taken, and so is another kind
        assertNotNull(MagSwap.of(gun("he", 5, true), new ArrayList<>(List.of(n("he", 6))), "he", false));
        assertNotNull(MagSwap.of(gun("he", 8, true), new ArrayList<>(List.of(n("ap", 2))), "ap", false));
    }

    @Test
    void afterTheSwapTheNextAskIsNoUntilItIsCleared() {
        MagSwap s = MagSwap.of(gun("he", 1, true), new ArrayList<>(List.of(n("he", 8))), "he", false);
        assertTrue(s.gun().fresh());
        assertFalse(s.gun().withFresh(false).fresh());
    }

    @Test
    void semiAutoFeedsTheNextIn() {
        GunMag g = new GunMag(List.of("inc", "smoke"), "he", "mixed", true, false);
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
        GunMag g = new GunMag(List.of("airburst"), "he", "mixed", true, false);
        assertEquals(GrenadeKind.HE.argb(), MagLogic.numberColor(g));
        assertEquals(GrenadeKind.AIRBURST.argb(), MagLogic.numberColor(g.afterShot()));
        // with the barrel empty: the magazine's first
        assertEquals(GrenadeKind.AIRBURST.argb(), MagLogic.numberColor(new GunMag(List.of("airburst"), "", "", true, false)));
    }

    @Test
    void wantedKind() {
        Set<String> there = Set.of("he", "smoke");
        assertEquals("he", MagLogic.wanted("he", false, there::contains));
        assertEquals("ap", MagLogic.wanted("ap", true, there::contains), "a locked gun waits for its kind");
        assertEquals("he", MagLogic.wanted("ap", false, there::contains), "unlocked: the first found in the wheel's order");
        assertEquals("smoke", MagLogic.wanted("ap", false, Set.of("smoke")::contains));
        assertEquals("mixed", MagLogic.wanted("ap", false, Set.of("mixed")::contains));
        assertEquals("ap", MagLogic.wanted("ap", false, Set.<String>of()::contains));
        assertEquals("he", MagLogic.wanted("", false, there::contains));
    }

    @Test
    void wheelLabel() {
        assertEquals("2×8 + 5", MagLogic.label(2, 5, false));
        assertEquals("2×8+5", MagLogic.label(2, 5, true));
        assertEquals("2×8", MagLogic.label(2, 0, false));
        assertEquals("5", MagLogic.label(0, 5, false));
        List<List<String>> mags = new ArrayList<>(List.of(n("he", 8), n("he", 8), n("he", 3), n("he", 2), n("ap", 8)));
        MagLogic.Stock st = MagLogic.stock(mags, "he");
        assertEquals(21, st.rounds());
        assertEquals("2×8 + 5", MagLogic.label(st.full(), st.rest(), false));
        assertEquals(21, MagLogic.reserve(mags, "he"));
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
