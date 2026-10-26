package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import net.minecraft.client.model.animal.chicken.AdultChickenModel;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class ModelCubeGeometryTest {
    @Test void chickenLegCollisionMatchesActualTextureAtEachFacePixel() throws Exception {
        var root=AdultChickenModel.createBodyLayer().bakeRoot();
        var leg=ModelPoseCaptureTest.PARTS.cubes(root.getChild("right_leg")).getFirst();
        try(var stream=getClass().getResourceAsStream("/assets/minecraft/textures/entity/chicken/chicken_temperate.png")) {
            assertNotNull(stream);
            var image=javax.imageio.ImageIO.read(stream);
            var mask=ModelTextureMask.alpha(image.getWidth(),image.getHeight(),image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth()));
            var geometry=ModelCubeGeometry.bake(leg,mask);
            assertFalse(geometry.meshes().isEmpty(),"Прозрачная нога не должна становиться сплошной коробкой");
            var boxes=new ArrayList<ModelBox>(); geometry.append(boxes,"leg",new Matrix4f(),0,0,0);
            int empty=0,opaque=0;
            for(var face:leg.polygons) {
                var v=face.vertices(); var a=v[0]; var b=v[1]; var d=v[3];
                var u=new org.joml.Vector3f(b.x()-a.x(),b.y()-a.y(),b.z()-a.z());
                var w=new org.joml.Vector3f(d.x()-a.x(),d.y()-a.y(),d.z()-a.z());
                var n=new org.joml.Vector3f(u).cross(w);
                if(n.lengthSquared()<1e-8) continue;
                n.normalize().mul(.001F);
                for(int iy=0;iy<20;iy++) for(int ix=0;ix<20;ix++) {
                    float s=(ix+.37F)/20,t=(iy+.61F)/20;
                    double x=(a.x()+s*u.x+t*w.x)/16,y=(a.y()+s*u.y+t*w.y)/16,z=(a.z()+s*u.z+t*w.z)/16;
                    int px=Math.clamp((int)((a.u()+s*(b.u()-a.u())+t*(d.u()-a.u()))*image.getWidth()),0,image.getWidth()-1);
                    int py=Math.clamp((int)((a.v()+s*(b.v()-a.v())+t*(d.v()-a.v()))*image.getHeight()),0,image.getHeight()-1);
                    boolean expected=(image.getRGB(px,py)>>>24)!=0;
                    boolean actual=boxes.stream().anyMatch(box -> box.clip(x-n.x,y-n.y,z-n.z,x+n.x,y+n.y,z+n.z)>=0);
                    assertEquals(expected,actual,"Пиксель ноги "+px+","+py+"; луч "+x+","+y+","+z);
                    if(expected) opaque++; else empty++;
                }
            }
            assertTrue(empty>100); assertTrue(opaque>100);
        }
    }

    @Test void openSurfaceDoesNotInventSolidInterior() {
        var mesh=new ModelMesh(new float[]{0,0,1,1,0,1,0,1,1});
        assertEquals(-1,mesh.clip(new double[]{.1,.1,.5},new double[]{.1,.1,.6}));
        assertTrue(mesh.clip(new double[]{.1,.1,.5},new double[]{.1,.1,1.1})>=0);
    }

    @Test void actualBatWingsFeetAndEarsKeepVisibleContours() throws Exception {
        var root=net.minecraft.client.model.ambient.BatModel.createBodyLayer().bakeRoot();
        try(var stream=getClass().getResourceAsStream("/assets/minecraft/textures/entity/bat/bat.png")) {
            assertNotNull(stream); var image=javax.imageio.ImageIO.read(stream);
            var mask=ModelTextureMask.alpha(image.getWidth(),image.getHeight(),image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth()));
            int[] flat={0};
            root.visit(new com.mojang.blaze3d.vertex.PoseStack(),(pose,name,index,cube) -> {
                if(cube.maxZ!=cube.minZ) return;
                var geometry=ModelCubeGeometry.bake(cube,mask);
                var boxes=new ArrayList<ModelBox>(); geometry.append(boxes,name,new Matrix4f(),0,0,0);
                assertTrue(geometry.visible(),name);
                assertFalse(boxes.isEmpty(),name);
                for(var box:boxes) {
                    assertNotEquals(0,box.mesh(),name);
                    var mesh=ModelMesh.get(box.mesh());
                    assertTrue(mesh.edges().length>=24,name+": внешний контур плоской детали потерян");
                    var tris=mesh.triangles();
                    double x=(tris[0]+tris[3]+tris[6])/3.,y=(tris[1]+tris[4]+tris[7])/3.;
                    assertTrue(mesh.clip(new double[]{x,y,-1},new double[]{x,y,1})>=0,name);
                }
                flat[0]++;
            });
            assertEquals(7,flat[0],"Четыре части крыльев, две ушные раковины и лапы");
        }
    }
}
