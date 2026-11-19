package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// What a reload may keep: paid from the reserve, what the reserve had not taken back out; endless pays nothing.
class AiAmmoTest {
    @Test
    void aReloadIsPaidFromTheReserve() {
        AiAmmo a = new AiAmmo(24);
        assertEquals(6, a.settle(6), "the first look only notes the gun");
        assertEquals(24, a.reserve());
        assertEquals(0, a.settle(0), "shots");
        assertEquals(6, a.settle(6), "a reload");
        assertEquals(18, a.reserve());
        assertEquals(6, a.loadedTotal);
    }

    @Test
    void whatTheReserveHasNotIsTakenBackOut() {
        AiAmmo a = new AiAmmo(2);
        a.settle(0);
        assertEquals(2, a.settle(6));
        assertEquals(0, a.reserve());
        assertFalse(a.any());
        assertEquals(1, a.settle(1));
        assertEquals(1, a.settle(6), "nothing left: a reload from nothing keeps nothing");
    }

    @Test
    void endlessPaysNothingAndGivingAddsUp() {
        AiAmmo a = new AiAmmo(AiAmmo.INFINITE);
        a.settle(0);
        assertEquals(30, a.settle(30));
        assertTrue(a.infinite());
        a.give(10);
        assertTrue(a.infinite(), "endless stays endless");
        AiAmmo b = new AiAmmo(0);
        b.give(12);
        assertEquals(12, b.reserve());
        b.set(5);
        assertEquals(5, b.reserve());
    }
}
