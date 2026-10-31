package acup.gunfeel.obj;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

// port/continuum: the OBJ part a Gem geometry draws is only in its name (WavefrontObj.ObjGeometry)
class HFRWavefrontObjectTest {
    @Test
    void readsTheSelectionOfAnObjGeometryName() {
        assertEquals(new HFRWavefrontObject.Name("hbm:models/weapons/maresleg.obj", '+', List.of("Gun")),
                HFRWavefrontObject.parse("hbm:models/weapons/maresleg.obj#+Gun"));
        assertEquals(new HFRWavefrontObject.Name("hbm:models/weapons/sexy.obj", '+', List.of("Barrel", "Gun", "Hood")),
                HFRWavefrontObject.parse("hbm:models/weapons/sexy.obj#+Barrel,Gun,Hood"));
        assertEquals(new HFRWavefrontObject.Name("hbm:models/weapons/whiskey.obj", '*', List.of()),
                HFRWavefrontObject.parse("hbm:models/weapons/whiskey.obj#*#2"));
        assertEquals(new HFRWavefrontObject.Name("hbm:models/weapons/g3.obj", '-', List.of("Scope", "Silencer")),
                HFRWavefrontObject.parse("hbm:models/weapons/g3.obj/face_normals#-Scope,Silencer"));
        assertEquals(new HFRWavefrontObject.Name("hbm:models/weapons/x.obj", '=', List.of("metal")),
                HFRWavefrontObject.parse("hbm:models/weapons/x.obj@1/4#=metal"));
    }

    @Test
    void otherGeometriesAreNotObjParts() {
        assertNull(HFRWavefrontObject.parse("b92_box"));
        assertNull(HFRWavefrontObject.parse("hbm:models/weapons/b92.mesh#+Gun"));
        assertNull(HFRWavefrontObject.parse("hbm:models/weapons/b92.obj#"));
    }

    @Test
    void facesAreTrianglesWithTheLastVertexTwice() {
        float[] tri = new float[3 * GroupObject.STRIDE];
        for (int v = 0; v < 3; v++) for (int k = 0; k < GroupObject.STRIDE; k++) tri[v * GroupObject.STRIDE + k] = v * 10 + k;
        GroupObject g = new GroupObject("Gun", tri);
        assertEquals(1, g.faceCount());
        float[] q = g.quads(true);
        assertEquals(4 * GroupObject.STRIDE, q.length);
        assertEquals(20F, q[3 * GroupObject.STRIDE]);
        assertArrayEquals(new float[] {0F, 1F, 2F, 20F, 21F, 22F}, g.bounds());
    }
}
