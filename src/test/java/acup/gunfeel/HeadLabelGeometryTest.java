package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class HeadLabelGeometryTest {
    private ModelBox tetra(double z) {
        return new ModelBox("anonymous",0,0,z,1,0,0,0,1,0,0,0,1).withMesh(ModelMeshTest.tetrahedron().id());
    }
    @Test void clickThroughEmptyEnvelopeCannotSelectInvisibleGeometry() {
        assertNull(HeadLabelGeometry.pick(List.of(tetra(0)),new Vec3(.8,.8,-1),new Vec3(.8,.8,2)));
        var hit=HeadLabelGeometry.pick(List.of(tetra(0)),new Vec3(.1,.1,-1),new Vec3(.1,.1,2));
        assertNotNull(hit);
        assertEquals(1.0/3,hit.t(),1e-7);
        assertEquals("0:-1",hit.token(false));
        assertFalse(hit.token(true).endsWith(":-1"));
    }
    @Test void nearestVisibleSurfaceWinsRegardlessOfPartOrder() {
        var hit=HeadLabelGeometry.pick(List.of(tetra(3),tetra(0)),new Vec3(.1,.1,-1),new Vec3(.1,.1,5));
        assertNotNull(hit);
        assertEquals(1,hit.box());
        assertEquals(1.0/6,hit.t(),1e-7);
    }
}
