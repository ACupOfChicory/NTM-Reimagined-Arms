package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class PlaneHitPolicyTest {
    @Test void flatSkinPatchRemainsHittableOnlyForWhitelistedAvatars() {
        var patch=MeshModelPose.surface("sleeve",0,0,0,1,1,0,new Matrix4f(),0,0,0);
        var boxes=List.of(patch);
        for(String type:List.of("minecraft:player","minecraft:mannequin")) {
            var kept=PlaneHitPolicy.filter(net.minecraft.resources.Identifier.parse(type),.6F,1.8F,boxes);
            assertEquals(boxes,kept);assertTrue(kept.getFirst().clip(.5,.5,-1,.5,.5,1)>=0);
        }
        var ordinary=PlaneHitPolicy.filter(net.minecraft.resources.Identifier.parse("minecraft:zombie"),.6F,1.8F,boxes);
        assertEquals(-1,ordinary.getFirst().clip(.5,.5,-1,.5,.5,1));
    }
    @Test void goatAndLargerLosePaperButKeepVolume() {
        var paper=MeshModelPose.surface("beard",0,0,0,1,1,0,new Matrix4f(),0,0,0);
        var horn=new ModelBox("horn",0,0,0,1,0,0,0,1,0,0,0,1);
        var boxes=List.of(paper,horn);
        assertEquals(List.of(horn),PlaneHitPolicy.filter(.9F,1.3F,boxes));
        assertEquals(List.of(horn),PlaneHitPolicy.filter(1.4F,.9F,boxes));
        assertEquals(boxes,PlaneHitPolicy.filter(.45F,.65F,boxes));
        assertEquals(boxes,PlaneHitPolicy.filter(.9F,.5F,boxes));
    }

    @Test void realGoatBeardIsExcludedButBodyAndHornsRemain() {
        var root=net.minecraft.client.model.animal.goat.GoatModel.createBodyLayer().bakeRoot();
        var boxes=ModelPoseCapture.collect(root,new com.mojang.blaze3d.vertex.PoseStack(),0,0,0,ModelPoseCaptureTest.PARTS).boxes();
        assertTrue(boxes.stream().anyMatch(b -> b.part().equals("root/head") && PlaneHitPolicy.flat(b)));
        var adult=PlaneHitPolicy.filter(.9F,1.3F,boxes);
        assertTrue(adult.stream().noneMatch(PlaneHitPolicy::flat));
        assertTrue(adult.stream().anyMatch(b -> b.part().contains("horn")));
        assertTrue(adult.stream().anyMatch(b -> b.part().contains("body")));
        assertEquals(boxes,PlaneHitPolicy.filter(.45F,.65F,boxes));
    }

    @Test void allPaperLargeModelStaysEmptyInsteadOfFallingBackToAVanillaBox() {
        var paper=MeshModelPose.surface("paper",0,0,0,1,1,0,new Matrix4f(),0,0,0);
        var empty=PlaneHitPolicy.filter(2,2,List.of(paper));
        assertEquals(1,empty.size());
        var box=empty.getFirst(); assertTrue(box.valid(2));
        assertEquals(-1,box.clip(.5,.5,-1,.5,.5,1));
        assertEquals(-1,box.clip(0,0,0,0,0,0));
        assertEquals(0,ModelMesh.get(box.mesh()).edges().length);
        assertEquals(empty,PlaneHitPolicy.filter(2,2,empty));
    }
}
