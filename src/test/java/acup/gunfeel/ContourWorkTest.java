package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class ContourWorkTest {
    @Test void spatialQueryDoesNotVisitEveryTriangleForAnIsolatedEdge() {
        var boxes=new ArrayList<ModelBox>();
        for(int i=0;i<64;i++) boxes.add(new ModelBox("part"+i,i*4,0,0,1,0,0,0,1,0,0,0,1));
        var surface=new ContourSurface(boxes);
        var lines=new ArrayList<Vec3>();
        surface.append(lines,Vec3.ZERO,new Vec3(0,1,0),List.of(new double[]{0,1}));
        assertEquals(2,lines.size()); assertEquals(1,lines.getFirst().distanceTo(lines.getLast()),1e-8);
        assertTrue(surface.triangleChecks()<64,"Посещены "+surface.triangleChecks()+" из 768 треугольников");
    }

    @Test void rigidMotionReusesContourButMovesEveryVertexInTheCurrentFrame() {
        ModelContours.clear();
        var base=List.of(new ModelBox("body",0,0,0,2,0,0,0,1,0,0,0,1),
                new ModelBox("head",2,0,0,1,0,0,0,2,0,0,0,1));
        var contour=ModelContours.edges(base);
        var gpuGeometry=ModelContours.outline(base).edges();
        long builds=ModelContours.builds();
        for(int frame=1;frame<=80;frame++) {
            var matrix=new Matrix4f().translate(14.7F,100.5F,-21.3F).rotateXYZ(frame*.01F,frame*.07F,frame*.03F);
            var posed=base.stream().map(b -> transform(b,matrix)).toList();
            var lines=ModelContours.edges(posed);
            assertSame(gpuGeometry,ModelContours.outline(posed).edges(),"Общее движение не должно заново загружать контур в GPU");
            assertEquals(builds,ModelContours.builds(),"Поворот без изменения позы перестроил топологию");
            assertEquals(contour.size(),lines.size());
            for(int j=0;j<contour.size();j++) for(int i=0;i<contour.get(j).length;i++) {
                var point=contour.get(j)[i];
                var expected=matrix.transformPosition((float)point.x,(float)point.y,(float)point.z,new Vector3f());
                assertEquals(0,lines.get(j)[i].distanceTo(new Vec3(expected)),2e-5,"Контур отстал от текущей матрицы");
            }
        }
        var changed=new ArrayList<>(base);
        changed.set(1,new ModelBox("head",2.1,0,0,1,0,0,0,2,0,0,0,1));
        ModelContours.edges(changed);
        assertNotSame(gpuGeometry,ModelContours.outline(changed).edges(),"Изменение позы требует нового GPU-контура");
        assertEquals(builds+1,ModelContours.builds(),"Изменение относительной позы должно перестроить контур");
        var resized=ModelContours.edges(base.stream().map(b -> transform(b,new Matrix4f().scaling(2))).toList());
        assertEquals(builds+2,ModelContours.builds(),"Изменение размера не является общим поворотом");
        assertEquals(length(contour)*2,length(resized),1e-5);
    }
    private static double length(List<Vec3[]> strips) {
        double sum=0; for(var strip:strips) for(int i=0;i<strip.length;i+=2) sum+=strip[i].distanceTo(strip[i+1]); return sum;
    }
    private static ModelBox transform(ModelBox b,Matrix4f matrix) {
        var p=matrix.transformPosition((float)b.x(),(float)b.y(),(float)b.z(),new Vector3f());
        var u=matrix.transformDirection((float)b.ux(),(float)b.uy(),(float)b.uz(),new Vector3f());
        var v=matrix.transformDirection((float)b.vx(),(float)b.vy(),(float)b.vz(),new Vector3f());
        var w=matrix.transformDirection((float)b.wx(),(float)b.wy(),(float)b.wz(),new Vector3f());
        return new ModelBox(b.part(),p.x,p.y,p.z,u.x,u.y,u.z,v.x,v.y,v.z,w.x,w.y,w.z,b.mesh());
    }
}
