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

    @Test
    void aSlopeIsTurnedFromTheWorldsPictureIntoTheHandsByTheirFieldsOfView() {
        // the same field of view: the same slope; the world's narrower: the hand's slope is smaller in proportion to the tangents
        assertEquals(0.3, ReflexMath.handSlope(0.3, 70, 70), 1e-12);
        double k = Math.tan(Math.toRadians(35)) / Math.tan(Math.toRadians(23.45));
        assertEquals(0.1 * k, ReflexMath.handSlope(0.1, 70, 46.9), 1e-12);
        // the edge of the world's picture is the edge of the hand's
        assertEquals(Math.tan(Math.toRadians(35)), ReflexMath.handSlope(Math.tan(Math.toRadians(23.45)), 70, 46.9), 1e-12);
    }

    @Test
    void aPointInsideTheOutlineIsInsideAndOutsideIsNot() {
        double[][] square = {{0, 0}, {2, 0}, {2, 2}, {0, 2}};
        assertTrue(ReflexMath.inside(square, 1, 1));
        assertTrue(ReflexMath.inside(square, 2, 1));
        assertTrue(!ReflexMath.inside(square, 2.01, 1));
        assertTrue(!ReflexMath.inside(square, -0.1, 1));
    }

    @Test
    void theHalfLineLeavesTheOutlineWhereItShould() {
        double[][] square = {{0, 0}, {2, 0}, {2, 2}, {0, 2}};
        double[] out = ReflexMath.exitPoint(square, 1, 1, 1, 0);
        assertEquals(2.0, out[0], 1e-12);
        assertEquals(1.0, out[1], 1e-12);
        double[] diagonal = ReflexMath.exitPoint(square, 1, 1, -1, -1);
        assertEquals(0.0, diagonal[0], 1e-12);
        assertEquals(0.0, diagonal[1], 1e-12);
        double[] slanted = ReflexMath.exitPoint(square, 0.5, 0.5, 0, 3);
        assertEquals(0.5, slanted[0], 1e-12);
        assertEquals(2.0, slanted[1], 1e-12);
    }

    @Test
    void theArrowPointsWhereItIsToldTo() {
        List<double[]> arrow = ReflexMath.arrow(5, 3, 0, 1, 0.4, 0.1);
        assertEquals(5.0, arrow.get(0)[0], 1e-12);
        assertEquals(3.0, arrow.get(0)[1], 1e-12);
        // pointing up (+b): the base lies below the tip
        assertEquals(2.6, arrow.get(1)[1], 1e-12);
        assertEquals(2.6, arrow.get(2)[1], 1e-12);
        assertEquals(0.2, Math.abs(arrow.get(1)[0] - arrow.get(2)[0]), 1e-12);
    }

    @Test
    void aSegmentsDistanceIsToItsNearestPoint() {
        double[] s = {0, 0, 2, 0};
        assertEquals(1.0, ReflexMath.segmentDistance(1, 1, s), 1e-12);
        assertEquals(Math.hypot(1, 1), ReflexMath.segmentDistance(3, 1, s), 1e-12);
        assertEquals(5.0, ReflexMath.segmentDistance(3, 4, new double[] {0, 0, 0, 0}), 1e-12);
    }

    @Test
    void theStrokesJoinTouchingPixelsOnly() {
        // "7": the top row, the right side down one, then a corner step to the middle column
        List<double[]> seven = ReflexMath.strokes("7");
        assertTrue(seven.stream().anyMatch(s -> s[0] == 2.5 && s[1] == 1.5 && s[2] == 1.5 && s[3] == 2.5), "the corner step");
        // "11": nothing joins the two characters across the gap
        assertTrue(ReflexMath.strokes("11").stream().noneMatch(s -> (s[0] < 3 && s[2] > 4) || (s[2] < 3 && s[0] > 4)));
        // every lit pixel is a point of its own
        assertEquals(ReflexMath.pixels("8").size(), ReflexMath.strokes("8").stream().filter(s -> s[0] == s[2] && s[1] == s[3]).count());
    }

    @Test
    void theCoreCoversAndTheGlowOnlyAdds() {
        ReflexMath.Light l = new ReflexMath.Light(0.5, 1, 70, 0.9, 0, 0);
        // on the line: the LED's red, opaque
        assertEquals(0xFF000000 | 255 << 16 | 36 << 8 | 24, ReflexMath.light(l, 0, Double.MAX_VALUE, 255, 36, 24));
        // two pixels out: alpha 0 (nothing behind is taken), a little red
        int glow = ReflexMath.light(l, 2.0, Double.MAX_VALUE, 255, 36, 24);
        assertEquals(0, glow >>> 24);
        assertTrue((glow >> 16 & 0xFF) > 0 && (glow >> 16 & 0xFF) < 70);
        // far out: nothing
        assertEquals(0, ReflexMath.light(l, 0.5 + l.reach() + 1, Double.MAX_VALUE, 255, 36, 24));
        // the hot middle of a dot goes toward white
        ReflexMath.Light dot = new ReflexMath.Light(0.9, 0, 140, 1.1, 110, 0.6);
        assertEquals(36 + 110, ReflexMath.light(dot, 0, 0, 255, 36, 24) >> 8 & 0xFF);
    }
}
