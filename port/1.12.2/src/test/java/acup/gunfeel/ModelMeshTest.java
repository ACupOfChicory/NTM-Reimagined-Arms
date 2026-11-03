package acup.gunfeel;

// port: copy of 26.2 src/test/java/acup/gunfeel/ModelMeshTest.java at 0762086 without maskmanEnvelopeCornersNoLongerCountAsGeometry
// (it reads an NTM 26.2 OBJ model through ModelMeshCapture, which the port has not)

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ModelMeshTest {
    @Test void bothSidesOfPaperKeepBoundaryEvenWithDifferentDiagonals() {
        var mesh=new ModelMesh(new float[]{
                0,0,0,1,0,0,1,1,0, 0,0,0,1,1,0,0,1,0,
                0,1,0,1,0,0,0,0,0, 0,1,0,1,1,0,1,0,0});
        assertEquals(24,mesh.edges().length,"Две стороны одной плоскости не гасят четыре внешних ребра");
        assertTrue(mesh.clip(new double[]{.3,.4,-1},new double[]{.3,.4,1})>=0);
        assertEquals(-1,mesh.clip(new double[]{.3,.4,.1},new double[]{.3,.4,.2}),"У бумаги нет внутреннего объёма");
    }
    static ModelMesh tetrahedron() {
        return ModelMesh.register(new ModelMesh(new float[] {
                0,0,0, 0,1,0, 1,0,0,
                0,0,0, 1,0,0, 0,0,1,
                0,0,0, 0,0,1, 0,1,0,
                1,0,0, 0,1,0, 0,0,1}));
    }

    @Test void emptyBoundingCornerIsMissAndRealSurfaceIsHit() {
        var mesh = tetrahedron();
        var envelope = new ModelBox("mesh", 0,0,0, 1,0,0, 0,1,0, 0,0,1);
        var exact = envelope.withMesh(mesh.id());
        assertTrue(envelope.clip(.8,.8,-1, .8,.8,2) >= 0);
        assertEquals(-1, exact.clip(.8,.8,-1, .8,.8,2));
        assertEquals(1.0 / 3, exact.clip(.1,.1,-1, .1,.1,2), 1e-8);
        assertEquals(0, exact.clip(.1,.1,.1, .1,.1,2));
        assertEquals(0, exact.clip(.1,.1,.1, .1,.1,.1));
        assertEquals(-1, exact.clip(.8,.8,.8, .8,.8,.8));
    }

    @Test void affinePoseKeepsSurfaceAfterRotationReflectionAndScale() {
        var shape = tetrahedron();
        // xWorld=4-2y, yWorld=7+3x, zWorld=-2+z.
        var exact = new ModelBox("mesh", 4,7,-2, 0,3,0, -2,0,0, 0,0,1).withMesh(shape.id());
        assertTrue(exact.valid(16));
        assertTrue(exact.clip(3.8,7.3,-3, 3.8,7.3,0) >= 0);
        assertEquals(-1, exact.clip(2.4,9.4,-3, 2.4,9.4,0));
        assertEquals(6 * 6, shape.edges().length, "У тетраэдра шесть настоящих рёбер");
    }

    // Независимая проверка: пересекаем плоскость каждого треугольника, затем проверяем сторону трёх рёбер.
    private static double reference(float[] mesh, double x, double y) {
        double nearest = Double.POSITIVE_INFINITY;
        for (int k = 0; k < mesh.length; k += 9) {
            double[] a = {mesh[k],mesh[k+1],mesh[k+2]}, b = {mesh[k+3],mesh[k+4],mesh[k+5]}, c = {mesh[k+6],mesh[k+7],mesh[k+8]};
            double[] n = cross(subtract(b,a), subtract(c,a));
            if (Math.abs(n[2]) < 1e-12) continue;
            double z = a[2] - (n[0]*(x-a[0]) + n[1]*(y-a[1])) / n[2];
            double[] p = {x,y,z};
            double s0 = dot(cross(subtract(b,a),subtract(p,a)),n);
            double s1 = dot(cross(subtract(c,b),subtract(p,b)),n);
            double s2 = dot(cross(subtract(a,c),subtract(p,c)),n);
            if (s0 >= -1e-12 && s1 >= -1e-12 && s2 >= -1e-12 && z >= -1 && z <= 2)
                nearest = Math.min(nearest, (z+1)/3);
        }
        return Double.isFinite(nearest) ? nearest : -1;
    }
    private static double[] subtract(double[] a,double[] b) { return new double[] {a[0]-b[0],a[1]-b[1],a[2]-b[2]}; }
    private static double[] cross(double[] a,double[] b) { return new double[] {a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]}; }
    private static double dot(double[] a,double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
}
