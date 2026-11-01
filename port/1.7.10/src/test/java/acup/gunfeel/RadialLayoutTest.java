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

    // 1.7.10's ScaledResolution: {width, height} in GUI pixels of a w x h window at guiScale (0 = auto), the
    // unicode font taking an even scale
    private static int[] scaled(int w, int h, int guiScale, boolean unicode) {
        int k = 1, most = guiScale == 0 ? 1000 : guiScale;
        while (k < most && w / (k + 1) >= 320 && h / (k + 1) >= 240) k++;
        if (unicode && k % 2 != 0 && k != 1) k--;
        return new int[] {(int) Math.ceil((double) w / k), (int) Math.ceil((double) h / k)};
    }

    // the whole menu on the screen, the hovered ammo's name and numbers too above the hotbar, at 854 x 480, 1280 x 720, 1920 x 1080
    // and GUI scale auto and 1 to 4; at the middle and full size where it fits
    @Test
    void menuFitsTheScreen() {
        int[][] windows = {{854, 480}, {1280, 720}, {1920, 1080}};
        for (int[] win : windows) for (int gs = 0; gs <= 4; gs++) for (boolean uni : new boolean[] {false, true}) {
            int[] s = scaled(win[0], win[1], gs, uni);
            float[] at = RadialLayout.fit(s[0], s[1]);
            String what = win[0] + "x" + win[1] + " scale " + gs + (uni ? " unicode" : "") + " (" + s[0] + "x" + s[1] + ")";
            float k = at[2];
            assertTrue(at[1] - RadialLayout.TOP * k >= 0, what + ": top off the screen");
            assertTrue(at[1] + RadialLayout.BOTTOM * k <= s[1] - RadialLayout.HOTBAR, what + ": the numbers over the hotbar");
            assertTrue(at[0] - RadialLayout.SIDE * k >= 0 && at[0] + RadialLayout.SIDE * k <= s[0], what + ": off the side");
            assertEquals(s[0] / 2F, at[0], 1e-6, what);
            // the smallest screen a GUI scale gives is 320 x 240: the menu fits it full size
            assertEquals(1F, k, 1e-6, what + ": scaled down");
            if (s[1] / 2F - RadialLayout.TOP >= RadialLayout.MARGIN && s[1] / 2F + RadialLayout.BOTTOM <= s[1] - RadialLayout.HOTBAR - RadialLayout.MARGIN)
                assertEquals(s[1] / 2F, at[1], 1e-6, what + ": moved though it fits at the middle");
        }
        // 854 x 480 auto is 427 x 240: at the middle the numbers would end 10 px under the bottom; moved up above the
        // hotbar instead, the top still on the screen
        int[] small = scaled(854, 480, 0, false);
        assertArrayEquals(new int[] {427, 240}, small);
        assertTrue(small[1] / 2F + RadialLayout.BOTTOM > small[1]);
        float[] at = RadialLayout.fit(427, 240);
        assertEquals(1F, at[2], 1e-6);
        assertEquals(240 - RadialLayout.HOTBAR - RadialLayout.MARGIN - RadialLayout.BOTTOM, at[1], 1e-6);
        assertTrue(at[1] - RadialLayout.TOP >= RadialLayout.MARGIN);
        // 1920 x 1080 at GUI scale 1: the middle
        assertEquals(540F, RadialLayout.fit(1920, 1080)[1], 1e-6);
    }

    // a screen lower or narrower than the menu: scaled down to fit, kept within the margins
    @Test
    void menuScalesOnATinyScreen() {
        float[] at = RadialLayout.fit(200, 120);
        assertTrue(at[2] < 1);
        assertEquals(RadialLayout.MARGIN, at[1] - RadialLayout.TOP * at[2], 1e-4);
        assertTrue(at[1] + RadialLayout.BOTTOM * at[2] <= 120 - RadialLayout.HOTBAR - RadialLayout.MARGIN + 1e-4);
        assertTrue(at[0] - RadialLayout.SIDE * at[2] >= RadialLayout.MARGIN - 1e-4);
    }

    @Test
    void captionLines() {
        assertArrayEquals(new String[] {"Патрон 12 калибра", "Пуля"}, RadialLayout.caption("Патрон 12 калибра (Пуля)"));
        assertArrayEquals(new String[] {"Box (of) shells", "Slug"}, RadialLayout.caption("Box (of) shells (Slug)"));
        assertArrayEquals(new String[] {null, "Shells"}, RadialLayout.caption("Shells"));
        assertArrayEquals(new String[] {null, "(Slug)"}, RadialLayout.caption("(Slug)"));
    }
}
