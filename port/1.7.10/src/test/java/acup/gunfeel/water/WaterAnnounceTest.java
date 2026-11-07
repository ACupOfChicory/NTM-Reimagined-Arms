package acup.gunfeel.water;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// port: a round with an impact fuze in water sends its motion to the players (WaterTick.announce) when it starts to
// fly there or to sink, and again only every RESEND ticks: never more than one S12 a tick, a few for a whole sinking.
class WaterAnnounceTest {
    @Test
    void aNewKindIsSentAtOnceTheSameOneOnlyAfterResend() {
        long sent = 100L << 2 | 1; // flying, sent at 100
        assertFalse(WaterTick.due(sent, 1, 101));
        assertFalse(WaterTick.due(sent, 1, 100 + WaterTick.RESEND - 1));
        assertTrue(WaterTick.due(sent, 1, 100 + WaterTick.RESEND));
        assertTrue(WaterTick.due(sent, 2, 101));
        assertTrue(WaterTick.due(0L, 1, 5));
    }

    @Test
    void aSinkingOfOneHundredTicksSendsSix() {
        long sent = 0;
        int n = 0;
        for (long t = 1000; t < 1100; t++) {
            int kind = t < 1003 ? 1 : 2; // three ticks flying in water, then sinking
            if (WaterTick.due(sent, kind, t)) {
                sent = t << 2 | kind;
                n++;
            }
        }
        // flying at 1000; sinking at 1003, 1023, 1043, 1063, 1083
        assertTrue(n == 6, "sent " + n);
    }
}
