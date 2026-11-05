package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.client.model.BlazeModel;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

// Port copy (notes/ports/de-1.21.1.md, «Копии»): 1.21.1's BlazeModel animates from setupAnim's ageInTicks (the mob
// itself is not read), where 26.2's takes it on the render state.
class MobModelPoseTest {
    @Test void preparedBlazePoseIncludesAllPartsAndRemovesGpuWorldPosition() {
        var model = new BlazeModel<net.minecraft.world.entity.monster.Blaze>(BlazeModel.createBodyLayer().bakeRoot());
        model.setupAnim(null, 0, 0, 17.3F, 0, 0);
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
        model.setupAnim(null, 0, 0, 23.8F, 0, 0);
        var next = ModelPoseCapture.collect(model.root(), world, position, ModelPoseCaptureTest.PARTS);
        assertNotEquals(actual.boxes(), next.boxes(), "Огненные стержни должны следовать подготовленной анимации");
    }
}
