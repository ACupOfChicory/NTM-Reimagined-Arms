package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import com.hbm.render.loader.GroupObject;
import com.hbm.render.loader.ObjMesh;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class MeshModelPoseTest {
    private static GroupObject[] maskman() throws Exception {
        try (var stream = MeshModelPoseTest.class.getResourceAsStream("/assets/hbm/models/mobs/maskman.hbm_mesh")) {
            assertNotNull(stream);
            return ObjMesh.read(stream, "maskman");
        }
    }

    @Test void maskmanActualMeshKeepsGapBetweenAnimatedLegs() throws Exception {
        var groups = maskman();
        var root = new Matrix4f().rotateY((float) Math.toRadians(270));
        List<ModelBox> legs = new ArrayList<>();
        for (String name : List.of("LLeg", "RLeg")) {
            var group = Arrays.stream(groups).filter(g -> g.name.equals(name)).findFirst().orElseThrow();
            var matrix = new Matrix4f(root).translate(-.5F, 1.75F, name.equals("LLeg") ? -.5F : .5F);
            ModelBox box = MeshModelPose.box(name, group.bounds(), matrix, 0, 0, 0);
            assertNotNull(box);
            assertTrue(box.valid(10));
            legs.add(box);
        }
        var a = center(legs.get(0)); var b = center(legs.get(1));
        var gap = new Vector3f(a).lerp(b, .5F);
        for (var leg : legs) {
            assertEquals(-1, leg.clip(gap.x, gap.y, gap.z, gap.x, gap.y, gap.z), "Пустота между ногами не должна стать общей коробкой");
            var p = center(leg);
            assertTrue(leg.clip(p.x, p.y, p.z - 10, p.x, p.y, p.z + 10) >= 0);
        }
    }

    @Test void meshBoundsMatchEveryTransformedCornerIncludingShearAndCameraOffset() {
        float[] bounds = {-1, -2, -3, 2, 4, 1};
        var matrix = new Matrix4f().translate(3, 7, -9).scale(2, 1, .5F).rotateXYZ(.2F, .4F, -.3F);
        var box = MeshModelPose.box("test", bounds, matrix, -2, 5, 11);
        assertNotNull(box);
        for (int i = 0; i < 8; i++) {
            var p = matrix.transformPosition(bounds[(i & 1) == 0 ? 0 : 3], bounds[(i & 2) == 0 ? 1 : 4],
                    bounds[(i & 4) == 0 ? 2 : 5], new Vector3f()).add(-2, 5, 11);
            double u = (i & 1) == 0 ? 0 : 1, v = (i & 2) == 0 ? 0 : 1, w = (i & 4) == 0 ? 0 : 1;
            assertArrayEquals(new double[] {p.x, p.y, p.z}, new double[] {
                    box.x() + u * box.ux() + v * box.vx() + w * box.wx(),
                    box.y() + u * box.uy() + v * box.vy() + w * box.wy(),
                    box.z() + u * box.uz() + v * box.vz() + w * box.wz()}, 2e-6);
        }
    }

    @Test void flatPaperDoesNotBecomeSolidHitbox() {
        assertNull(MeshModelPose.box("IOU", new float[] {0, 0, 0, 1, 1, 0}, new Matrix4f(), 0, 0, 0));
    }

    private static Vector3f center(ModelBox box) {
        return new Vector3f((float) (box.x() + (box.ux() + box.vx() + box.wx()) / 2),
                (float) (box.y() + (box.uy() + box.vy() + box.wy()) / 2),
                (float) (box.z() + (box.uz() + box.vz() + box.wz()) / 2));
    }
}
