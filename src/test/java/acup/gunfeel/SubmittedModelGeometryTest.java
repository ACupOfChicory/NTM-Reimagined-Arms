package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.blaze3d.PrimitiveTopology;
import java.util.ArrayList;
import net.minecraft.client.model.animal.chicken.AdultChickenModel;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class SubmittedModelGeometryTest {
    private static float[] stream(ModelPart.Cube cube,Matrix4f transform) {
        var data=new ArrayList<Float>();
        for(var face:cube.polygons) for(var vertex:face.vertices()) {
            var p=transform.transformPosition(vertex.x()/16,vertex.y()/16,vertex.z()/16,new org.joml.Vector3f());
            data.add(p.x); data.add(p.y); data.add(p.z); data.add(vertex.u()); data.add(vertex.v());
        }
        float[] result=new float[data.size()]; for(int i=0;i<result.length;i++) result[i]=data.get(i);
        return result;
    }
    @Test void nativeChickenBodyKeepsItsExactShapeAcrossCameraAndShearedPose() {
        var root=AdultChickenModel.createBodyLayer().bakeRoot();
        var cube=ModelPoseCaptureTest.PARTS.cubes(root.getChild("body")).getFirst();
        for(int i=0;i<25;i++) {
            var transform=new Matrix4f().translation(4+i*.1F,2,8).rotateY(i*.11F)
                    .scale(1.4F,.7F,2).rotateZ(i*.07F);
            var expected=new ArrayList<ModelBox>();
            ModelCubeGeometry.bake(cube,null).append(expected,"native",transform,-3,-2,-8);
            var actual=SubmittedModelGeometry.capture(stream(cube,transform),PrimitiveTopology.QUADS,null,false,-3,-2,-8);
            assertEquals(1,actual.size(),"шесть граней настоящего куба должны сохранить его объём");
            assertEquals(0,actual.getFirst().mesh());
            for(int y=0;y<15;y++) for(int x=0;x<15;x++) {
                double a=-1+x*.2,b=-1+y*.2;
                boolean hit=expected.stream().anyMatch(box -> box.clip(a,b,-5,a,b,5)>=0);
                assertEquals(hit,actual.stream().anyMatch(box -> box.clip(a,b,-5,a,b,5)>=0),"поворот и неравномерный масштаб "+i);
            }
        }
    }
    @Test void zeroThicknessPartRemainsSurfaceWithoutInventedVolume() {
        var root=AdultChickenModel.createBodyLayer().bakeRoot();
        var cube=ModelPoseCaptureTest.PARTS.cubes(root.getChild("right_leg")).getFirst();
        var vertices=stream(cube,new Matrix4f());
        for(int p=0;p<vertices.length;p+=5) vertices[p]=0;
        var actual=SubmittedModelGeometry.capture(vertices,PrimitiveTopology.QUADS,null,false,0,0,0);
        assertFalse(actual.isEmpty());
        assertTrue(actual.stream().allMatch(box -> box.mesh()!=0));
        assertTrue(actual.stream().allMatch(PlaneHitPolicy::flat));
    }
    @Test void sixSeparateQuadsDoNotBecomeSolidBox() {
        float[] raw=new float[24*5];
        for(int i=0;i<6;i++) {
            float[][] corners={{0,0},{1,0},{1,1},{0,1}};
            for(int v=0;v<4;v++) { int p=(i*4+v)*5; raw[p]=corners[v][0]+i*2; raw[p+1]=corners[v][1]; }
        }
        var actual=SubmittedModelGeometry.capture(raw,PrimitiveTopology.QUADS,null,false,0,0,0);
        assertEquals(6,actual.size());
        assertTrue(actual.stream().allMatch(box -> box.mesh()!=0));
        assertTrue(actual.stream().noneMatch(box -> box.clip(1.5,.5,-1,1.5,.5,1)>=0));
    }
    // F3+B outlines on their own only the pieces NTM's renderers draw (ModelMesh.grouped), and not one Flywheel has
    // also taken: a mesh is one object for equal triangles, whoever took them
    @Test void onlyNtmsPiecesGoOnTheirOwnAndFlywheelsMarkWins() {
        float[] raw={0,0,0,0,0, 1,0,0,1,0, .7F,1,0,1,1, .2F,1,0,0,1};
        var other=SubmittedModelGeometry.capture(raw,PrimitiveTopology.QUADS,null,false,false,0,0,0);
        assertFalse(other.isEmpty());
        assertTrue(other.stream().allMatch(box -> box.mesh()!=0));
        assertTrue(other.stream().noneMatch(box -> ModelMesh.grouped(box.mesh())),"another mod's custom geometry goes with the cubes");
        var ntm=SubmittedModelGeometry.capture(raw,PrimitiveTopology.QUADS,null,false,true,0,0,0);
        assertEquals(other,ntm);
        assertTrue(ntm.stream().allMatch(box -> ModelMesh.grouped(box.mesh())),"NTM's own piece goes on its own");
        for(var box:ntm) ModelMesh.get(box.mesh()).flywheel=true;
        assertTrue(ntm.stream().noneMatch(box -> ModelMesh.grouped(box.mesh())),"one Flywheel has also taken goes with the cubes");
        SubmittedModelGeometry.capture(raw,PrimitiveTopology.QUADS,null,false,true,0,0,0);
        assertTrue(ntm.stream().noneMatch(box -> ModelMesh.grouped(box.mesh())),"whichever took it last");
    }
    @Test void invalidForeignStreamsAreRejectedBeforeBakingOrAllocatingMasks() {
        float[] raw=new float[20]; raw[0]=Float.NaN;
        assertThrows(IllegalArgumentException.class,() -> SubmittedModelGeometry.capture(raw,PrimitiveTopology.QUADS,null,false,0,0,0));
        raw[0]=0; raw[3]=Float.MAX_VALUE;
        assertThrows(IllegalArgumentException.class,() -> SubmittedModelGeometry.capture(raw,PrimitiveTopology.QUADS,null,false,0,0,0));
        assertThrows(IllegalArgumentException.class,() -> SubmittedModelGeometry.capture(new float[15],PrimitiveTopology.QUADS,null,false,0,0,0));
    }
}
