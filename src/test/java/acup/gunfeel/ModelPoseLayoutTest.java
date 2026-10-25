package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.monster.skeleton.SkeletonModel;
import net.minecraft.client.renderer.entity.state.SkeletonRenderState;
import org.junit.jupiter.api.Test;

class ModelPoseLayoutTest {
    @Test void snapshotTransformsMatchActualAnimatedModelIncludingParentScale() {
        var model = new SkeletonModel<SkeletonRenderState>(SkeletonModel.createBodyLayer().bakeRoot());
        var state = new SkeletonRenderState();
        state.walkAnimationPos = 1.3F;
        state.walkAnimationSpeed = .8F;
        model.setupAnim(state);
        model.root().xScale = 1.7F;
        List<ModelPart> parts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Integer> parents = new ArrayList<>();
        flatten(model.root(), "", -1, parts, names, parents);
        float[] transforms = new float[parts.size() * 9];
        boolean[] draw = new boolean[parts.size()];
        List<List<ModelPart.Cube>> cubes = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            ModelPart p = parts.get(i);
            float[] t = {p.x, p.y, p.z, p.xRot, p.yRot, p.zRot, p.xScale, p.yScale, p.zScale};
            System.arraycopy(t, 0, transforms, i * 9, 9);
            draw[i] = p.visible && !p.skipDraw;
            cubes.add(ModelPoseCaptureTest.PARTS.cubes(p));
        }
        var stack = new PoseStack();
        stack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(35));
        stack.scale(-1, -1, 1);
        var expected = ModelPoseCapture.collect(model.root(), stack, 0, 0, 0, ModelPoseCaptureTest.PARTS).boxes();
        var layout = new ModelPoseLayout(names.toArray(String[]::new), parents.stream().mapToInt(i -> i).toArray(), cubes);
        var actual = layout.boxes(stack.last().pose(), transforms, draw, null);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).part(), actual.get(i).part());
            assertArrayEquals(expected.get(i).numbers(), actual.get(i).numbers(), 1e-6);
        }
        java.util.Arrays.fill(draw, false);
        assertTrue(layout.boxes(stack.last().pose(), transforms, draw, null).isEmpty());
    }

    private static void flatten(ModelPart part, String path, int parent, List<ModelPart> parts, List<String> names, List<Integer> parents) {
        int index = parts.size();
        parts.add(part); names.add(path); parents.add(parent);
        ModelPoseCaptureTest.PARTS.children(part).forEach((name, child) -> flatten(child, path.isEmpty() ? name : path + "/" + name,
                index, parts, names, parents));
    }
}
