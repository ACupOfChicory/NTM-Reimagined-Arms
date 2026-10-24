package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpringTest {
    private static final float EPS = 1e-4F;
    private static final Spring[] SOME = {
            new Spring(10F, 0.04F, 0.35F, 0.2F, 0F, 0F),
            new Spring(-3F, 0.01F, 0.6F, 0F, 0.03F, 0F),
            new Spring(2F, 0.01F, 0.3F, 0F, 0F, 18F),
            new Spring(1F, 0F, 0F, 0.9F, 0F, 0F),
    };

    @Test
    void startsAndEndsAtRest() {
        // the camera and the gun model always come back to where they were
        for (Spring s : SOME) {
            assertEquals(0F, s.at(0F), EPS);
            assertEquals(0F, s.at(-1F), EPS);
            assertEquals(0F, s.at(s.length()), EPS);
            assertEquals(0F, s.at(s.length() + 1F), EPS);
        }
    }

    @Test
    void peaksAtAmountAfterDelayAndAttack() {
        for (Spring s : SOME) assertEquals(s.amount(), s.at(s.delay() + s.attack()), EPS * 10);
        Spring s = SOME[0];
        float peak = 0F;
        for (float t = 0F; t < s.length(); t += 1e-4F) peak = Math.max(peak, Math.abs(s.at(t)));
        assertEquals(10F, peak, 1e-3F, "nothing goes past the amount");
    }

    @Test
    void swingsBackByBounce() {
        Spring s = new Spring(10F, 0.04F, 0.5F, 0.3F, 0F, 0F);
        float low = 0F;
        for (float t = 0F; t < s.length(); t += 1e-4F) low = Math.min(low, s.at(t));
        assertEquals(-3F, low, 0.01F);
        Spring none = new Spring(10F, 0.04F, 0.5F, 0F, 0F, 0F);
        for (float t = 0F; t < none.length(); t += 1e-4F) assertTrue(none.at(t) >= 0F, "no bounce, no swing at " + t);
    }

    @Test
    void hasNoJumps() {
        // the last one swings a thousand times a second: steps of 0.1 ms are too coarse for it
        for (Spring s : java.util.Arrays.copyOf(SOME, 3)) {
            float step = 1e-4F, prev = s.at(0F);
            for (float t = step; t <= s.length() + 0.01F; t += step) {
                float v = s.at(t);
                assertTrue(Math.abs(v - prev) < Math.abs(s.amount()) * 0.05F, "jump of " + (v - prev) + " at t=" + t + " in " + s);
                prev = v;
            }
        }
    }

    @Test
    void readsWhatItWrites() {
        for (Spring s : SOME) assertEquals(s, Spring.parse(s.toString()));
        Spring short_ = Spring.parse("4, 0.05");
        assertNotNull(short_);
        assertEquals(4F, short_.amount());
        assertEquals(0.05F, short_.attack());
        assertEquals(Spring.MIN_SETTLE, short_.settle());
    }

    @Test
    void refusesOtherText() {
        assertNull(Spring.parse(null));
        assertNull(Spring.parse(""));
        assertNull(Spring.parse("0:0 0.1:5"), "a curve is not a spring");
        assertNull(Spring.parse("1,2,x"));
        assertNull(Spring.parse("1,2,3,4,5,6,7"));
        assertNull(Spring.parse("NaN,1"));
    }
}
