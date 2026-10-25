package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelBoxTest {
    private static ModelBox unit() {
        return new ModelBox("body", 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1);
    }

    @Test void segmentAndStartInside() {
        assertEquals(1.0 / 3, unit().clip(-1, .5, .5, 2, .5, .5), 1e-12);
        assertEquals(0, unit().clip(.5, .5, .5, 2, .5, .5));
        assertEquals(0, unit().clip(.5, .5, .5, .5, .5, .5));
        assertEquals(-1, unit().clip(2, .5, .5, 3, .5, .5));
        assertEquals(-1, unit().clip(-1, 1.1, .5, 2, 1.1, .5));
        assertEquals(-1, unit().clip(-1, .5, .5, -.1, .5, .5));
    }

    @Test void gapsBetweenLegsStayEmpty() {
        var legs = List.of(new ModelBox("left_leg", -.4, 0, -.1, .2, 0, 0, 0, 1, 0, 0, 0, .2),
                new ModelBox("right_leg", .2, 0, -.1, .2, 0, 0, 0, 1, 0, 0, 0, .2));
        assertTrue(legs.stream().noneMatch(b -> b.clip(0, .5, -1, 0, .5, 1) >= 0));
        assertTrue(legs.stream().anyMatch(b -> b.clip(.3, .5, -1, .3, .5, 1) >= 0));
    }

    @Test void rotatedCubeMissesInsideItsEnclosingBox() {
        double s = Math.sqrt(.5);
        ModelBox rotated = new ModelBox("arm", 0, 0, 0, s, s, 0, -s, s, 0, 0, 0, 1);
        assertEquals(-1, rotated.clip(.65, .05, -1, .65, .05, 2));
        assertEquals(1.0 / 3, rotated.clip(0, s, -1, 0, s, 2), 1e-12);
    }

    @Test void nonuniformParentScaleAndRotatedChildCanShear() {
        ModelBox sheared = new ModelBox("arm", 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 0, 1);
        assertEquals(-1, sheared.clip(.1, .9, -1, .1, .9, 2));
        assertEquals(1.0 / 3, sheared.clip(1, .5, -1, 1, .5, 2), 1e-12);
    }

    @Test void reflectedAndThinCubes() {
        ModelBox reflected = new ModelBox("head", 1, 0, 0, -1, 0, 0, 0, 1, 0, 0, 0, 1);
        assertEquals(1.0 / 3, reflected.clip(-1, .5, .5, 2, .5, .5), 1e-12);
        ModelBox thin = new ModelBox("ear", 0, 0, 0, .001, 0, 0, 0, 1, 0, 0, 0, .001);
        assertTrue(thin.valid(2));
        assertTrue(thin.clip(-1, .5, .0005, 1, .5, .0005) >= 0);
    }

    @Test void rejectsInvalidAndOversizedGeometry() {
        assertTrue(unit().valid(2));
        assertFalse(unit().valid(.5));
        assertFalse(new ModelBox("body", Double.NaN, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1).valid(2));
        assertFalse(new ModelBox("body", 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1).valid(2));
        assertEquals(-1, unit().clip(Double.NaN, 0, 0, 1, 1, 1));
    }
}
