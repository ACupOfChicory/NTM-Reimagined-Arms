package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.client.model.monster.blaze.BlazeModel;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class MobModelPoseTest {
    @Test void preparedBlazePoseIncludesAllPartsAndRemovesGpuWorldPosition() {
        var model = new BlazeModel(BlazeModel.createBodyLayer().bakeRoot());
        var state = new LivingEntityRenderState();
        state.ageInTicks = 17.3F;
        model.setupAnim(state);
        var local = new Matrix4f().rotateY(.7F).scale(-1,-1,1).translate(0,-1.501F,0);
        var position = new Vector3f(-1096,-60,-460);
        var world = new Matrix4f().translation(position).mul(local);
        var expected = ModelPoseCapture.collect(model.root(), local, new Vector3f(), ModelPoseCaptureTest.PARTS);
        var actual = ModelPoseCapture.collect(model.root(), world, position, ModelPoseCaptureTest.PARTS);
        assertTrue(actual.complete());
        assertEquals(13, actual.boxes().size());
        for (int i = 0; i < expected.boxes().size(); i++)
            assertArrayEquals(expected.boxes().get(i).numbers(), actual.boxes().get(i).numbers(), 2e-4);
        assertTrue(actual.boxes().stream().allMatch(b -> b.valid(3.6)));
        state.ageInTicks = 23.8F;
        model.setupAnim(state);
        var next = ModelPoseCapture.collect(model.root(), world, position, ModelPoseCaptureTest.PARTS);
        assertNotEquals(actual.boxes(), next.boxes(), "Огненные стержни должны следовать подготовленной анимации");
    }
}
