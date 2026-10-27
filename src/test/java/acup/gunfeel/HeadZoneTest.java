package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class HeadZoneTest {
    private ModelBox cube(double x,double size) { return new ModelBox("anonymous",x,0,0,size,0,0,0,size,0,0,0,size); }
    @Test void duplicatePartDoesNotTurnTheSameHeadPointIntoANegativeExample() {
        var head=new HeadZone(cube(0,1),List.of());
        assertTrue(HeadZone.contains(List.of(head),new Vec3(.5,.5,.5)));
        assertTrue(HeadZone.contains(List.of(head),new Vec3(0,.5,.5)));
        assertTrue(HeadZone.visible(List.of(cube(0,1),cube(0,1)),new Vec3(0,.5,.5),new Vec3(1,0,0),4));
    }
    @Test void internalSurfacesAreHiddenButTheOutsideRemainsVisible() {
        var body=List.of(cube(0,2),cube(.5,1));
        assertFalse(HeadZone.visible(body,new Vec3(.5,.5,.5),new Vec3(1,0,0),8));
        assertTrue(HeadZone.visible(body,new Vec3(0,.5,.5),new Vec3(1,0,0),8));
    }
    @Test void headCutsDoNotIncludeTheNeckOrAnEmptyMeshCorner() {
        var cut=new HeadZone(cube(0,1),List.of(new HeadZone.Plane(0,1,0,-.7)));
        assertTrue(cut.contains(new Vec3(.1,.8,.1)));assertFalse(cut.contains(new Vec3(.1,.3,.1)));
        var exact=new HeadZone(cube(0,1).withMesh(ModelMeshTest.tetrahedron().id()),List.of());
        assertFalse(exact.contains(new Vec3(.8,.8,.8)));assertTrue(exact.contains(new Vec3(.1,.1,.1)));
    }
}
