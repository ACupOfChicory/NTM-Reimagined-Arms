package acup.gunfeel.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AmmoModelsTest {
    private static AmmoModels.Model m(String id) {
        AmmoModels.Model model = AmmoModels.of(id);
        assertNotNull(model, id);
        return model;
    }

    private static float maxRadius(float[] pos) {
        float max = 0;
        for (int i = 0; i < pos.length; i += 3) {
            max = Math.max(max, (float) Math.hypot(pos[i], pos[i + 1]));
        }
        return max;
    }

    // length of the nose part (z < 0, the nose looks to -Z) whose radius is under a fraction of R
    private static float tipLength(AmmoModels.Model model, float fraction) {
        float zMin = Float.MAX_VALUE, zMax = -Float.MAX_VALUE;
        for (int i = 0; i < model.positions.length; i += 3) {
            float z = model.positions[i + 2];
            if (z < 0 && Math.hypot(model.positions[i], model.positions[i + 1]) < fraction * model.radius * 0.999F) {
                zMin = Math.min(zMin, z);
                zMax = Math.max(zMax, z);
            }
        }
        return zMax - zMin;
    }

    @Test
    void everyAmmoBuilds() {
        assertEquals(58, AmmoModels.ids().size());
        System.out.printf("%-14s %-7s %5s %9s %9s%n", "id", "kind", "tris", "radius", "length");
        for (String id : AmmoModels.ids()) {
            AmmoModels.Model model = m(id);
            assertTrue(model.triangles() > 0, id);
            assertEquals(0, model.positions.length % 9, id);
            int verts = model.positions.length / 3;
            assertEquals(verts * 3, model.normals.length, id);
            assertEquals(verts, model.colors.length, id);
            assertEquals(verts % 3, 0, id);
            for (float f : model.positions) {
                assertFalse(Float.isNaN(f) || Float.isInfinite(f), id);
            }
            for (float f : model.normals) {
                assertFalse(Float.isNaN(f) || Float.isInfinite(f), id);
            }
            if (model.mushroom != null) {
                assertEquals(model.positions.length, model.mushroom.length, id);
            }
            System.out.printf("%-14s %-7s %5d %9.5f %9.5f%n", id, model.capsule ? "capsule" : "ball",
                    model.triangles(), model.radius, model.length);
        }
    }

    @Test
    void trianglesFaceOutwards() {
        for (String id : AmmoModels.ids()) {
            float[] p = m(id).positions;
            double volume = 0;
            for (int t = 0; t < p.length; t += 9) {
                volume += p[t] * (p[t + 4] * p[t + 8] - p[t + 5] * p[t + 7])
                        - p[t + 1] * (p[t + 3] * p[t + 8] - p[t + 5] * p[t + 6])
                        + p[t + 2] * (p[t + 3] * p[t + 7] - p[t + 4] * p[t + 6]);
            }
            assertTrue(volume > 0, id + " volume " + volume);
        }
    }

    @Test
    void sizes() {
        float s = AmmoModels.SHOW;
        AmmoModels.Model p9 = m("p9_fmj");
        assertEquals(0.0045F * s, p9.radius, 1e-4F);
        assertEquals(0.0155F * s, p9.length, 1e-4F);
        AmmoModels.Model bmg = m("bmg50_ap");
        assertEquals(0.006475F * s, bmg.radius, 1e-4F);
        assertEquals(0.058F * s, bmg.length, 1e-4F);
        AmmoModels.Model g12 = m("g12");
        assertFalse(g12.capsule);
        assertEquals(g12.radius, 0.0F + g12.length / 2, 1e-6F);
        assertEquals(g12.radius, g12.positions.length > 0 ? g12.radius : 0, 0);
        assertEquals(maxRadiusOfBall(g12), g12.radius, 1e-4F);
        // radius = d / 2000 * SHOW from the table: 12 gauge shot is d mm
        assertEquals(g12.radius, (float) (g12.radius / s) * s, 1e-6F);
    }

    private static float maxRadiusOfBall(AmmoModels.Model ball) {
        float max = 0;
        for (int i = 0; i < ball.positions.length; i += 3) {
            max = Math.max(max, (float) Math.sqrt(ball.positions[i] * ball.positions[i]
                    + ball.positions[i + 1] * ball.positions[i + 1] + ball.positions[i + 2] * ball.positions[i + 2]));
        }
        return max;
    }

    @Test
    void mushrooms() {
        AmmoModels.Model jhp = m("m44_jhp");
        assertNotNull(jhp.mushroom);
        assertTrue(jhp.expands);
        assertEquals(1F, jhp.expandAmount);
        float jhpRatio = maxRadius(jhp.mushroom) / jhp.radius;
        assertEquals(1.75F, jhpRatio, 1.75F * 0.05F);
        AmmoModels.Model sp = m("m44_sp");
        assertNotNull(sp.mushroom);
        assertEquals(0.47F, sp.expandAmount);
        float spRatio = maxRadius(sp.mushroom) / sp.radius;
        assertEquals(1.35F, spRatio, 1.35F * 0.05F);
        System.out.printf("mushroom max radius / R: m44_jhp %.3f, m44_sp %.3f%n", jhpRatio, spRatio);
        AmmoModels.Model fmj = m("m44_fmj");
        assertNull(fmj.mushroom);
        assertFalse(fmj.expands);
        float[] half = jhp.morph(0.5F, null);
        assertEquals(jhp.positions[5] + (jhp.mushroom[5] - jhp.positions[5]) * 0.5F, half[5], 1e-6F);
    }

    @Test
    void armourPiercingNoseIsSharper() {
        float ap = tipLength(m("r762_ap"), 0.5F);
        float fmj = tipLength(m("r762_fmj"), 0.5F);
        System.out.printf("r762 nose length under 0.5 R: ap %.5f, fmj %.5f%n", ap, fmj);
        assertTrue(ap > fmj);
    }

    @Test
    void colorsComeFromTheTable() {
        assertEquals(0x474747, m("bmg50_ap").noseColor);
        assertEquals(0x767676, m("bmg50_ap").bodyColor);
        for (String id : AmmoModels.ids()) {
            AmmoModels.Model model = m(id);
            List<Integer> allowed = new ArrayList<>(List.of(model.noseColor, model.bodyColor));
            for (int c : model.colors) {
                assertTrue(allowed.contains(c), id);
            }
        }
    }

    @Test
    void uraniumMiniBullets() {
        AmmoModels.Model du = m("g10_du");
        assertTrue(du.capsule);
        assertEquals("mini", du.shape);
        assertEquals("flechette", m("g12_flechette").shape);
        assertEquals(0.003F * AmmoModels.SHOW, du.radius, 1e-4F);
        assertEquals(0.014F * AmmoModels.SHOW, du.length, 1e-4F);
        assertEquals(0x3f5a3a, du.bodyColor);
        assertEquals(0x5d7a55, du.noseColor);
    }

    @Test
    void ammoPaths() {
        assertNotNull(AmmoModels.of("ammo_standard_m44_jhp"));
        assertNotNull(AmmoModels.of("ammo_secret_b75"));
        assertNull(AmmoModels.of("ammo_standard_nothing"));
    }
}
