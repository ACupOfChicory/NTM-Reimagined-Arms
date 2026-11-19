package acup.gunfeel.heat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

// The server's heat decisions (OverheatBase, called by 26.2's OverheatServer as NTM ticks the gun): the cook-off's tick
// kept in 31 bits at the wrap of the bits and of the game's tick itself, and each decision the very expression
// OverheatServer had inline before (copied here as it was), bit for bit
class OverheatTickTest {
    private static final long[] ODD_TICKS = {0L, 1L, 5L, 0x1FFFFFFFL, 0x20000000L, 0x7FFFFFFEL, 0x7FFFFFFFL, 0x80000000L,
            0x80000001L, 0xFFFFFFFFL, 0x100000000L, Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE, -1L, -5L};

    @Test
    void lowKeepsTheLow31Bits() {
        assertEquals(5, OverheatBase.low(0x80000005L));
        assertEquals(0x7FFFFFFF, OverheatBase.low(-1L));
        assertEquals(0, OverheatBase.low(Long.MIN_VALUE));
    }

    @Test
    void aCookOffIsNeverZeroAndGoesExactlyItsDelayLater() {
        // the tick of the cook-off lands on the wrap of the 31 bits: 0 is none, so 1
        assertEquals(1, OverheatBase.cookAt(0x7FFFFFFFL, 1));
        assertEquals(0x7FFFFFFF, OverheatBase.cookAt(0x7FFFFFFFL - 3, 3));
        // across the wrap of the 31 bits, and of the game's tick itself (long overflow): not due before, due on the tick
        for (long start : new long[] {0x7FFFFFFEL - 20, 0xFFFFFFF0L, Long.MAX_VALUE - 15, -30L, 100L}) {
            for (int delay = OverheatBase.COOK_DELAY_MIN; delay <= OverheatBase.COOK_DELAY_MAX; delay++) {
                int cook = OverheatBase.cookAt(start, delay);
                long due = start + delay;
                for (long t = start; t != due; t++) assertFalse(OverheatBase.cookDue(cook, t), start + " " + delay + " at " + t);
                if (OverheatBase.low(due) != 0) assertTrue(OverheatBase.cookDue(cook, due), start + " " + delay);
            }
        }
    }

    @Test
    void aCookOffLongPastIsDueAndOneFarAheadToo() {
        long now = 0x7FFFFFF0L;
        int at = OverheatBase.low(now);
        assertTrue(OverheatBase.cookDue(at - 1, now), "a tick past");
        assertFalse(OverheatBase.cookDue(at + 1, now), "a tick ahead");
        // ahead by just under a quarter of the bits: waits; by a quarter or more: taken for one past (the bits wrapped)
        assertFalse(OverheatBase.cookDue((at + 0x1FFFFFFF) & 0x7FFFFFFF, now));
        assertTrue(OverheatBase.cookDue((at + 0x20000000) & 0x7FFFFFFF, now));
    }

    @Test
    void eachDecisionIsTheExpressionItWas() {
        SplittableRandom r = new SplittableRandom(20261120);
        for (int i = 0; i < 200_000; i++) {
            long now = i < ODD_TICKS.length ? ODD_TICKS[i] : r.nextInt(4) == 0 ? ODD_TICKS[r.nextInt(ODD_TICKS.length)] + r.nextInt(-40, 41) : r.nextLong();
            long last = r.nextBoolean() ? now - r.nextInt(-3, 12) : r.nextLong();
            int id = r.nextInt();
            int cook = r.nextBoolean() ? OverheatBase.low(now + r.nextInt(-50, 50)) : r.nextInt() & 0x7FFFFFFF;
            int rolled = r.nextBoolean() ? OverheatBase.low(last) : r.nextInt();
            int delay = r.nextInt(-5, 60);
            float h = r.nextInt(8) == 0 ? new float[] {0F, -0F, 0.3F, 0.30000001F, 0.8F, 1F, 1.5F, Float.MIN_VALUE}[r.nextInt(8)] : r.nextFloat() * 1.2F;
            String at = now + " " + last + " " + id + " " + cook + " " + rolled + " " + delay + " " + h;

            assertEquals((int) (now & 0x7FFFFFFFL), OverheatBase.low(now), at);
            assertEquals(now % 10 == 0, OverheatBase.wetNow(now), at);
            float extra = 1F / (1F / OverheatBase.TAU_WET - 1F / OverheatBase.TAU);
            same(OverheatBase.cooled(h, 10, extra), OverheatBase.wetCooled(h), at);
            assertEquals(h > 0.3F, OverheatBase.wetHisses(h), at);
            same(0.15F + 0.35F * h, OverheatBase.wetHissVolume(h), at);
            assertEquals(1 + (int) (3 * h), OverheatBase.steamPuffs(h), at);
            assertEquals(now % 20 == id % 20, OverheatBase.hissNow(now, id), at);
            same(0.06F + 0.12F * OverheatBase.ramp(h, OverheatBase.HISS_FROM, 1F), OverheatBase.hissVolume(h), at);
            int lastLow = (int) (last & 0x7FFFFFFFL);
            assertEquals(!(rolled == lastLow || now - last > 5), OverheatBase.cookRolls(rolled, lastLow, last, now), at);
            assertEquals(Math.max(1, (int) ((now + delay) & 0x7FFFFFFFL)), OverheatBase.cookAt(now, delay), at);
            int ahead = (cook - (int) (now & 0x7FFFFFFFL)) & 0x7FFFFFFF;
            assertEquals(!(ahead != 0 && ahead < 0x20000000), OverheatBase.cookDue(cook, now), at);
        }
    }

    private static void same(float want, float got, String at) {
        assertEquals(Float.floatToRawIntBits(want), Float.floatToRawIntBits(got), () -> at + ": " + want + " / " + got);
    }
}
