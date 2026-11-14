package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.ai.client.BotAnimation;
import acup.gunfeel.ai.client.BotModel;
import acup.gunfeel.ai.client.LayerSeams;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.HumanoidArm;

// The bots' model of ten parts: straight, each limb is vanilla's box with vanilla's skin on it; the hitbox has ten
// parts; the item is held at the end of the forearm.
class BotModelTest {
    /** NTM's RenderHumanoidMob bakes this for the FBI agent and the test dummy */
    static ModelPart vanilla() {
        return LayerDefinition.create(HumanoidModel.createMesh(CubeDeformation.NONE, 0F), 64, 32).bakeRoot();
    }

    /** a vertex in its limb's frame: a child's moved by its pivot (straight) */
    record V(float x, float y, float z, float u, float v, org.joml.Vector3fc normal) {
    }

    /** a cube's faces lying flat at height {@code y} (its frame) */
    static int endFaces(ModelPart.Cube cube, float y) {
        int n = 0;
        for (var p : cube.polygons) {
            boolean flat = true;
            for (var v : p.vertices()) flat &= Math.abs(v.y() - y) < 1e-4F;
            if (flat) n++;
        }
        return n;
    }

    static List<V> vertices(ModelPart.Cube cube, float dx, float dy, float dz) {
        var out = new java.util.ArrayList<V>();
        for (var p : cube.polygons)
            for (var v : p.vertices()) out.add(new V(v.x() + dx, v.y() + dy, v.z() + dz, v.u(), v.v(), p.normal()));
        return out;
    }

    /**
     * The skin's (u, v) at a point of a face of the vanilla cube: the face whose plane holds it and whose rectangle it
     * is in, u and v affine across it; null if it is on no face.
     */
    static float[] uvAt(ModelPart.Cube cube, V at) {
        float x = at.x(), y = at.y(), z = at.z();
        for (var p : cube.polygons) {
            if (p.normal().distance(at.normal()) > 1e-5F) continue;
            var vs = p.vertices();
            // the face's fixed axis: the one all four vertices share
            int fixed = -1;
            for (int a = 0; a < 3; a++) {
                float c0 = coord(vs[0], a);
                boolean same = true;
                for (var v : vs) same &= Math.abs(coord(v, a) - c0) < 1e-5F;
                if (same) fixed = a;
            }
            float[] pt = { x, y, z };
            if (fixed < 0 || Math.abs(coord(vs[0], fixed) - pt[fixed]) > 1e-5F) continue;
            int a = (fixed + 1) % 3, b = (fixed + 2) % 3;
            float amin = Float.MAX_VALUE, amax = -Float.MAX_VALUE, bmin = Float.MAX_VALUE, bmax = -Float.MAX_VALUE;
            for (var v : vs) {
                amin = Math.min(amin, coord(v, a));
                amax = Math.max(amax, coord(v, a));
                bmin = Math.min(bmin, coord(v, b));
                bmax = Math.max(bmax, coord(v, b));
            }
            if (pt[a] < amin - 1e-5F || pt[a] > amax + 1e-5F || pt[b] < bmin - 1e-5F || pt[b] > bmax + 1e-5F) continue;
            // u, v = k0 + ka * a + kb * b, from three corners
            double[][] m = new double[3][];
            for (int i = 0; i < 3; i++) m[i] = new double[] { 1, coord(vs[i], a), coord(vs[i], b), vs[i].u(), vs[i].v() };
            double[] su = solve(m, 3), sv = solve(m, 4);
            return new float[] { (float) (su[0] + su[1] * pt[a] + su[2] * pt[b]), (float) (sv[0] + sv[1] * pt[a] + sv[2] * pt[b]) };
        }
        return null;
    }

    /** x, y, z: lowest and highest of the vertices */
    static float[] extent(List<V> vs) {
        float[] e = { Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE };
        for (var v : vs) {
            float[] c = { v.x(), v.y(), v.z() };
            for (int k = 0; k < 3; k++) {
                e[2 * k] = Math.min(e[2 * k], c[k]);
                e[2 * k + 1] = Math.max(e[2 * k + 1], c[k]);
            }
        }
        return e;
    }

