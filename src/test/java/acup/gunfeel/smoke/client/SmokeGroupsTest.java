package acup.gunfeel.smoke.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The drawn clouds in groups: those whose boxes meet are marched as one smoke, the groups far to near. */
class SmokeGroupsTest {
    private static double[] box(double x, double y, double z, double size) {
        return new double[] {x, y, z, x + size, y + size, z + size};
    }

    @Test
    void cloudsThatOverlapAreOneGroup() {
        double[][] boxes = {box(0, 0, 0, 10), box(5, 0, 5, 10), box(100, 0, 0, 10)};
        int[][] g = SmokeRender.groups(boxes, 200, 0, 0);
        assertEquals(2, g.length);
        // the pair is farther from the camera at x 200: first
        assertArrayEquals(new int[] {0, 1}, g[0]);
        assertArrayEquals(new int[] {2}, g[1]);
    }

    @Test
    void aChainOfMeetingBoxesIsOneGroupAndAGapOfMoreThanABlockIsNot() {
        double[][] boxes = {box(0, 0, 0, 10), box(29.5, 0, 0, 10), box(10.5, 0, 0, 10), box(20.9, 0, 0, 8)};
        assertEquals(1, SmokeRender.groups(boxes, 0, 0, 0).length);
        double[][] apart = {box(0, 0, 0, 10), box(11.5, 0, 0, 10)};
        assertEquals(2, SmokeRender.groups(apart, 0, 0, 0).length);
    }

    @Test
    void theGroupsGoFarToNear() {
        double[][] boxes = {box(0, 0, 0, 4), box(50, 0, 0, 4), box(20, 0, 0, 4)};
        int[][] g = SmokeRender.groups(boxes, 0, 0, 0);
        assertArrayEquals(new int[] {1}, g[0]);
        assertArrayEquals(new int[] {2}, g[1]);
        assertArrayEquals(new int[] {0}, g[2]);
        assertArrayEquals(new double[] {0, 0, 0, 24, 4, 4}, SmokeRender.union(boxes, new int[] {0, 2}));
    }
}
