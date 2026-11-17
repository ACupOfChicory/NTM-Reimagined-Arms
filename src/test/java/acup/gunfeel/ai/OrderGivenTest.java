package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A building fought in gives way to a commander's order only when that order is new since the foe fell (AiSoldier.fought). */
class OrderGivenTest {
    @Test
    void newOrderAfterTheFallWins() {
        AiCommander.Order o = AiCommander.fresh(1000);
        assertTrue(AiCommander.givenSince(o, 999));
    }

    @Test
    void orderFromBeforeTheFallDoesNot() {
        AiCommander.Order o = AiCommander.fresh(1000);
        assertFalse(AiCommander.givenSince(o, 1000));
        assertFalse(AiCommander.givenSince(o, 1500));
    }

    @Test
    void orderKeptOnKeepsItsTick() {
        // the staff renews the same order (its time made longer): not a new one
        AiCommander.Order o = AiCommander.fresh(1000);
        o.until = 5000;
        assertFalse(AiCommander.givenSince(o, 1200));
    }

    @Test
    void noOrderOrOneNotGivenByTheStaff() {
        assertFalse(AiCommander.givenSince(null, 0));
        assertFalse(AiCommander.givenSince(new AiCommander.Order(), Long.MIN_VALUE));
    }
}
