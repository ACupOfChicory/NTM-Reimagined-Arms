package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.monster.slime.SlimeModel;
import net.minecraft.client.model.geom.ModelPart;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class SlimeShellGeometryTest {
    @Test void shellMatchesActualOuterModelAcrossSizesAndSquash() {
        var access=ModelPoseCaptureTest.PARTS;
        var inner=SlimeModel.createInnerBodyLayer().bakeRoot();
        var outer=SlimeModel.createOuterBodyLayer().bakeRoot();
        var geometry=ModelBodyGeometry.slime(access);
        var decorated=ModelBodyGeometry.layerParts(inner,access,geometry,null,ModelBodyGeometry.SHELL_SUFFIX,access);
        var parts=new ArrayList<ModelPart>(); var names=new ArrayList<String>(); var parents=new ArrayList<Integer>();
        flatten(inner,"",-1,parts,names,parents);
        var layout=new ModelPoseLayout(names.toArray(String[]::new),parents.stream().mapToInt(i -> i).toArray(),parts.stream().map(access::cubes).toList());
        layout.supplement(geometry,null,ModelBodyGeometry.SHELL_SUFFIX);
        float[] transforms=new float[parts.size()*9]; boolean[] draw=new boolean[parts.size()];
        for(int i=0;i<parts.size();i++) {
            var p=parts.get(i); float[] values={p.x,p.y,p.z,p.xRot,p.yRot,p.zRot,p.xScale,p.yScale,p.zScale};
            System.arraycopy(values,0,transforms,i*9,9); draw[i]=true;
        }
        for(int size:new int[]{1,2,4}) for(float squash:new float[]{.75F,1,1.25F}) {
            var stack=new PoseStack(); stack.translate(2,3,4); stack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(31));
            stack.scale(-size/squash,-size*squash,size/squash);
            var expected=ModelPoseCapture.collect(outer,stack,0,0,0,access).boxes().getFirst();
            var cpu=ModelPoseCapture.collect(inner,stack,0,0,0,decorated).boxes();
            var gpu=layout.boxes(new Matrix4f(stack.last().pose()),transforms,draw,null);
            for(var boxes:List.of(cpu,gpu)) {
                var shell=boxes.stream().filter(b -> b.part().endsWith(ModelBodyGeometry.SHELL_SUFFIX)).findFirst().orElseThrow();
                assertArrayEquals(expected.numbers(),shell.numbers(),1e-6);
                assertEquals(5,boxes.size());
            }
        }
    }

    @Test void pointOutsideInnerBodyHitsOnlyTheRealShell() {
        var access=ModelPoseCaptureTest.PARTS;
        var inner=SlimeModel.createInnerBodyLayer().bakeRoot();
        var core=ModelPoseCapture.collect(inner,new PoseStack(),0,0,0,access).boxes();
        var full=ModelPoseCapture.collect(inner,new PoseStack(),0,0,0,
                ModelBodyGeometry.layerParts(inner,access,ModelBodyGeometry.slime(access),null,ModelBodyGeometry.SHELL_SUFFIX,access)).boxes();
        assertFalse(core.stream().anyMatch(b -> b.clip(.24,1.25,-1,.24,1.25,1)>=0));
        assertTrue(full.stream().anyMatch(b -> b.clip(.24,1.25,-1,.24,1.25,1)>=0));
    }
    private static void flatten(ModelPart part,String name,int parent,List<ModelPart> parts,List<String> names,List<Integer> parents) {
        int index=parts.size(); parts.add(part); names.add(name); parents.add(parent);
        ModelPoseCaptureTest.PARTS.children(part).forEach((key,child) -> flatten(child,name.isEmpty()?key:name+"/"+key,index,parts,names,parents));
    }
}
