package acup.gunfeel.crosshair;

import java.util.ArrayList;
import java.util.List;
// The dynamic crosshair's styles. Look and behaviour after the dynamic crosshair of TACZ Refabricated (Sh1roCu, The
// Unnamed), itself after TaCZ: Labs by Txt-Text (GPL-3.0); written here from how it looks and moves, no code taken.
// Lines one GUI pixel wide, each with a shadow a pixel right and down. `gap` is how far the lines stand off the
// middle pixel: the style's own base, plus the spread, plus the shot's jump (DynamicCrosshair).
public enum CrosshairStyle {
    /** four strokes of 6 and a dot: rifles, snipers, machine guns, the rest */
    CROSS("cross", 4),
    /** two corners at the sides and a notch under the middle: pistols, revolvers */
    CORNER("corner", 4),
    /** brackets [ · ] 13 high with 3-pixel ends: SMGs */
    BRACKETS("brackets", 6),
    /** four sides of 13 open at the corners and a dot: shotguns */
    SQUARE("square", 9),
    /** ticks of 8 to the sides and a scale down with three marks, and a dot: launchers, the heaviest */
    RULER("ruler", 3);

    public final String key;
    public final int base;

    CrosshairStyle(String key, int base) {
        this.key = key;
        this.base = base;
    }

    /** the style's rectangles [x0, y0, x1, y1] round the middle pixel (x, y): x0 ≤ x < x1, y0 ≤ y < y1 */
    public List<int[]> rects(int x, int y, int g) {
        List<int[]> out = new ArrayList<>();
        switch (this) {
            case CROSS -> {
                r(out, x, y - g - 6, x + 1, y - g);
                r(out, x, y + g, x + 1, y + g + 6);
                r(out, x - g - 6, y, x - g, y + 1);
                r(out, x + g, y, x + g + 6, y + 1);
            }
            case CORNER -> {
                r(out, x - g - 5, y, x - g + 1, y + 1);
                r(out, x - g, y, x - g + 1, y + 4);
                r(out, x + g, y, x + g + 6, y + 1);
                r(out, x + g, y, x + g + 1, y + 4);
                r(out, x - 1, y + 1, x + 2, y + 2);
                return out;
            }
            case BRACKETS -> {
                r(out, x - g, y - 6, x - g + 1, y + 7);
                r(out, x - g, y - 6, x - g + 3, y - 5);
                r(out, x - g, y + 6, x - g + 3, y + 7);
                r(out, x + g, y - 6, x + g + 1, y + 7);
                r(out, x + g - 2, y - 6, x + g + 1, y - 5);
                r(out, x + g - 2, y + 6, x + g + 1, y + 7);
            }
            case SQUARE -> {
                r(out, x - 6, y - g, x + 7, y - g + 1);
                r(out, x - 6, y + g, x + 7, y + g + 1);
                r(out, x - g, y - 6, x - g + 1, y + 7);
                r(out, x + g, y - 6, x + g + 1, y + 7);
            }
            case RULER -> {
                r(out, x - g - 8, y, x - g, y + 1);
                r(out, x + g, y, x + g + 8, y + 1);
                r(out, x, y + g, x + 1, y + g + 18);
                r(out, x - 4, y + g + 6, x + 5, y + g + 7);
                r(out, x - 3, y + g + 12, x + 4, y + g + 13);
                r(out, x - 2, y + g + 18, x + 3, y + g + 19);
            }
        }
        r(out, x, y, x + 1, y + 1);
        return out;
    }

    private static void r(List<int[]> out, int a, int b, int c, int d) {
        out.add(new int[] {Math.min(a, c), Math.min(b, d), Math.max(a, c), Math.max(b, d)});
    }
}
