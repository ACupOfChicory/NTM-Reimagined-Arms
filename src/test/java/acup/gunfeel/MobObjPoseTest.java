package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import com.hbm.render.loader.HFRWavefrontObject;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class MobObjPoseTest {
    private record Part(String name) {}
    private record Spec(Supplier<HFRWavefrontObject> mesh, List<Part> parts) {}
    private record Visual(Spec spec) {}

    @Test void actualMaskmanMeshUsesOnlyVisibleGpuPartsAndRemovesRenderOrigin() throws Exception {
        var mesh = new HFRWavefrontObject("maskman", Arrays.asList(MeshModelPoseTest.maskman()));
        var names = List.of("Torso", "LLeg", "RLeg", "LArm", "RArm", "Head", "Skull");
        var visual = new Visual(new Spec(() -> mesh, names.stream().map(Part::new).toList()));
        var layout = MobObjPose.layout(visual);
        assertEquals(names, layout.stream().map(MobObjPose.Part::name).toList());
        var position = new Vector3f(-100, 35, 600);
        Matrix4f[] poses = new Matrix4f[names.size()];
        for (int i = 0; i < poses.length; i++) poses[i] = new Matrix4f().translation(position).rotateY((float) Math.toRadians(270));
        poses[1].translate(-.5F, 1.75F, -.5F);
        poses[2].translate(-.5F, 1.75F, .5F);
        poses[3].translate(-.5F, 3.75F, -1.5F);
        poses[4].translate(-.5F, 3.75F, 1.5F);
        poses[5].translate(.5F, 4, 0).rotateY(.4F);
        poses[6].set(poses[5]);
        boolean[] visible = {true, true, true, true, true, true, false};
        var normal = MobObjPose.boxes(layout, poses, visible, position);
        assertEquals(6, normal.size());
        assertTrue(normal.stream().allMatch(b -> b.valid(10)), "Координаты GPU должны стать локальными относительно моба");
        assertTrue(normal.stream().anyMatch(b -> b.part().equals("mesh/Head")));
        assertFalse(normal.stream().anyMatch(b -> b.part().equals("mesh/Skull")));
        visible[5] = false; visible[6] = true;
        var wounded = MobObjPose.boxes(layout, poses, visible, position);
        assertFalse(wounded.stream().anyMatch(b -> b.part().equals("mesh/Head")));
        assertTrue(wounded.stream().anyMatch(b -> b.part().equals("mesh/Skull")));
        Arrays.fill(visible, false);
        assertTrue(MobObjPose.boxes(layout, poses, visible, position).isEmpty());
    }
}
