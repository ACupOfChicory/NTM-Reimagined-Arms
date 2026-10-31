package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

// The radial ammo menu's layout (RadialLayout, from 26.2's RadialMenu, 0762086): which ammo goes in, the sector under
// the pointer, the pointer's reach, the easing, the name's two lines.
class RadialLayoutTest {
    @Test
    void choicesAreWhatThePlayerHas() {
        List<String> takes = Arrays.asList("ball", "buck", "ball", "slug", "dart");
        assertEquals(Arrays.asList("ball", "slug"), RadialLayout.choices(takes, c -> c.equals("buck") || c.equals("dart") ? 0 : 3));
        // an infinite bag (-1) counts as some
        assertEquals(Arrays.asList("buck"), RadialLayout.choices(takes, c -> c.equals("buck") ? AmmoLogic.INFINITE : 0));
        List<Integer> many = new ArrayList<Integer>();
        for (int i = 0; i < 40; i++) many.add(i);
        assertEquals(RadialLayout.MAX, RadialLayout.choices(many, c -> 1).size());
        assertEquals(Integer.valueOf(15), RadialLayout.choices(many, c -> 1).get(15));
    }

    // clockwise from the top, the top sector centred on the top; y grows down as on screen
    @Test
    void sectorUnderThePointer() {
        assertEquals(-1, RadialLayout.hovered(0, 0, 4));
        assertEquals(-1, RadialLayout.hovered(9.9, 0, 4)); // the dead middle
        assertEquals(-1, RadialLayout.hovered(20, 0, 0));
        assertEquals(0, RadialLayout.hovered(0, -30, 4)); // up
        assertEquals(1, RadialLayout.hovered(30, 0, 4)); // right
        assertEquals(2, RadialLayout.hovered(0, 30, 4)); // down
        assertEquals(3, RadialLayout.hovered(-30, 0, 4)); // left
        assertEquals(0, RadialLayout.hovered(-10, -30, 4)); // a little left of up: still the top one
        assertEquals(0, RadialLayout.hovered(5, 20, 1)); // one ammo: everywhere out of the middle
        for (int n = 1; n <= RadialLayout.MAX; n++) {
            for (int i = 0; i < n; i++) {
                float a = i * RadialLayout.TAU / n;
                assertEquals(i, RadialLayout.hovered(RadialLayout.sx(40, a), RadialLayout.sy(40, a), n), n + " ammo, sector " + i);
            }
        }
    }

    @Test
    void pointerStaysWithinReach() {
        double[] p = new double[2];
        RadialLayout.move(p, 10, -5);
        assertArrayEquals(new double[] {4, -2}, p, 1e-12);
        RadialLayout.move(p, 1000, 0);
        assertEquals(RadialLayout.REACH, Math.hypot(p[0], p[1]), 1e-9);
        assertTrue(p[0] > 0 && p[1] < 0);
    }

    @Test
    void easing() {
        assertEquals(0F, RadialLayout.ease(-1));
        assertEquals(1F, RadialLayout.ease(2));
        assertEquals(0.875F, RadialLayout.ease(0.5F), 1e-6);
        assertEquals(0F, RadialLayout.in(0, 0));
        assertEquals(1F, RadialLayout.in(90, 0));
        assertEquals(0F, RadialLayout.in(22, 1)); // the next one comes 22 ms later
        assertEquals(1F, RadialLayout.open(110));
        assertEquals(0F, RadialLayout.follow(0, 0, 16));
        assertEquals(1 - (float) Math.exp(-1), RadialLayout.follow(0, 1, 50), 1e-6);
    }

    @Test
    void captionLines() {
        assertArrayEquals(new String[] {"Патрон 12 калибра", "Пуля"}, RadialLayout.caption("Патрон 12 калибра (Пуля)"));
        assertArrayEquals(new String[] {"Box (of) shells", "Slug"}, RadialLayout.caption("Box (of) shells (Slug)"));
        assertArrayEquals(new String[] {null, "Shells"}, RadialLayout.caption("Shells"));
        assertArrayEquals(new String[] {null, "(Slug)"}, RadialLayout.caption("(Slug)"));
    }
}
