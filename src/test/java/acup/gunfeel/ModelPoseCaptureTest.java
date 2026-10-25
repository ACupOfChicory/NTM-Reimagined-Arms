package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.monster.skeleton.SkeletonModel;
import net.minecraft.client.renderer.entity.state.SkeletonRenderState;
import org.junit.jupiter.api.Test;

class ModelPoseCaptureTest {
    // В JUnit нет Mixin; читаем те же поля настоящего ModelPart обычной рефлексией только в тесте.
    static final ModelPoseCapture.Parts PARTS = new ModelPoseCapture.Parts() {
        @SuppressWarnings("unchecked")
        private <T> T field(ModelPart p, String name) {
            try { Field f = ModelPart.class.getDeclaredField(name); f.setAccessible(true); return (T) f.get(p); }
            catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        public List<ModelPart.Cube> cubes(ModelPart p) { return field(p, "cubes"); }
        public Map<String, ModelPart> children(ModelPart p) { return field(p, "children"); }
    };

    @Test void realSkeletonHasLegsAndAnEmptyGap() {
        var model = new SkeletonModel<SkeletonRenderState>(SkeletonModel.createBodyLayer().bakeRoot());
        var state = new SkeletonRenderState();
        model.setupAnim(state);
        var stack = new PoseStack();
        stack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180));
        stack.scale(-1, -1, 1);
        stack.translate(0, -1.501F, 0);
        var result = ModelPoseCapture.collect(model.root(), stack, 0, 0, 0, PARTS);
        assertTrue(result.complete());
        assertTrue(result.boxes().size() >= 6);
        assertTrue(result.boxes().stream().allMatch(b -> b.valid(3.98)), result.boxes().toString());
        assertTrue(result.boxes().stream().noneMatch(b -> b.clip(0, .3, -2, 0, .3, 2) >= 0));
        assertTrue(result.boxes().stream().anyMatch(b -> b.clip(.125, .3, -2, .125, .3, 2) >= 0));
        model.root().getChild("right_leg").visible = false;
        var hidden = ModelPoseCapture.collect(model.root(), stack, 0, 0, 0, PARTS);
        assertTrue(hidden.boxes().stream().noneMatch(b -> b.part().endsWith("right_leg")));
    }

    @Test void walkingAnimationChangesTheActualLegBox() {
        var model = new SkeletonModel<SkeletonRenderState>(SkeletonModel.createBodyLayer().bakeRoot());
        var state = new SkeletonRenderState();
        var stack = new PoseStack();
        model.setupAnim(state);
        var standing = ModelPoseCapture.collect(model.root(), stack, 0, 0, 0, PARTS).boxes().stream()
                .filter(b -> b.part().endsWith("right_leg")).findFirst().orElseThrow();
        state.walkAnimationSpeed = 1;
        state.walkAnimationPos = 1;
        model.setupAnim(state);
        var walking = ModelPoseCapture.collect(model.root(), stack, 0, 0, 0, PARTS).boxes().stream()
                .filter(b -> b.part().endsWith("right_leg")).findFirst().orElseThrow();
        assertNotEquals(standing, walking);
        assertTrue(walking.valid(4));
    }
}
