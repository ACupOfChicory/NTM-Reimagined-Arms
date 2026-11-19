package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;


import org.junit.jupiter.api.Test;

class CurveTest {
    private static final float EPS = 1e-5F;

    @Test
    void passesThroughEveryKeyframe() {
        Curve c = new Curve(0F, 0F, .1F, 2F, .3F, -1F, .5F, 0F);
        assertEquals(0F, c.at(0F), EPS);
        assertEquals(2F, c.at(.1F), EPS);
        assertEquals(-1F, c.at(.3F), EPS);
        assertEquals(0F, c.at(.5F), EPS);
    }

    @Test
    void holdsTheEndValuesOutsideItsRange() {
        Curve c = new Curve(.1F, 3F, .2F, 5F);
        assertEquals(3F, c.at(-1F), EPS);
        assertEquals(3F, c.at(0F), EPS);
        assertEquals(5F, c.at(.2F), EPS);
        assertEquals(5F, c.at(10F), EPS);
        assertEquals(.2F, c.length(), EPS);
    }

    @Test
    void parsesConfigText() {
        Curve c = Curve.of("0:0  0.1:4 0.2:0");
        assertEquals(4F, c.at(.1F), EPS);
        assertEquals(.2F, c.length(), EPS);
    }

    @Test
    void givesNothingOnBadText() {
        assertNull(Curve.of(null));
        assertNull(Curve.of(""));
        assertNull(Curve.of("   "));
        assertNull(Curve.of("0:0"), "one point is not a curve");
        assertNull(Curve.of("0:0 abc:1"));
        assertNull(Curve.of("0:0 0.1"));
        assertNull(Curve.of("0:0 0.2:1 0.1:0"), "times must go up");
        assertNull(Curve.of("0:0 0:1"), "times must go up");
        assertNull(Curve.of("8,0.04,0.3"), "a spring is not a curve");
    }

    @Test
    void parsesTheSameTextOnce() {
        assertSame(Curve.of("0:0 0.3:1"), Curve.of("0:0 0.3:1"));
    }
}
