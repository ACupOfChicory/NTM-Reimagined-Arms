package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.animal.sheep.*;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.SheepRenderState;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class SheepWoolGeometryTest {
    @Test void adultWoolFollowsTheActualWoolModelWhileWalkingAndEating() { check(false); }
    @Test void lambUsesItsOwnWoolGeometryAndPivots() { check(true); }

    private void check(boolean baby) {
        SheepModel body=baby?new BabySheepModel(BabySheepModel.createBodyLayer().bakeRoot()):new SheepModel(SheepModel.createBodyLayer().bakeRoot());
        var wool=new SheepFurModel((baby?BabySheepModel.createBodyLayer():SheepFurModel.createFurLayer()).bakeRoot());
        var state=new SheepRenderState(); state.isBaby=baby; state.ageScale=baby?.5F:1;
        state.walkAnimationPos=2.3F; state.walkAnimationSpeed=.7F;
        state.headEatPositionScale=.6F; state.headEatAngleScale=.25F; state.yRot=29;
        body.setupAnim(state); wool.setupAnim(state);
        var stack=new PoseStack(); stack.translate(2,3,4); stack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(31)); stack.scale(-1,-1,1);
        var access=ModelPoseCaptureTest.PARTS;
        var geometry=ModelBodyGeometry.wool(baby,access);
        var decorated=ModelBodyGeometry.layerParts(body.root(),access,geometry,null,ModelWool.SUFFIX,access);
        var cpu=ModelPoseCapture.collect(body.root(),stack,0,0,0,decorated).boxes();
        var expected=ModelPoseCapture.collect(wool.root(),stack,0,0,0,access).boxes();
        assertLayer(expected,cpu);

        var parts=new ArrayList<ModelPart>(); var names=new ArrayList<String>(); var parents=new ArrayList<Integer>();
        flatten(body.root(),"",-1,parts,names,parents);
        var layout=new ModelPoseLayout(names.toArray(String[]::new),parents.stream().mapToInt(i -> i).toArray(),parts.stream().map(access::cubes).toList());
        layout.supplement(geometry,null,ModelWool.SUFFIX);
        float[] transforms=new float[parts.size()*9]; boolean[] draw=new boolean[parts.size()];
        for(int i=0;i<parts.size();i++) {
            var p=parts.get(i); float[] values={p.x,p.y,p.z,p.xRot,p.yRot,p.zRot,p.xScale,p.yScale,p.zScale};
            System.arraycopy(values,0,transforms,i*9,9); draw[i]=p.visible && !p.skipDraw;
        }
        assertLayer(expected,layout.boxes(new Matrix4f(stack.last().pose()),transforms,draw,null));

        var sheared=ModelPoseCapture.collect(body.root(),stack,0,0,0,access).boxes();
        assertEquals(6,sheared.size()); assertTrue(sheared.stream().noneMatch(ModelWool::isWool));
        assertLayer(expected,ModelPoseCapture.collect(body.root(),stack,0,0,0,decorated).boxes());
    }

    private static void assertLayer(List<ModelBox> expected,List<ModelBox> actual) {
        var fur=actual.stream().filter(ModelWool::isWool).toList(); assertEquals(expected.size(),fur.size());
        for(var reference:expected) {
            var captured=fur.stream().filter(b -> b.part().equals(reference.part()+ModelWool.SUFFIX)).findFirst().orElseThrow();
            assertArrayEquals(reference.numbers(),captured.numbers(),1e-6,reference.part());
        }
    }
    private static void flatten(ModelPart part,String name,int parent,List<ModelPart> parts,List<String> names,List<Integer> parents) {
        int index=parts.size(); parts.add(part); names.add(name); parents.add(parent);
        ModelPoseCaptureTest.PARTS.children(part).forEach((key,child) -> flatten(child,name.isEmpty()?key:name+"/"+key,index,parts,names,parents));
    }
}
