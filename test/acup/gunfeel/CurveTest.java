package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

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
    void hasNoJumpsBetweenKeyframes() {
        for (Curve c : builtins()) {
            float step = c.length() / 2000F;
            float prev = c.at(0F);
            for (float t = step; t <= c.length(); t += step) {
                float v = c.at(t);
                assertTrue(Math.abs(v - prev) < 0.5F, "jump of " + (v - prev) + " at t=" + t);
                prev = v;
            }
        }
    }

    @Test
    void builtInShapesStartAndEndAtRest() {
        // the camera and the gun model always come back to where they were
        for (Curve c : builtins()) {
            assertEquals(0F, c.at(0F), EPS);
            assertEquals(0F, c.at(c.length()), EPS);
            assertEquals(0F, c.at(c.length() + 1F), EPS);
        }
    }

    @Test
    void builtInShapesHaveIncreasingTimes() throws ReflectiveOperationException {
        Field times = Curve.class.getDeclaredField("t");
        times.setAccessible(true);
        for (Curve c : builtins()) {
            float[] t = (float[]) times.get(c);
            for (int i = 1; i < t.length; i++) assertTrue(t[i] > t[i - 1], "time " + t[i] + " after " + t[i - 1]);
        }
    }

    @Test
    void parsesConfigText() {
        Curve c = Curve.of("0:0  0.1:4 0.2:0", Curve.PITCH);
        assertEquals(4F, c.at(.1F), EPS);
        assertEquals(.2F, c.length(), EPS);
    }

    @Test
    void keepsTheBuiltInCurveOnBadText() {
        Curve builtin = Curve.CAM_PITCH;
        assertSame(builtin, Curve.of(null, builtin));
        assertSame(builtin, Curve.of("", builtin));
        assertSame(builtin, Curve.of("   ", builtin));
        assertSame(builtin, Curve.of("0:0", builtin), "one point is not a curve");
        assertSame(builtin, Curve.of("0:0 abc:1", builtin));
        assertSame(builtin, Curve.of("0:0 0.1", builtin));
        assertSame(builtin, Curve.of("0:0 0.2:1 0.1:0", builtin), "times must go up");
        assertSame(builtin, Curve.of("0:0 0:1", builtin), "times must go up");
    }

    @Test
    void parsesTheSameTextOnce() {
        assertSame(Curve.of("0:0 0.3:1", Curve.YAW), Curve.of("0:0 0.3:1", Curve.YAW));
    }

    static List<Curve> builtins() {
        List<Curve> out = new ArrayList<>();
        for (Field f : Curve.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == Curve.class) {
                try {
                    out.add((Curve) f.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
        assertTrue(out.size() >= 8);
        return out;
    }
}