    @Test
    void anotherModelsForearmIsNotOne() {
        var limb = new ModelPart(List.of(), java.util.Map.of(BotModel.FOREARM, new ModelPart(List.of(), java.util.Map.of())));
        assertNull(BotModel.lower(limb));
    }

    static float coord(ModelPart.Vertex v, int a) {
        return a == 0 ? v.x() : a == 1 ? v.y() : v.z();
    }

    /** Cramer's rule: the coefficients of column {@code col} from columns 0..2 */
    static double[] solve(double[][] m, int col) {
        double d = det(m, -1, col);
        return new double[] { det(m, 0, col) / d, det(m, 1, col) / d, det(m, 2, col) / d };
    }

    static double det(double[][] m, int replace, int col) {
        double[][] a = new double[3][3];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) a[r][c] = c == replace ? m[r][col] : m[r][c];
        return a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0])
                + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0]);
    }

    @Test
    void straightLimbsAreVanillasBoxesAndSkin() {
        ModelPart old = vanilla(), bot = BotModel.split(vanilla(), ModelPoseCaptureTest.PARTS);
        for (String limb : new String[] { "right_arm", "left_arm", "right_leg", "left_leg" }) {
            var whole = ModelPoseCaptureTest.PARTS.cubes(old.getChild(limb)).getFirst();
            ModelPart upperPart = bot.getChild(limb), lowerPart = BotModel.lower(upperPart);
            assertNotNull(lowerPart, limb);
            assertEquals(upperPart.getInitialPose(), old.getChild(limb).getInitialPose(), limb);
            var upper = ModelPoseCaptureTest.PARTS.cubes(upperPart).getFirst();
            var lower = ModelPoseCaptureTest.PARTS.cubes(lowerPart).getFirst();
            float jx = lowerPart.x, jy = lowerPart.y, jz = lowerPart.z;
            // the two halves cut the box at half its length, no gap, no overlap (the overlap is the second cube)
            assertEquals(whole.minY, upper.minY, 1e-6);
            assertEquals(upper.maxY, lower.minY + jy, 1e-6, limb);
            assertEquals(whole.maxY, lower.maxY + jy, 1e-6, limb);
            assertEquals(whole.maxY - whole.minY, 2 * (upper.maxY - upper.minY), 1e-6, limb);
            assertEquals(whole.minX, lower.minX + jx, 1e-6, limb);
            assertEquals(whole.maxZ, lower.maxZ + jz, 1e-6, limb);
            // every outer face point of each half has the skin the whole box has there; the faces where the halves
            // meet are inside and left out
            int checked = 0;
            for (var v : vertices(upper, 0, 0, 0)) {
                if (v.y() == upper.maxY) continue;
                float[] uv = uvAt(whole, v);
                assertNotNull(uv, limb + " " + v);
                assertEquals(uv[0], v.u(), 1e-5, limb + " upper u " + v);
                assertEquals(uv[1], v.v(), 1e-5, limb + " upper v " + v);
                checked++;
            }
            for (var v : vertices(lower, jx, jy, jz)) {
                if (Math.abs(v.y() - upper.maxY) < 1e-6) continue;
                float[] uv = uvAt(whole, v);
                assertNotNull(uv, limb + " " + v);
                assertEquals(uv[0], v.u(), 1e-5, limb + " lower u " + v);
                assertEquals(uv[1], v.v(), 1e-5, limb + " lower v " + v);
                checked++;
            }
            assertTrue(checked >= 24, limb + " " + checked);
            // the overlap sits inside the upper half, in from its faces
            var overlap = ModelPoseCaptureTest.PARTS.cubes(lowerPart).get(1);
            for (var v : vertices(overlap, jx, jy, jz)) {
                assertTrue(v.x() > upper.minX && v.x() < upper.maxX && v.z() > upper.minZ && v.z() < upper.maxZ, limb + " " + v);
                assertTrue(v.y() > upper.minY && v.y() < upper.maxY + 1e-3F, limb + " " + v);
            }
        }
        assertNull(BotModel.lower(bot.getChild("head")));
        assertNotNull(bot.getChild("head").getChild("hat"));
    }

    @Test
    void armourIsCutAsTheBodyGrownAsItWas() {
        var set = HumanoidModel.createArmorMeshSet(new CubeDeformation(0.5F), new CubeDeformation(1F))
                .map(mesh -> LayerDefinition.create(mesh, 64, 32).bakeRoot());
        int pieces = 0;
        for (var slot : new net.minecraft.world.entity.EquipmentSlot[] { net.minecraft.world.entity.EquipmentSlot.HEAD,
                net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS,
                net.minecraft.world.entity.EquipmentSlot.FEET }) {
            ModelPart old = set.get(slot), cut = BotModel.split(set.get(slot), ModelPoseCaptureTest.PARTS);
            for (String limb : new String[] { "right_arm", "left_arm", "right_leg", "left_leg" }) {
                var was = ModelPoseCaptureTest.PARTS.cubes(old.getChild(limb));
                ModelPart upper = cut.getChild(limb), lower = BotModel.lower(upper);
                assertNotNull(lower, slot + " " + limb);
                if (was.isEmpty()) {
                    // no piece of this slot on the limb: none on either half
                    assertTrue(ModelPoseCaptureTest.PARTS.cubes(upper).isEmpty() && ModelPoseCaptureTest.PARTS.cubes(lower).isEmpty(), slot + " " + limb);
                    continue;
                }
                pieces++;
                var whole = was.getFirst();
                var up = ModelPoseCaptureTest.PARTS.cubes(upper).getFirst();
                var low = ModelPoseCaptureTest.PARTS.cubes(lower).getFirst();
                float jx = lower.x, jy = lower.y, jz = lower.z;
                // straight, the two halves span the grown piece, as thick as it, and meet at the joint
                float[] w = extent(vertices(whole, 0, 0, 0)), a = extent(vertices(up, 0, 0, 0)), b = extent(vertices(low, jx, jy, jz));
                assertTrue(w[1] - w[0] > 4.5F, slot + " " + limb + " grown " + (w[1] - w[0]));
                for (int k = 0; k < 6; k += 2) {
                    if (k == 2) continue;
                    assertEquals(w[k], a[k], 1e-5, slot + " " + limb + " upper " + k);
                    assertEquals(w[k + 1], a[k + 1], 1e-5, slot + " " + limb + " upper " + k);
                    assertEquals(w[k], b[k], 1e-5, slot + " " + limb + " lower " + k);
                    assertEquals(w[k + 1], b[k + 1], 1e-5, slot + " " + limb + " lower " + k);
                }
                assertEquals(w[2], a[2], 1e-5, slot + " " + limb);
                assertEquals(a[3], b[2], 1e-5, slot + " " + limb + " joint");
                assertEquals(w[3], b[3], 1e-5, slot + " " + limb);
                // each half's skin is the whole piece's there; the faces where they meet are inside
                for (var vx : vertices(up, 0, 0, 0)) {
                    if (Math.abs(vx.y() - a[3]) < 1e-5F && vx.normal().y() != 0) continue;
                    float[] uv = uvAt(whole, vx);
                    assertNotNull(uv, slot + " " + limb + " " + vx);
                    assertEquals(uv[0], vx.u(), 1e-5, slot + " " + limb + " upper u " + vx);
                    assertEquals(uv[1], vx.v(), 1e-5, slot + " " + limb + " upper v " + vx);
                }
                for (var vx : vertices(low, jx, jy, jz)) {
                    if (Math.abs(vx.y() - b[2]) < 1e-5F && vx.normal().y() != 0) continue;
                    float[] uv = uvAt(whole, vx);
                    assertNotNull(uv, slot + " " + limb + " " + vx);
                    assertEquals(uv[0], vx.u(), 1e-5, slot + " " + limb + " lower u " + vx);
                    assertEquals(uv[1], vx.v(), 1e-5, slot + " " + limb + " lower v " + vx);
                }
                // no face where the halves meet, nor on the overlap's ends: the whole piece has none there, and their
                // texture (a boot's sole, a sleeve's cuff) showed through the piece's transparent stretch
                assertEquals(0, endFaces(up, a[3]), slot + " " + limb + " upper's joint face");
                assertEquals(0, endFaces(low, 0F), slot + " " + limb + " lower's joint face");
                assertEquals(1, endFaces(low, low.maxY + (b[3] - jy - low.maxY)), slot + " " + limb + " the hand's or the foot's end");
                var overlap = ModelPoseCaptureTest.PARTS.cubes(lower).get(1);
                for (var p : overlap.polygons) assertEquals(0F, p.normal().y(), 1e-6F, slot + " " + limb + " overlap's end");
            }
        }
        // the skin keeps its faces at the joint: it is solid, and they close the bend
        ModelPart skin = BotModel.split(vanilla(), ModelPoseCaptureTest.PARTS).getChild("right_leg");
        assertEquals(1, endFaces(ModelPoseCaptureTest.PARTS.cubes(skin).getFirst(), 6F));
        assertEquals(1, endFaces(ModelPoseCaptureTest.PARTS.cubes(BotModel.lower(skin)).getFirst(), 0F));
        // the chest's two sleeves, the leggings' two legs, the boots' two
        assertEquals(6, pieces);
    }

    @Test
    void hitboxHasTenParts() {
        var model = new HumanoidModel<HumanoidRenderState>(BotModel.split(vanilla(), ModelPoseCaptureTest.PARTS));
        model.setupAnim(new HumanoidRenderState());
        var boxes = ModelPoseCapture.collect(model.root(), new PoseStack(), 0, 0, 0, ModelPoseCaptureTest.PARTS).boxes();
        var names = new java.util.TreeSet<String>();
        for (var b : boxes) names.add(b.part());
        assertTrue(names.containsAll(List.of("root/head", "root/body", "root/right_arm", "root/right_arm/forearm",
                "root/left_arm", "root/left_arm/forearm", "root/right_leg", "root/right_leg/shin", "root/left_leg",
                "root/left_leg/shin")), names.toString());
    }

    /** the bot's boxes as LivingEntityRenderer puts them in the world, at its feet facing south */
    static List<ModelBox> worldBoxes(HumanoidModel<HumanoidRenderState> model) {
        var stack = new PoseStack();
        stack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180));
        stack.scale(-1, -1, 1);
        stack.translate(0, -1.501F, 0);
        return ModelPoseCapture.collect(model.root(), stack, 0, 0, 0, ModelPoseCaptureTest.PARTS).boxes();
    }

    @Test
    void agentsProfileFindsTheHeadAndTheEightLimbParts() {
        var model = new HumanoidModel<HumanoidRenderState>(BotModel.split(vanilla(), ModelPoseCaptureTest.PARTS));
        var state = new HumanoidRenderState();
        model.setupAnim(state);
        for (String type : new String[] { "hbm:entity_fbi", "hbm:entity_test_dummy" }) {
            var boxes = worldBoxes(model);
            var head = ModelHeads.resolve(type, boxes, 0F);
            assertEquals("dataset", head.source(), type + " " + head.reason());
            assertEquals(0, head.missing(), type);
            assertEquals(1, head.zones().size(), type);
            var limbs = ModelHeads.limbs(type, boxes, 0F);
            assertEquals("dataset", limbs.source(), type + " " + limbs.reason());
            assertEquals(8, limbs.zones().size(), type);
        }
        // bent at the elbow and the knee, the zones stay with their parts
        model.rightArm.xRot = -1.2F;
        BotModel.lower(model.rightArm).xRot = -1.4F;
        BotModel.lower(model.leftLeg).xRot = 1.3F;
        var limbs = ModelHeads.limbs("hbm:entity_fbi", worldBoxes(model), 0F);
        assertEquals(8, limbs.zones().size());
    }

    @Test
    void itemIsHeldAtTheEndOfTheForearm() {
        var model = new HumanoidModel<HumanoidRenderState>(BotModel.split(vanilla(), ModelPoseCaptureTest.PARTS));
        model.setupAnim(new HumanoidRenderState());
        ModelPart arm = model.rightArm, fore = BotModel.lower(arm);
        arm.xRot = -0.4F;
        fore.xRot = -1.1F;
        fore.yRot = 0.3F;
        PoseStack ps = new PoseStack();
        model.translateToHand(new HumanoidRenderState(), HumanoidArm.RIGHT, ps);
        BotAnimation.hand(model, HumanoidArm.RIGHT, ps);
        // the wrist (the arm's end on its middle line, x -1, y 10) goes where the forearm's end is
        Vector3f got = ps.last().pose().transformPosition(new Vector3f(-1F, 10F, 0F).div(16F));
        PoseStack want = new PoseStack();
        model.root().translateAndRotate(want);
        arm.translateAndRotate(want);
        fore.translateAndRotate(want);
        Vector3f end = want.last().pose().transformPosition(new Vector3f(0F, 6F, 0F).div(16F));
        assertEquals(end.x, got.x, 1e-6);
        assertEquals(end.y, got.y, 1e-6);
        assertEquals(end.z, got.z, 1e-6);
    }

    // NTM fits a suit's mesh to a limb's own largest box (ArmorWorldRenderer.fit, MixinBotSuitFit): the split limb is
    // measured whole, as vanilla's box, or the boots sit on the knees
    @Test
    void wholeLimbIsVanillasBox() {
        ModelPart old = vanilla(), bot = BotModel.split(vanilla(), ModelPoseCaptureTest.PARTS);
        for (String name : List.of("right_arm", "left_arm", "right_leg", "left_leg")) {
            ModelPart whole = BotModel.whole(bot.getChild(name), ModelPoseCaptureTest.PARTS);
            var cubes = ModelPoseCaptureTest.PARTS.cubes(whole);
            assertEquals(1, cubes.size(), name);
            var got = cubes.getFirst();
            var want = ModelPoseCaptureTest.PARTS.cubes(old.getChild(name)).getFirst();
            float[] g = { got.minX, got.minY, got.minZ, got.maxX, got.maxY, got.maxZ }, w = { want.minX, want.minY, want.minZ, want.maxX, want.maxY, want.maxZ };
            for (int i = 0; i < 6; i++) assertEquals(w[i], g[i], 1e-6F, name + " " + i);
            assertTrue(whole == BotModel.whole(bot.getChild(name), ModelPoseCaptureTest.PARTS), name + ": made once");
        }
        assertTrue(BotModel.whole(bot.getChild("head"), ModelPoseCaptureTest.PARTS) == bot.getChild("head"));
        assertTrue(BotModel.whole(old.getChild("left_leg"), ModelPoseCaptureTest.PARTS) == old.getChild("left_leg"));
    }

    /** the layer textures in a jar by the folder they are in, read off the class path; none: empty */
    static java.util.Map<String, java.awt.image.BufferedImage> textures(String folder) throws Exception {
        var out = new java.util.TreeMap<String, java.awt.image.BufferedImage>();
        for (var url : java.util.Collections.list(BotModelTest.class.getClassLoader().getResources(folder))) {
            if (!(url.openConnection() instanceof java.net.JarURLConnection jar)) continue;
            try (var file = new java.util.jar.JarFile(jar.getJarFileURL().getPath())) {
                for (var e : java.util.Collections.list(file.entries())) {
                    String n = e.getName();
                    if (!n.startsWith(folder) || !n.endsWith(".png") || n.substring(folder.length()).contains("/")) continue;
                    try (var in = file.getInputStream(e)) {
                        var image = javax.imageio.ImageIO.read(in);
                        if (image.getWidth() == 64 && image.getHeight() == 32) out.put(n.substring(n.indexOf("/") + 1), image);
                    }
                }
            }
        }
        return out;
    }

    /**
     * A layer's piece that ends above the elbow or the knee (every NTM layer set's sleeve ends at its row 4) leaves
     * nothing of itself on the bent forearm or shin out of the upper half: the overlap shows the upper half's last
     * row alone. A piece that goes on over the joint (a legging) still fills the corner. (With that row alone the
     * overlap can only fill a corner; the test holds the overlap to it. The stand measures the same on the bots' poses.)
     */
    @Test
    void aBentLimbCarriesNoPieceOfASleeveThatEndsAboveIt() throws Exception {
        var set = HumanoidModel.createArmorMeshSet(new CubeDeformation(0.5F), new CubeDeformation(1F))
                .map(mesh -> BotModel.split(LayerDefinition.create(mesh, 64, 32).bakeRoot(), ModelPoseCaptureTest.PARTS));
        var outer = new java.util.TreeMap<String, java.awt.image.BufferedImage>();
        var inner = new java.util.TreeMap<String, java.awt.image.BufferedImage>();
        for (String ns : new String[] { "hbm", "minecraft" }) {
            outer.putAll(textures("assets/" + ns + "/textures/entity/equipment/humanoid/"));
            inner.putAll(textures("assets/" + ns + "/textures/entity/equipment/humanoid_leggings/"));
        }
        // NTM's layer sets and vanilla's both read (a folder not found would leave its sets out unseen)
        assertTrue(outer.containsKey("hbm/textures/entity/equipment/humanoid/steel.png"), outer.keySet().toString());
        assertTrue(outer.containsKey("minecraft/textures/entity/equipment/humanoid/iron.png"), outer.keySet().toString());
        assertTrue(outer.size() >= 20 && inner.size() >= 20, outer.size() + " outer, " + inner.size() + " inner");
        int legCorner = 0;
        StringBuilder table = new StringBuilder();
        for (boolean legs : new boolean[] { false, true }) {
            for (var t : (legs ? inner : outer).entrySet()) {
                var image = t.getValue();
                LayerSeams.Alpha alpha = (x, y) -> x >= 0 && y >= 0 && x < 64 && y < 32 && (image.getRGB(x, y) >>> 24) != 0;
                var slots = legs ? new ModelPart[] { set.legs() } : new ModelPart[] { set.chest(), set.feet() };
                int loose = 0, corner = 0;
                for (ModelPart root : slots) for (float deg : new float[] { -120, -90, -45, 45, 90, 120 }) {
                    for (String limb : new String[] { "right_arm", "left_arm", "right_leg", "left_leg" })
                        BotModel.lower(root.getChild(limb)).xRot = (float) Math.toRadians(deg);
                    var c = LayerSeams.measure(root, ModelPoseCaptureTest.PARTS, alpha);
                    loose = Math.max(loose, c.loose());
                    corner = Math.max(corner, c.corner());
                    for (String limb : new String[] { "right_arm", "left_arm", "right_leg", "left_leg" })
                        BotModel.lower(root.getChild(limb)).xRot = 0F;
                }
                table.append(String.format(java.util.Locale.ROOT, "%-50s loose %3d corner %3d%n", t.getKey(), loose, corner));
                if (legs && t.getKey().equals("hbm/textures/entity/equipment/humanoid_leggings/steel.png")) legCorner = corner;
                assertEquals(0, loose, t.getKey() + " loose texels on a bent limb\n" + table);
            }
        }
        System.out.print(table);
        // the steel legging goes on over the knee: its corner is filled
        assertTrue(legCorner > 0, "steel legging's corner\n" + table);
    }
}
