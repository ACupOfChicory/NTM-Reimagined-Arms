package acup.gunfeel.gl54;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ReflexMathTest {
    static final double[][] SQUARE = {{0, 0}, {2, 0}, {2, 2}, {0, 2}};

    static double area(List<double[]> p) {
        double a = 0;
        for (int i = 0; i < p.size(); i++) {
            double[] s = p.get(i), e = p.get((i + 1) % p.size());
            a += s[0] * e[1] - e[0] * s[1];
        }
        return Math.abs(a) / 2;
    }

    @Test
    void aShapeInsideIsKept() {
        assertEquals(1.0, area(ReflexMath.clip(ReflexMath.rect(0.5, 0.5, 1.5, 1.5), SQUARE)), 1e-12);
    }

    @Test
    void aShapeHalfOutIsCut() {
        assertEquals(1.0, area(ReflexMath.clip(ReflexMath.rect(1.0, 0.5, 3.0, 1.5), SQUARE)), 1e-12);
    }

    @Test
    void aShapeOutsideIsGone() {
        assertTrue(ReflexMath.clip(ReflexMath.rect(3, 3, 4, 4), SQUARE).isEmpty());
    }

    @Test
    void eitherWindingOfTheWindowWorks() {
        double[][] backwards = {{0, 0}, {0, 2}, {2, 2}, {2, 0}};
        assertEquals(1.0, area(ReflexMath.clip(ReflexMath.rect(1.0, 0.5, 3.0, 1.5), backwards)), 1e-12);
    }

    @Test
    void aCutCornerClipsTheDisk() {
        // a hexagon: the square with the top right corner cut off by the line x + y = 3
        double[][] hex = {{0, 0}, {2, 0}, {2, 1}, {1, 2}, {0, 2}};
        double full = area(ReflexMath.disk(1.9, 1.9, 0.3, 24));
        double cut = area(ReflexMath.clip(ReflexMath.disk(1.9, 1.9, 0.3, 24), hex));
        assertTrue(cut < full * 0.6, cut + " of " + full);
    }

    @Test
    void digitsAreThreeByFive() {
        // "1" lights 8 pixels, "8" lights 13, "-" 3
        assertEquals(8, ReflexMath.pixels("1").size());
        assertEquals(13, ReflexMath.pixels("8").size());
        assertEquals(3, ReflexMath.pixels("-").size());
        assertEquals(5, ReflexMath.pixels("+").size());
        assertEquals(7, ReflexMath.textWidth("87"));
        assertEquals(11, ReflexMath.textWidth("120"));
        // the second digit starts after the first and a gap
        assertTrue(ReflexMath.pixels("11").stream().anyMatch(p -> p[0] >= 4));
    }

    @Test
    void theRangeIsShownRoundedAndNoneAsDashes() {
        assertEquals("87", ReflexMath.rangeText(86.6F, 0));
        assertEquals("---", ReflexMath.rangeText(Float.NaN, 2));
        assertEquals("40+2", ReflexMath.rangeText(40.2F, 2));
        assertEquals("40-1", ReflexMath.rangeText(39.6F, -1));
    }

    @Test
    void theRealPanesDotAndCornerLieInsideItsOutline() {
        Gl54Reflex r = Gl54Reflex.get();
        double uL = r.uLength(), vL = r.vLength();
        double[][] window = new double[r.uv().length][];
        for (int i = 0; i < window.length; i++) window[i] = new double[] {r.uv()[i][0] * uL, r.uv()[i][1] * vL};
        double radius = ReflexDraw.DOT_PER_MILLE / 2000.0 * uL;
        List<double[]> dot = ReflexMath.disk(ReflexDraw.DOT_U * uL, ReflexDraw.DOT_V * vL, radius, 20);
        assertEquals(area(dot), area(ReflexMath.clip(dot, window)), 1e-12, "the dot is whole");
        // the lowest digit pixel nearest the pane's right edge (the screen's right seen from behind: small a)
        double px = ReflexDraw.DIGIT_PIXEL * uL;
        List<double[]> pixel = ReflexMath.rect(ReflexDraw.MARGIN_A * uL, ReflexDraw.MARGIN_B * vL, ReflexDraw.MARGIN_A * uL + px, ReflexDraw.MARGIN_B * vL + px);
        assertEquals(area(pixel), area(ReflexMath.clip(pixel, window)), 1e-12, "the digits are whole");
    }

    @Test
    void theLongestTextFitsTheCornerOfThePane() {
        // "200+3": five characters, the longest the range and the shift give, with the corner's margin, within the pane's width
        double px = ReflexDraw.DIGIT_PIXEL * Gl54Reflex.get().uLength();
        double width = (ReflexMath.textWidth("200+3") * px + ReflexDraw.MARGIN_A * Gl54Reflex.get().uLength());
        assertTrue(width < Gl54Reflex.get().uLength() * 0.5, "the text takes " + width / Gl54Reflex.get().uLength() + " of the pane's width");
    }
}
