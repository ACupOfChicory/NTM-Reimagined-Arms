package acup.gunfeel;

import java.util.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContourIntersectionTest {
    @Test void creaseSurvivesWhenItIsAlsoASeamInAnotherPlane() {
        float[] triangles={
            0,0,.5f, 1,0,.5f, 1,.5f,.5f, 0,0,.5f, 1,.5f,.5f, 0,.5f,.5f,
            0,.5f,.5f, 1,.5f,.5f, 1,1,.5f, 0,.5f,.5f, 1,1,.5f, 0,1,.5f,
            0,.5f,.5f, 1,.5f,.5f, 1,.5f,1, 0,.5f,.5f, 1,.5f,1, 0,.5f,1
        };
        var mesh=ModelMesh.register(new ModelMesh(triangles));
        var box=new ModelBox("intersection",0,0,0,1,0,0,0,1,0,0,0,1,mesh.id());
        var result=new ArrayList<Vec3>();
        new ContourSurface(List.of(box)).append(result,new Vec3(0,.5,.5),new Vec3(1,.5,.5),List.of(new double[]{0,1}),box);
        assertFalse(result.isEmpty(),"Угол должен остаться, хотя в горизонтальной плоскости это шов");
    }
}
