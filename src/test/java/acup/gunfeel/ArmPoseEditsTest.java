package acup.gunfeel;

import org.joml.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArmPoseEditsTest {
    @Test void dynamicRotationInterpolatesAndSurvivesSaving() {
        var data=ArmPoseEdits.read("RELOAD 180:0,0,0,0,0,20 585:0,0,0,0,0,50; INSPECT 0:1,0,0,0,0,0");
        var track=data.get("RELOAD");
        assertEquals(20,ArmPoseEdits.sample(track,180)[5]);
        assertEquals(35,ArmPoseEdits.sample(track,382.5F)[5],.001);
        assertEquals(50,ArmPoseEdits.sample(track,585)[5]);
        var saved=ArmPoseEdits.write(data);
        assertArrayEquals(ArmPoseEdits.sample(track,410),ArmPoseEdits.sample(saved,"RELOAD",410));
        assertEquals(1,ArmPoseEdits.sample(saved,"INSPECT",0)[0]);
    }
    @Test void ordinaryGripReallyMovesAndRotatesWithoutStretching() {
        for(boolean right:new boolean[]{false,true})for(boolean slim:new boolean[]{false,true}) {
            var pose=new Matrix4f().translation(.3F,-.2F,-1).rotateXYZ(.4F,.8F,-.3F).scale(.8F,2F,.8F);
            var original=new Matrix4f(pose);
            var pivot=new Vector3f((right?-1:1)*(slim?5.5F:6F)/16F,.75F,0);
            var before=pose.transformPosition(pivot,new Vector3f());
            ArmPoseEdits.apply(pose,new float[]{0,0,0,20,35,50},right,slim,0);
            assertEquals(0,before.distance(pose.transformPosition(pivot,new Vector3f())),1e-5);
            assertEquals(0,original.getScale(new Vector3f()).distance(pose.getScale(new Vector3f())),1e-5);
            assertNotEquals(original.m01(),pose.m01());
            ArmPoseEdits.apply(pose,new float[]{2,0,0,0,0,0},right,slim,0);
            assertEquals(before.x+.1F,pose.transformPosition(pivot,new Vector3f()).x,1e-5);
        }
    }
}
