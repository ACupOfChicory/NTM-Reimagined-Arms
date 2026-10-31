package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

// port/continuum: a WavefrontObj group read the way NtmMeshHits and ModelMeshCapture read NTM NEXT's GroupObject
class ObjGroupTest {
    // a unit cube's bottom (a quad), one side split in two triangles, and a pentagon cap above it
    private static final float[][] POSITIONS = {
            {0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1},
            {0, 1, 0}, {1, 1, 0},
            {0, 2, 0}, {1, 2, 0}, {1.5F, 2, .5F}, {1, 2, 1}, {0, 2, 1}};
    private static final float[][] UVS = {{0, 0}, {.5F, 0}, {.5F, .5F}, {0, .5F}};
    private static final List<int[][]> FACES = List.of(
            new int[][] {{0, 0, -1}, {1, 1, -1}, {2, 2, -1}, {3, 3, -1}},
            new int[][] {{0, 0, -1}, {1, 1, -1}, {5, 2, -1}},
            new int[][] {{0, 0, -1}, {5, 2, -1}, {4, -1, -1}},
            new int[][] {{6, 0, -1}, {7, 1, -1}, {8, 2, -1}, {9, 3, -1}, {10, 0, -1}});

    @Test void facesBecomeQuadsAsWavefrontObjDrawsThem() {
        var group = ObjGroup.read("Body", FACES, POSITIONS, UVS);
        assertEquals("Body", group.name);
        float[] q = group.quads(true);
        // a quad, two triangles, a pentagon fanned into three
        assertEquals((1 + 1 + 1 + 3) * 32, q.length);
        // the quad's corners in order, with their UVs
        assertArrayEquals(new float[] {1, 0, 1, .5F, .5F}, java.util.Arrays.copyOfRange(q, 16, 21));
        // a triangle's fourth corner repeats its third
        assertArrayEquals(java.util.Arrays.copyOfRange(q, 32 + 16, 32 + 21), java.util.Arrays.copyOfRange(q, 32 + 24, 32 + 29));
        // a corner without a UV reads 0, 0
        assertArrayEquals(new float[] {0, 1, 0, 0, 0}, java.util.Arrays.copyOfRange(q, 64 + 16, 64 + 21));
        // the pentagon's last fan triangle: corners 0, 3, 4
        int last = 5 * 32;
        assertArrayEquals(new float[] {0, 2, 0}, java.util.Arrays.copyOfRange(q, last, last + 3));
        assertArrayEquals(new float[] {1, 2, 1}, java.util.Arrays.copyOfRange(q, last + 8, last + 11));
        assertArrayEquals(new float[] {0, 2, 1}, java.util.Arrays.copyOfRange(q, last + 16, last + 19));
        assertArrayEquals(new float[] {0, 0, 0, 1.5F, 2, 1}, group.bounds());
    }

    @Test void theMeshCoversTheGroupAndSkipsTheRepeatedCorner() {
        var group = ObjGroup.read("Body", FACES, POSITIONS, UVS);
        var geometry = ModelMeshCapture.geometry(group, null, 1, 1, 0, 0);
        assertArrayEquals(new float[] {0, 0, 0, 1.5F, 2, 1}, geometry.bounds());
        int triangles = 0;
        for (var mesh : geometry.meshes()) triangles += mesh.triangles().length / 9;
        // the quad is two triangles, each triangle one (its repeated corner adds none), the pentagon three
        assertEquals(2 + 1 + 1 + 3, triangles);
        var boxes = new java.util.ArrayList<ModelBox>();
        geometry.append(boxes, "Body", new Matrix4f(), 0, 0, 0);
        assertFalse(boxes.isEmpty());
        // a ray down through the bottom quad meets it; one beside the group does not
        var box = boxes.getFirst();
        assertTrue(boxes.stream().anyMatch(b -> b.clip(.5, -1, .5, .5, .5, .5) >= 0));
        assertTrue(boxes.stream().allMatch(b -> b.clip(3, -1, 3, 3, 3, 3) < 0));
        assertTrue(box.part().endsWith("Body"));
    }

    @Test void anEmptyGroupHasZeroBounds() {
        var group = ObjGroup.read("Empty", List.of(), POSITIONS, UVS);
        assertEquals(0, group.quads(false).length);
        assertArrayEquals(new float[6], group.bounds());
    }
}
