package acup.gunfeel;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArmContactTest {
    @Test void allFacesAndCornersKeepContactAfterWristRotation() {
        for(int id=1;id<=13;id++)for(boolean right:new boolean[]{false,true})for(boolean slim:new boolean[]{false,true}) {
            var pose=new Matrix4f().translation(.3F,-.2F,-1).rotateXYZ(.4F,.8F,-.3F).scale(.8F,1.4F,.8F);
            var target=new Vector3f(.1F,.2F,-.7F);var offset=ArmContact.offset(id);
            ArmContact.attach(pose,target,right,slim,1,offset);
            assertEquals(0,pose.transformPosition(ArmContact.pivot(right,slim,offset),new Vector3f()).distance(target),1e-6,"контакт "+id);
            ArmPoseEdits.apply(pose,new float[]{0,0,0,35,70,-20},right,slim,offset);
            assertEquals(0,pose.transformPosition(ArmContact.pivot(right,slim,offset),new Vector3f()).distance(target),1e-6,"поворот вокруг контакта "+id);
            String source="RELOAD 180:Handle!"+id+"@0.44,0.72,5.6/1,0,0";
            var keys=ArmTimeline.read(source,"RELOAD","",0);
            assertEquals(id,ArmTimeline.read(ArmTimeline.write(source,"RELOAD",keys),"RELOAD","",0).getFirst().contactId());
        }
        assertEquals(0,ArmContact.offset(5).z);assertEquals(-2,ArmContact.offset(5).y);
        assertEquals(1,ArmContact.offset(5).x);
        assertEquals(ArmContact.offset(1).lerp(ArmContact.offset(2),.5F),ArmContact.offset(10));
        assertEquals(ArmContact.offset(1).lerp(ArmContact.offset(3),.5F),ArmContact.offset(12));
        assertNotEquals(ArmContact.offset(5),ArmContact.offset(10),"ребро торца отличается от середины боковой плоскости");
    }
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
            var fixed = pose.transformPosition(center+(right?half:-half),.75F,-.125F,new Vector3f());
            assertEquals(0,fixed.distance(target),1e-6,"один и тот же угол при любом повороте");
            assertEquals(before.m00(),pose.m00());
            assertEquals(before.m11(),pose.m11());
            assertEquals(before.m22(),pose.m22());
            ArmContact.corner(before,target,right,slim,0);
            assertEquals(.3F,before.m30(),1e-6);
        }
    }
}
