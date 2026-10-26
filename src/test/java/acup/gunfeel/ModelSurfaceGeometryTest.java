package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.animal.chicken.AdultChickenModel;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class ModelSurfaceGeometryTest {
    @Test void gpuTrianglesMatchVisibleChickenLegPixels() throws Exception {
        var root=AdultChickenModel.createBodyLayer().bakeRoot();
        var cube=ModelPoseCaptureTest.PARTS.cubes(root.getChild("right_leg")).getFirst();
        try(var stream=getClass().getResourceAsStream("/assets/minecraft/textures/entity/chicken/chicken_temperate.png")) {
            var image=javax.imageio.ImageIO.read(stream);
            var mask=ModelTextureMask.alpha(image.getWidth(),image.getHeight(),image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth()));
            var raw=new ArrayList<Float>();
            for(var face:cube.polygons) for(int i:new int[]{0,1,2,0,2,3}) {
                var v=face.vertices()[i]; raw.add(v.x()/16); raw.add(v.y()/16); raw.add(v.z()/16); raw.add(v.u()); raw.add(v.v());
            }
            float[] data=new float[raw.size()]; for(int i=0;i<data.length;i++) data[i]=raw.get(i);
            var geometry=ModelSurfaceGeometry.bake(data,mask);
            var expected=new ArrayList<ModelBox>(); ModelCubeGeometry.bake(cube,mask).append(expected,"cpu",new Matrix4f(),0,0,0);
            var actual=new ArrayList<ModelBox>(); geometry.append(actual,"gpu",new Matrix4f(),0,0,0);
            assertFalse(geometry.solid());
            int hit=0,miss=0;
            for(var face:cube.polygons) {
                var v=face.vertices(); var a=v[0]; var b=v[1]; var d=v[3];
                var u=new org.joml.Vector3f(b.x()-a.x(),b.y()-a.y(),b.z()-a.z());
                var w=new org.joml.Vector3f(d.x()-a.x(),d.y()-a.y(),d.z()-a.z());
                var n=new org.joml.Vector3f(u).cross(w);
                if(n.lengthSquared()<1e-8) continue;
                n.normalize().mul(.0001F);
                for(int iy=0;iy<23;iy++) for(int ix=0;ix<23;ix++) {
                    float s=(ix+.37F)/23,t=(iy+.61F)/23;
                    double x=(a.x()+s*u.x+t*w.x)/16,y=(a.y()+s*u.y+t*w.y)/16,z=(a.z()+s*u.z+t*w.z)/16;
                    boolean opaque=mask.pixel(a.u()+s*(b.u()-a.u())+t*(d.u()-a.u()),a.v()+s*(b.v()-a.v())+t*(d.v()-a.v()));
                    boolean got=actual.stream().anyMatch(box -> box.clip(x-n.x,y-n.y,z-n.z,x+n.x,y+n.y,z+n.z)>=0);
                    assertEquals(opaque,got,"GPU: пустой пиксель превратился в коробку");
                    if(got) hit++; else miss++;
                }
            }
            assertTrue(hit>100 && miss>100);
        }
    }
    @Test void rotatedPaperIslandInVolumeGroupIsRemovedOnlyForLargeMobs() {
        // Объёмный тетраэдр и отдельное наклонное крыло в одной OBJ-группе.
        float[] data={0,0,0,.4F,0,0,0,.4F,0, 0,0,0,0,0,.4F,.4F,0,0,
                0,0,0,0,.4F,0,0,0,.4F, .4F,0,0,0,0,.4F,0,.4F,0,
                .6F,.6F,.6F,1,.6F,1,.6F,1,.6F};
        var mesh=ModelMesh.register(new ModelMesh(data));
        var box=new ModelBox("mixed",0,0,0,1,0,0,0,1,0,0,0,1).withMesh(mesh.id());
        assertFalse(PlaneHitPolicy.flat(box),"Вся группа объёмна, проверка её AABB не найдёт крыло");
        var small=PlaneHitPolicy.filter(.5F,.5F,List.of(box));
        var large=PlaneHitPolicy.filter(3,3,List.of(box));
        assertEquals(data.length,ModelMesh.get(small.getFirst().mesh()).triangles().length);
        assertEquals(36,ModelMesh.get(large.getFirst().mesh()).triangles().length);
        assertTrue(large.getFirst().clip(.1,.1,-1,.1,.1,1)>=0);
        assertEquals(-1,large.getFirst().clip(.7,.7,0,.7,.7,1));
    }
}
