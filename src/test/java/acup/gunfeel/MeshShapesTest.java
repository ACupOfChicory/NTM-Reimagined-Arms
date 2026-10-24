package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

// Bullet shapes from block models (MeshShapes.fill): a face or an edge on a cell border goes into the cell of the part
// it bounds. NTM's steel scaffold: its side frame is 0..2/16 thick, and its diagonal braces' edges lie on 2/16; the
// cell 2/16..3/16 beside it is air (BUGS.md: bullets stopped there and left no hole).
class MeshShapesTest {
    private static final MeshShapes.PixelShown ALL = (a, b, c) -> true;

    private static Set<Integer> xs(long[] bits) {
        Set<Integer> out = new TreeSet<>();
        for (int k = 0; k < 4096; k++) if ((bits[k >> 6] >> (k & 63) & 1) != 0) out.add(k & 15);
        return out;
    }

    private static double[] tri(double... p) {
        return p;
    }

    @Test
    void faceOnABorderGoesBehind() {
        // the frame's east face at x = 2/16, normal +x (counter-clockwise seen from +x)
        long[] bits = new long[65];
        MeshShapes.fill(bits, tri(0.125, 0.2, 0.2, 0.125, 0.8, 0.2, 0.125, 0.2, 0.8), true, ALL);
        assertEquals(Set.of(1), xs(bits));
    }

    @Test
    void edgeOnABorderStaysOnItsSide() {
        // a diagonal brace's face spanning x 0..2/16, slanted in y and z: its edge at x = 2/16 is not in cell 2
        long[] bits = new long[65];
        MeshShapes.fill(bits, tri(0.0, 0.0625, 0.25, 0.125, 0.0625, 0.25, 0.125, 0.4375, 0.5), true, ALL);
        MeshShapes.fill(bits, tri(0.0, 0.0625, 0.25, 0.125, 0.4375, 0.5, 0.0, 0.4375, 0.5), true, ALL);
        assertEquals(Set.of(0, 1), xs(bits));
    }

    @Test
    void doubleSidedPlaneStaysOneCellThick() {
        // a chain's plane at x = 0.5, drawn from both sides: not pushed behind, one cell
        long[] bits = new long[65];
        MeshShapes.fill(bits, tri(0.5, 0.2, 0.2, 0.5, 0.8, 0.2, 0.5, 0.2, 0.8), false, ALL);
        MeshShapes.fill(bits, tri(0.5, 0.2, 0.2, 0.5, 0.2, 0.8, 0.5, 0.8, 0.2), false, ALL);
        assertEquals(Set.of(8), xs(bits));
    }

    @Test
    void outerFacesStayInTheBlock() {
        long[] bits = new long[65];
        MeshShapes.fill(bits, tri(1.0, 0.2, 0.2, 1.0, 0.8, 0.2, 1.0, 0.2, 0.8), true, ALL);
        MeshShapes.fill(bits, tri(0.0, 0.2, 0.2, 0.0, 0.2, 0.8, 0.0, 0.8, 0.2), true, ALL);
        assertEquals(Set.of(0, 15), xs(bits));
    }

    @Test
    void hiddenPixelsLeaveNoCells() {
        long[] bits = new long[65];
        MeshShapes.fill(bits, tri(0.3, 0.2, 0.2, 0.3, 0.8, 0.2, 0.3, 0.2, 0.8), true, (a, b, c) -> false);
        assertTrue(xs(bits).isEmpty());
    }

    // Ближайшая точка треугольника: внутри грани, на ребре и у вершины.
    @Test
    void nearestPointOfATriangle() {
        RenderedMesh.Tri t = new RenderedMesh.Tri(new double[] {0.125, 0, 0, 0.125, 1, 0, 0.125, 0, 1}, new float[6], null, true, false, null, null);
        assertNear(new org.joml.Vector3d(0.125, 0.3, 0.4), t.closest(new org.joml.Vector3d(0.1875, 0.3, 0.4)));
        assertNear(new org.joml.Vector3d(0.125, 0, 0), t.closest(new org.joml.Vector3d(0.2, -0.5, -0.5)));
        assertNear(new org.joml.Vector3d(0.125, 0.5, 0.5), t.closest(new org.joml.Vector3d(0.125, 0.8, 0.8)));
        assertNear(new org.joml.Vector3d(0.125, 0.3, 0), t.closest(new org.joml.Vector3d(0.15, 0.3, -0.2)));
    }

    private static void assertNear(org.joml.Vector3d want, org.joml.Vector3d got) {
        assertTrue(want.distance(got) < 1e-9, "want " + want + " got " + got);
    }
}
