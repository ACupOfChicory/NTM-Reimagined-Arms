package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class HeadLabelCutsTest {
    @Test void cutKeepsUpperVolumeAndProducesBoundaryOnThePlane() {
        var mesh=ModelMeshTest.tetrahedron();
        var box=new ModelBox("neck",0,0,0,1,0,0,0,2,0,0,0,1).withMesh(mesh.id());
        var plane=new HeadLabelCuts.Plane(0,1,0,-1);
        var edges=HeadLabelCuts.edges(List.of(box),List.of("0:-1"),Map.of("0:-1",List.of(plane)));
        assertFalse(edges.isEmpty());
        assertTrue(edges.stream().flatMap(Arrays::stream).allMatch(p->p.y>=1-1e-8));
        assertTrue(edges.stream().anyMatch(e->Math.abs(e[0].y-1)<1e-8 && Math.abs(e[1].y-1)<1e-8));
        assertFalse(HeadLabelCuts.contains(new Vec3(.1,.5,.1),List.of(plane)));
        assertTrue(HeadLabelCuts.contains(new Vec3(.1,1.5,.1),List.of(plane)));
        assertFalse(HeadLabelCuts.contains(new Vec3(.1,1.5,.1),List.of(plane.flip())));
    }
    @Test void consecutiveCutsIntersectInsteadOfRestoringPreviouslyRemovedNeck() {
        var planes=List.of(new HeadLabelCuts.Plane(0,1,0,-1),new HeadLabelCuts.Plane(1,0,0,-.2));
        assertTrue(HeadLabelCuts.contains(new Vec3(.3,1.5,0),planes));
        assertFalse(HeadLabelCuts.contains(new Vec3(.1,1.5,0),planes));
        assertFalse(HeadLabelCuts.contains(new Vec3(.3,.5,0),planes));
    }
}
