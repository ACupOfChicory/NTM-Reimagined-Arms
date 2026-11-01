package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

// A hole on plain cubes wrapped over their edges (BlockDecal, 26.2's drawnHole): the whole square is laid on the faces
// around, none of it is cut off at an edge, and every piece lies on its face.
class BlockDecalTest {
    static final int UP = 1, EAST = 5;

    private static BlockDecal.Cubes cubes(int[]... at) {
        Set<String> s = new HashSet<String>();
        for (int[] p : at) s.add(p[0] + " " + p[1] + " " + p[2]);
        return (x, y, z) -> s.contains(x + " " + y + " " + z);
    }

    // the pieces' area in the hole's texture (a, b): 1 = the whole square is laid somewhere
    private static double area(List<double[]> pieces) {
        double sum = 0;
        for (double[] pc : pieces) {
            int n = (pc.length - 3) / 5;
            for (int i = 0; i < n; i++) {
                int a = 3 + i * 5, b = 3 + (i + 1) % n * 5;
                sum += pc[a + 3] * pc[b + 4] - pc[b + 3] * pc[a + 4];
            }
        }
        return Math.abs(sum) / 2;
    }

    private static void onFaces(List<double[]> pieces) {
        for (double[] pc : pieces) {
            int n = (pc.length - 3) / 5;
            double plane = pc[3] * pc[0] + pc[4] * pc[1] + pc[5] * pc[2];
            for (int i = 1; i < n; i++) {
                int o = 3 + i * 5;
                assertEquals(plane, pc[o] * pc[0] + pc[o + 1] * pc[1] + pc[o + 2] * pc[2], 1e-9, "a piece off its face");
            }
        }
    }

    @Test
    void aSquareInsideItsFaceStaysFlat() {
        assertNull(BlockDecal.wrap(new int[] {10, 64, -3}, UP, new double[] {10.5, 65, -2.5}, 0.2, cubes(new int[] {10, 64, -3})));
    }

    // the top face's east edge of a lone cube: part on the top, the rest folded down the east face
    @Test
    void overAnOuterEdgeItFoldsOntoTheSide() {
        int[] pos = {10, 64, -3};
        List<double[]> pieces = BlockDecal.wrap(pos, UP, new double[] {10.9, 65, -2.5}, 0.2, cubes(pos));
        assertNotNull(pieces);
        assertEquals(1, area(pieces), 1e-9);
        onFaces(pieces);
        boolean side = false;
        for (double[] pc : pieces) if (pc[0] == 1) {
            side = true;
            int n = (pc.length - 3) / 5;
            for (int i = 0; i < n; i++) {
                int o = 3 + i * 5;
                assertEquals(11, pc[o], 1e-9);
                // folded: 0.1 of the square's 0.4 lies on the side, from the edge down
                assertTrue(pc[o + 1] >= 65 - 0.1 - 1e-9 && pc[o + 1] <= 65 + 1e-9);
                assertTrue(pc[o + 3] >= 0.75 - 1e-9, "the far part of the texture goes down the side");
            }
        }
        assertTrue(side, "nothing on the east face");
    }

    // a cube east of it continues the top face: the square goes on over the seam, nothing on the covered side
    @Test
    void overASeamItStaysInThePlane() {
        int[] pos = {10, 64, -3};
        List<double[]> pieces = BlockDecal.wrap(pos, UP, new double[] {10.9, 65, -2.5}, 0.2, cubes(pos, new int[] {11, 64, -3}));
        assertNotNull(pieces);
        assertEquals(1, area(pieces), 1e-9);
        onFaces(pieces);
        for (double[] pc : pieces) assertEquals(1, pc[1], 1e-12, "a piece off the top plane");
    }

    // a cube placed or broken next to the hole changes what it lies on
    @Test
    void aroundSeesANeighbourChange() {
        int[] pos = {10, 64, -3};
        long[] alone = BlockDecal.around(pos, 0.2, cubes(pos)), paired = BlockDecal.around(pos, 0.2, cubes(pos, new int[] {11, 64, -3}));
        assertTrue(!java.util.Arrays.equals(alone, paired));
    }
}
