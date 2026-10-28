package acup.gunfeel;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArmContactTest {
    @Test void contactSurvivesRotationAndBothArmModels() {
        for (boolean right : new boolean[] {false,true}) for (boolean slim : new boolean[] {false,true}) {
            var pose = new Matrix4f().translation(.3F,-.2F,-1).rotateXYZ(.4F,.8F,-.3F).scale(.8F,1.4F,.8F);
            var target = new Vector3f(.1F,.2F,-.7F);
            var before = new Matrix4f(pose);
            ArmContact.corner(pose,target,right,slim,1);
            float center = (right?-1:1)*(slim?5.5F:6F)/16;
            float half = (slim?1.5F:2F)/16;
            float distance = Float.POSITIVE_INFINITY;
            for (int x : new int[] {-1,1}) for (int z : new int[] {-1,1})
                distance = Math.min(distance,pose.transformPosition(center+x*half,.75F,z*.125F,new Vector3f()).distance(target));
            assertEquals(0,distance,1e-6,"угол кисти должен касаться детали");
            assertEquals(before.m00(),pose.m00());
            assertEquals(before.m11(),pose.m11());
            assertEquals(before.m22(),pose.m22());
            ArmContact.corner(before,target,right,slim,0);
            assertEquals(.3F,before.m30(),1e-6);
        }
    }
}
