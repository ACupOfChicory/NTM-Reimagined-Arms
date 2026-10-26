package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.monster.breeze.BreezeModel;
import net.minecraft.client.renderer.entity.state.BreezeRenderState;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class FlatMobGeometryTest {
    @Test void genuineZeroDepthModelPartIsCapturedByBothRenderPaths() {
        var definition=new MeshDefinition();
        definition.getRoot().addOrReplaceChild("wing",CubeListBuilder.create().texOffs(0,0).addBox(0,0,0,16,16,0),PartPose.ZERO);
        ModelPart root=LayerDefinition.create(definition,64,64).bakeRoot();
        var direct=ModelPoseCapture.collect(root,new PoseStack(),0,0,0,ModelPoseCaptureTest.PARTS).boxes();
        var gpu=layout(root).boxes(new Matrix4f(),transforms(root),draw(root),null);
        assertEquals(1,direct.size()); assertEquals(1,gpu.size());
        assertEquals(direct,gpu);
        assertEquals(.5,direct.getFirst().clip(.5,.5,-1,.5,.5,1),1e-8);
        assertNotEquals(0,direct.getFirst().mesh());
    }

    @Test void realBreezeWindUsesAlreadyAnimatedBodyBones() {
        var model=new BreezeModel(BreezeModel.createBodyLayer().bakeRoot());
        var state=new BreezeRenderState(); state.ageInTicks=17;
        model.setupAnim(state);
        var layout=layout(model.root());
        int before=layout.boxes(new Matrix4f(),transforms(model.root()),draw(model.root()),null).size();
        layout.supplement(ModelBodyGeometry.wind(ModelPoseCaptureTest.PARTS),null);
        var after=layout.boxes(new Matrix4f(),transforms(model.root()),draw(model.root()),null);
        assertTrue(after.size()>before);
        assertTrue(after.stream().anyMatch(box -> box.part().contains("wind_bottom")));
        assertTrue(after.stream().anyMatch(box -> box.part().contains("wind_top")));
    }

    private static ModelPoseLayout layout(ModelPart root) {
        var parts=new ArrayList<ModelPart>(); var names=new ArrayList<String>(); var parents=new ArrayList<Integer>();
        flatten(root,"",-1,parts,names,parents);
        return new ModelPoseLayout(names.toArray(String[]::new),parents.stream().mapToInt(i -> i).toArray(),
                parts.stream().map(ModelPoseCaptureTest.PARTS::cubes).toList());
    }
    private static List<ModelPart> parts(ModelPart root) {
        var parts=new ArrayList<ModelPart>(); flatten(root,"",-1,parts,new ArrayList<>(),new ArrayList<>()); return parts;
    }
    private static float[] transforms(ModelPart root) {
        var parts=parts(root); float[] out=new float[parts.size()*9];
        for(int i=0;i<parts.size();i++) {
            var p=parts.get(i); float[] values={p.x,p.y,p.z,p.xRot,p.yRot,p.zRot,p.xScale,p.yScale,p.zScale};
            System.arraycopy(values,0,out,i*9,9);
        }
        return out;
    }
    private static boolean[] draw(ModelPart root) {
        var parts=parts(root); boolean[] out=new boolean[parts.size()];
        for(int i=0;i<out.length;i++) out[i]=parts.get(i).visible && !parts.get(i).skipDraw;
        return out;
    }
    private static void flatten(ModelPart part,String name,int parent,List<ModelPart> parts,List<String> names,List<Integer> parents) {
        int index=parts.size(); parts.add(part); names.add(name); parents.add(parent);
        ModelPoseCaptureTest.PARTS.children(part).forEach((key,child) -> flatten(child,key,index,parts,names,parents));
    }
}
