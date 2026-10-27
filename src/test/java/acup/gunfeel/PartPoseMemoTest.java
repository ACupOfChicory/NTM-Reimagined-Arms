package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class PartPoseMemoTest {
    private static RenderedMesh.PartPose pose(float offset,float cut) {
        return new RenderedMesh.PartPose(new Matrix4f().translation(offset,0,0),new float[][]{{0,0,1,cut}});
    }
    @Test void stationaryDoorKeepsTheSamePartPoseAcrossFrames() {
        var memo=new PartPoseMemo<String>();
        var initial=memo.frame(Map.of("leaf",pose(0,2.5F)));
        for(int frame=0;frame<120;frame++) {
            var actual=memo.frame(Map.of("leaf",pose(0,2.5F)));
            assertSame(initial,actual); assertSame(initial.get("leaf"),actual.get("leaf"));
        }
    }
    @Test void movementAndClippingChangesInvalidateOnlyTheirPart() {
        var memo=new PartPoseMemo<String>();
        var initial=memo.frame(Map.of("leaf",pose(0,2.5F),"frame",pose(0,0)));
        var moved=memo.frame(Map.of("leaf",pose(.1F,2.5F),"frame",pose(0,0)));
        assertNotSame(initial.get("leaf"),moved.get("leaf")); assertSame(initial.get("frame"),moved.get("frame"));
        var clipped=memo.frame(Map.of("leaf",pose(.1F,2.4F),"frame",pose(0,0)));
        assertNotSame(moved.get("leaf"),clipped.get("leaf")); assertSame(moved.get("frame"),clipped.get("frame"));
    }
    @Test void mutableRendererInputsCannotChangeTheSavedSnapshot() {
        var memo=new PartPoseMemo<String>(); var input=pose(0,2.5F);
        var old=memo.frame(Map.of("leaf",input)).get("leaf");
        ((Matrix4f)input.pose()).translate(1,0,0); input.planes()[0][3]=1;
        assertEquals(0,old.pose().m30()); assertEquals(2.5F,old.planes()[0][3]);
        assertNotSame(old,memo.frame(Map.of("leaf",input)).get("leaf"));
    }
    @Test void disappearedPartsLeaveNoPoseInTheNextFrame() {
        var memo=new PartPoseMemo<String>(); memo.frame(Map.of("leaf",pose(0,2.5F)));
        assertTrue(memo.frame(Map.of()).isEmpty());
    }
}
