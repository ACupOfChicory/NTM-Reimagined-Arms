package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

// The radial menu's numbers: the sector under the pointer clockwise from the top, the dead centre, the reach, and a
// frame's easing.
class RadialDialTest {
    private static final long MS = 1_000_000L;

    @Test
    void sectorsClockwiseFromTheTop() {
        RadialDial.open(4, 0);
        assertEquals(-1, RadialDial.hovered(4), "the centre");
        RadialDial.move(0, -60);
        assertEquals(0, RadialDial.hovered(4), "up");
        RadialDial.open(4, 0);
        RadialDial.move(60, 0);
        assertEquals(1, RadialDial.hovered(4), "right");
        RadialDial.open(4, 0);
        RadialDial.move(0, 60);
        assertEquals(2, RadialDial.hovered(4), "down");
        RadialDial.open(4, 0);
        RadialDial.move(-60, 0);
        assertEquals(3, RadialDial.hovered(4), "left");
    }

    @Test
    void deadCentreAndReach() {
        RadialDial.open(4, 0);
        RadialDial.move(24, 0); // 9.6 out: still the centre
        assertEquals(-1, RadialDial.hovered(4));
        RadialDial.move(1000, 0); // held at the reach, 50
        RadialDial.move(-100, 0); // 50 - 40 = 10: just out of the centre
        assertEquals(1, RadialDial.hovered(4));
        RadialDial.move(-1, 0);
        assertEquals(-1, RadialDial.hovered(4));
    }

    @Test
    void frameStepsHeldTo50Ms() {
        RadialDial.open(2, 1000 * MS);
        assertEquals(20F, RadialDial.frame(1020 * MS));
        assertEquals(50F, RadialDial.frame(3000 * MS));
        assertEquals(2000F, RadialDial.since(3000 * MS));
    }

    @Test
    void hoveredSlidesOutOthersIn() {
        RadialDial.open(2, 0);
        RadialDial.settle(2, 0, 50);
        float k = 1 - (float) Math.exp(-1F);
        assertArrayEquals(new float[] {k, 0}, RadialDial.h);
        assertEquals(k, RadialDial.any);
        RadialDial.settle(2, -1, 50);
        assertEquals(k - k * k, RadialDial.any, 1e-7F);
    }

    @Test
    void sectorsComeInClockwiseAndTheMenuOpens() {
        assertArrayEquals(new float[] {RadialDial.ease(44 / 90F), RadialDial.ease(22 / 90F), 0}, RadialDial.in(3, 44));
        assertEquals(0F, RadialDial.ease(-1));
        assertEquals(0.875F, RadialDial.ease(0.5F));
        assertEquals(1F, RadialDial.ease(2));
        assertEquals(1F, RadialDial.open(110));
        assertEquals(0.88F, RadialDial.scale(0));
        assertEquals(1F, RadialDial.scale(1), 1e-6F);
    }
}
