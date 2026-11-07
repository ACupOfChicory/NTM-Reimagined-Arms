package acup.gunfeel.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hbm.render.loader.GroupObject;
import com.hbm.render.loader.ObjMesh;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // NTM 1.0.3's casing of each calibre (XFactory*: the base SpentCasing every round of the calibre clones);
    // the .357 has none, stones neither
    static final Map<String, AmmoModels.Casing> NTM = Map.of(
            "p22", new AmmoModels.Casing("STRAIGHT", 0.5F, 0.5F, 0.5F),
            "p9", new AmmoModels.Casing("STRAIGHT", 1F, 1F, 0.75F),
            "m44", new AmmoModels.Casing("STRAIGHT", 1F, 1F, 1F),
            "r556", new AmmoModels.Casing("BOTTLENECK", 0.8F, 0.8F, 0.8F),
            "r762", new AmmoModels.Casing("BOTTLENECK", 1F, 1F, 1F),
            "bmg50", new AmmoModels.Casing("BOTTLENECK", 1.5F, 1.5F, 1.5F),
            "b75", new AmmoModels.Casing("STRAIGHT", 2F, 2F, 1.5F),
            "g12", new AmmoModels.Casing("SHOTGUN", 0.75F, 0.75F, 0.75F),
            "g10", new AmmoModels.Casing("SHOTGUN", 1F, 1F, 1F));

    // what NTM draws of a casing, blocks: {length, width}
    static double[] drawn(AmmoModels.Casing c) {
        double[] mesh = AmmoModels.CASING_MESH.get(c.type());
        return new double[] {AmmoModels.CASING_DRAW * c.sz() * mesh[0],
                AmmoModels.CASING_DRAW * Math.max(c.sx(), c.sy()) * mesh[1]};
    }

    static AmmoModels.Model sized(String id, double scale) {
        String cal = id.contains("_") ? id.substring(0, id.indexOf('_')) : id;
        AmmoModels.Model model = AmmoModels.of(id, NTM.get(cal), scale);
        assertNotNull(model, id);
        return model;
    }

    @Test
    void casingMeshIsMeasured() throws Exception {
        Map<String, float[]> b = new HashMap<>();
        try (var stream = AmmoModelsTest.class.getResourceAsStream("/assets/hbm/models/effect/casings.hbm_mesh")) {
            assertNotNull(stream);
            for (GroupObject g : ObjMesh.read(stream, "casings")) b.put(g.name, g.bounds());
        }
        // bounds: min x y z, max x y z; the casing lies along Z
        assertEquals(b.get("Straight")[5] - b.get("Straight")[2], AmmoModels.CASING_MESH.get("STRAIGHT")[0], 1e-4);
        assertEquals(b.get("Straight")[3] - b.get("Straight")[0], AmmoModels.CASING_MESH.get("STRAIGHT")[1], 1e-4);
        assertEquals(b.get("Bottleneck")[5] - b.get("Bottleneck")[2], AmmoModels.CASING_MESH.get("BOTTLENECK")[0], 1e-4);
        assertEquals(b.get("Bottleneck")[3] - b.get("Bottleneck")[0], AmmoModels.CASING_MESH.get("BOTTLENECK")[1], 1e-4);
        assertEquals(b.get("ShotgunCase")[5] - b.get("Shotgun")[2], AmmoModels.CASING_MESH.get("SHOTGUN")[0], 1e-4);
        assertEquals(b.get("Shotgun")[3] - b.get("Shotgun")[0], AmmoModels.CASING_MESH.get("SHOTGUN")[1], 1e-4);
    }

    // the bullet is to NTM's casing as the real bullet is to the real case, along and across; before, ×6 real size
    @Test
    void sizedByTheCasing() {
        System.out.printf("%-14s %8s %8s | %8s %8s | %8s %8s%n", "id", "len was", "d was", "len now", "d now", "casing", "width");
        for (String id : AmmoModels.ids()) {
            String cal = id.contains("_") ? id.substring(0, id.indexOf('_')) : id;
            AmmoModels.Model was = m(id), now = sized(id, 1);
            AmmoModels.Casing c = NTM.get(cal);
            double wasLen = was.length / AmmoModels.FALLBACK * 6, wasD = 2 * was.radius / AmmoModels.FALLBACK * 6;
            double[] drawn = c == null ? new double[] {0, 0} : drawn(c);
            System.out.printf("%-14s %8.4f %8.4f | %8.4f %8.4f | %8.4f %8.4f%n", id, wasLen, wasD, now.length,
                    2 * now.radius, drawn[0], drawn[1]);
            assertEquals(c != null, now.byCasing, id);
            if (c == null) {
                assertEquals(was.length, now.length, 1e-6F, id);
                continue;
            }
            double[] real = AmmoModels.CASE.get(cal);
            // across: 2r over the casing's width as the real diameter over the case's
            assertEquals(2 * was.radius / AmmoModels.FALLBACK * 1000 / real[0], 2 * now.radius / drawn[1], 1e-4, id);
            // along (a ball is as long as it is wide)
            if (now.capsule) assertEquals(was.length / AmmoModels.FALLBACK * 1000 / real[1], now.length / drawn[0], 1e-4, id);
            // no bullet longer or wider than the casing it came out of (the bolt is mostly bullet)
            if (!cal.equals("b75")) {
                assertTrue(now.length < drawn[0], id + " length " + now.length + " casing " + drawn[0]);
                assertTrue(2 * now.radius < drawn[1] * 1.001, id + " width " + 2 * now.radius + " casing " + drawn[1]);
            }
        }
        // the numbers of the task: rifle and .50 rounds were 3 to 4 times the size of NTM's casings
        assertEquals(0.0486, sized("p9_fmj", 1).length, 5e-4);
        assertEquals(0.0371, sized("r556_fmj", 1).length, 5e-4);
        assertEquals(0.0510, sized("r762_fmj", 1).length, 5e-4);
        assertEquals(0.0789, sized("bmg50_fmj", 1).length, 5e-4);
        assertEquals(0.0140, 2 * sized("g12", 1).radius, 5e-4);
    }

    @Test
    void withoutCasingRealSizeTimesFallback() {
        AmmoModels.Model m357 = sized("m357_fmj", 1), stone = sized("stone", 1);
        assertFalse(m357.byCasing);
        assertEquals(0.015 * AmmoModels.FALLBACK, m357.length, 1e-5);
        assertEquals(0.0091 / 2 * AmmoModels.FALLBACK, m357.radius, 1e-5);
        assertEquals(0.015 / 2 * AmmoModels.FALLBACK, stone.radius, 1e-5);
        // a casing the table cannot size: an unknown type, a zero or broken scale
        for (AmmoModels.Casing bad : List.of(new AmmoModels.Casing("AR2", 1, 1, 1), new AmmoModels.Casing("STRAIGHT", 0, 0, 0),
                new AmmoModels.Casing("STRAIGHT", Float.NaN, 1, 1), new AmmoModels.Casing("STRAIGHT", 1, 1, Float.POSITIVE_INFINITY))) {
            AmmoModels.Model p9 = AmmoModels.of("ammo_standard_p9_fmj", bad, 1);
            assertNotNull(p9);
            assertFalse(p9.byCasing, bad.toString());
            assertEquals(0.0155 * AmmoModels.FALLBACK, p9.length, 1e-5, bad.toString());
        }
    }

    @Test
    void scaleMultipliesTheSize() {
        AmmoModels.Model one = sized("r762_jhp", 1), two = sized("r762_jhp", 2);
        assertEquals(2 * one.radius, two.radius, 1e-6F);
        assertEquals(2 * one.length, two.length, 1e-6F);
        assertEquals(2 * maxRadius(one.mushroom), maxRadius(two.mushroom), 1e-5F);
        assertTrue(one == sized("r762_jhp", 1), "built once");
        AmmoModels.Model g12 = sized("g12", 1);
        assertFalse(g12.capsule);
        assertEquals(g12.radius, g12.length / 2, 1e-6F);
        assertEquals(maxRadiusOfBall(g12), g12.radius, 1e-4F);
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
        assertEquals(0.003 * AmmoModels.FALLBACK, du.radius, 1e-5);
        assertEquals(0.014 * AmmoModels.FALLBACK, du.length, 1e-5);
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
