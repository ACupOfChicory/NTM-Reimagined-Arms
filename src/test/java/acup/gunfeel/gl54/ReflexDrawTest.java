package acup.gunfeel.gl54;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

// Where the aim marker lands on the pane: the ray from the eye with the hand's slopes crossing the pane's plane.
class ReflexDrawTest {
    // a pane 2 wide and 1 high, upright, in the plane z = -3 of the hand's space, its corner (u = 0, v = 0) at (-1, -0.5, -3);
    // identity pose: the model's space is the view's
    static Gl54Reflex pane() {
        double[][] corners = {{-1, -0.5, -3}, {1, -0.5, -3}, {1, 0.5, -3}, {-1, 0.5, -3}};
        double[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        return new Gl54Reflex(true, corners, uv, new double[] {-1, -0.5, -3}, new double[] {1, 0, 0}, 2.0, new double[] {0, 1, 0}, 1.0,
                new double[] {0, 0, 1}, new double[] {0, 0, 0});
    }

    @Test
    void theSlopeOfZeroLandsWhereTheViewAxisCrossesThePane() {
        double[] at = ReflexDraw.markerAt(pane(), new Matrix4f(), 0, 0);
        assertNotNull(at);
        assertEquals(1.0, at[0], 1e-6);
        assertEquals(0.5, at[1], 1e-6);
    }

    @Test
    void aSlopeMovesItByTheSlopeTimesTheDepth() {
        // 0.1 to the side and 0.2 up over a depth of 3: 0.3 and 0.6 on the pane
        double[] at = ReflexDraw.markerAt(pane(), new Matrix4f(), 0.1, 0.2);
        assertEquals(1.0 + 0.3, at[0], 1e-5);
        assertEquals(0.5 + 0.6, at[1], 1e-5);
    }

    @Test
    void theSameWorldDirectionLandsWhereTheGunIsNotAndTurningTheViewSlidesItTheOtherWay() {
        // the world direction is fixed; when the view turns by an angle d to the left the direction's slope grows by about tan(d)
        double before = ReflexDraw.markerAt(pane(), new Matrix4f(), Math.tan(Math.toRadians(2)), 0)[0];
        double after = ReflexDraw.markerAt(pane(), new Matrix4f(), Math.tan(Math.toRadians(7)), 0)[0];
        assertEquals(3.0 * (Math.tan(Math.toRadians(7)) - Math.tan(Math.toRadians(2))), after - before, 1e-5);
    }

    @Test
    void aPaneSeenEdgeOnIsNone() {
        Gl54Reflex edge = new Gl54Reflex(true, new double[0][], new double[0][], new double[] {0, 0, -3}, new double[] {1, 0, 0}, 1.0, new double[] {0, 0, 1}, 1.0,
                new double[] {0, 1, 0}, new double[] {0, 0, 0});
        // normal along y, the ray runs flat: parallel
        assertNull(ReflexDraw.markerAt(edge, new Matrix4f(), 0.5, 0));
    }

    @Test
    void aPaneBehindTheEyeIsNone() {
        Gl54Reflex behind = new Gl54Reflex(true, new double[0][], new double[0][], new double[] {0, 0, 3}, new double[] {1, 0, 0}, 1.0, new double[] {0, 1, 0}, 1.0,
                new double[] {0, 0, 1}, new double[] {0, 0, 0});
        assertNull(ReflexDraw.markerAt(behind, new Matrix4f(), 0, 0));
    }
}
