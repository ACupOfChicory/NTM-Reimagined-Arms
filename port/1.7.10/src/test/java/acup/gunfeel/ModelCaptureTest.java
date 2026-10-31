package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import acup.gunfeel.phys.Vec3;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelCreeper;
import net.minecraft.client.model.ModelDragon;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.ModelZombie;

// The port's model capture on real 1.7.10 models, without a window: a software matrix stack does what
// RendererLivingEntity.doRender and the model's render do in GL (doRender: translate to the origin, rotateCorpse's
// 180 - yaw about Y, scale -1 -1 1, up by 24/16 + 1/128; ModelRenderer.render: offset, rotation point, Z Y X turns,
// then the children). The expected numbers are worked out by hand from the models' source, not from the capture.
class ModelCaptureTest {
    private static final float SCALE = 0.0625F;

    // the GL stack of one draw
    private static final class Draw {
        float[] m;
        final ModelCapture capture;
        Draw(ModelBase model, double x, double y, double z, float yaw) {
            float[] view = ModelCapture.identity();
            capture = new ModelCapture(model, view, x, y, z, yaw);
            m = view;
            m = Mat4.mul(m, ModelCapture.translation((float) x, (float) y, (float) z));
            m = Mat4.mul(m, ModelCapture.rotation(180F - yaw, 0, 1, 0));
            m = Mat4.mul(m, ModelCapture.scaling(-1, -1, 1));
            m = Mat4.mul(m, ModelCapture.translation(0, -24F * SCALE - 0.0078125F, 0));
        }
        void render(ModelRenderer r) {
            if (r.isHidden || !r.showModel) return;
            capture.part(r, SCALE, false, m, 0);
            float[] saved = m;
            m = Mat4.mul(m, ModelCapture.local(r, SCALE, false));
            if (r.childModels != null) for (ModelRenderer c : r.childModels) render(c);
            m = saved;
        }
    }

    // posed: ModelZombie.setRotationAngles, arms held forward (and ModelBiped's idle sway); not posed: every angle 0
    private static List<ModelBox> zombie(float yaw) {
        return zombie(yaw, true);
    }

    private static List<ModelBox> zombie(float yaw, boolean posed) {
        ModelZombie model = new ModelZombie();
        if (posed) model.setRotationAngles(0, 0, 0, 0, 0, SCALE, null);
        Draw d = new Draw(model, 0, 0, 0, yaw);
        // ModelBiped.render, an adult
        for (ModelRenderer r : new ModelRenderer[] {model.bipedHead, model.bipedBody, model.bipedRightArm, model.bipedLeftArm,
                model.bipedRightLeg, model.bipedLeftLeg, model.bipedHeadwear}) d.render(r);
        assertTrue(d.capture.complete());
        return d.capture.boxes();
    }

    private static ModelBox only(List<ModelBox> boxes, String part) {
        List<ModelBox> found = boxes.stream().filter(b -> b.part().equals(part)).toList();
        assertEquals(1, found.size(), part + " in " + boxes.stream().map(ModelBox::part).toList());
        return found.get(0);
    }

    private static double[] bounds(ModelBox b) {
        double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
        for (int i = 0; i < 8; i++) {
            Vec3 p = ModelContours.point(b, i & 1, i >> 1 & 1, i >> 2 & 1);
            double[] c = {p.x, p.y, p.z};
            for (int k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], c[k]); hi[k] = Math.max(hi[k], c[k]); }
        }
        return new double[] {lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]};
    }

    private static void near(double[] expected, double[] actual) {
        for (int i = 0; i < 6; i++) assertEquals(expected[i], actual[i], 1e-5, "bound " + i);
    }

    @Test void zombieAtRestStandsWhereItsModelIs() {
        List<ModelBox> boxes = zombie(0, false);
        assertEquals(7, boxes.size());
        double base = 1.5078125;
        // the head: 8 px cube on the neck, from 24 px up (ModelBiped: addBox(-4, -8, -4, 8, 8, 8), point 0 0 0)
        near(new double[] {-.25, base, -.25, .25, base + .5, .25}, bounds(only(boxes, "root/head")));
        // the hat: the same cube inflated by half a pixel
        near(new double[] {-.28125, base - .03125, -.28125, .28125, base + .53125, .28125}, bounds(only(boxes, "root/head/hat")));
        near(new double[] {-.25, base - .75, -.125, .25, base, .125}, bounds(only(boxes, "root/body")));
        // facing +z at yaw 0: the right arm is at -x (point -5 2 0, box -3..1 across, so -8..-4 px)
        double[] right = bounds(only(boxes, "root/right_arm")), left = bounds(only(boxes, "root/left_arm"));
        assertEquals(-.5, right[0], 1e-5);
        assertEquals(-.25, right[3], 1e-5);
        assertEquals(.25, left[0], 1e-5);
        assertEquals(.5, left[3], 1e-5);
        // the legs' points are 1.9 px off the middle (box -2..2), so they overlap by 0.2 px; the feet 1/128 up
        near(new double[] {-.24375, .0078125, -.125, .00625, .7578125, .125}, bounds(only(boxes, "root/right_leg")));
        near(new double[] {-.00625, .0078125, -.125, .24375, .7578125, .125}, bounds(only(boxes, "root/left_leg")));
    }

    @Test void thePosedZombieHoldsItsArmsForward() {
        // ModelZombie: arms at -pi/2 about X, so the 12 px arm reaches out in front (+z at yaw 0), at shoulder height
        double[] arm = bounds(only(zombie(0), "root/right_arm"));
        assertTrue(arm[5] > .6, java.util.Arrays.toString(arm));
        assertTrue(arm[4] < 1.6 && arm[1] > 1.1, java.util.Arrays.toString(arm));
    }

    @Test void theBodyYawTurnsTheModelAboutItsOrigin() {
        Vec3 at0 = ModelContours.point(only(zombie(0), "root/right_arm"), .5, .5, .5);
        Vec3 at90 = ModelContours.point(only(zombie(90), "root/right_arm"), .5, .5, .5);
        // yaw 90 faces -x: the right arm goes from -x to -z (Ry(90) = Ry(-90) Ry(180))
        assertEquals(-at0.z, at90.x, 1e-5);
        assertEquals(at0.y, at90.y, 1e-5);
        assertEquals(at0.x, at90.z, 1e-5);
    }

    @Test void the26_2HeadDatasetFindsTheZombiesHead() {
        for (float yaw : new float[] {0, 90, -37.5F}) {
            List<ModelBox> boxes = zombie(yaw);
            ModelHeads.Resolution head = ModelHeads.resolve("minecraft:zombie", boxes, yaw);
            assertEquals("dataset", head.source(), head.reason());
            assertEquals(0, head.missing());
            Vec3 face = ModelContours.point(only(boxes, "root/head"), .5, .5, .5);
            Vec3 chest = ModelContours.point(only(boxes, "root/body"), .5, .7, .5);
            assertTrue(HeadZone.contains(head.zones(), face));
            assertFalse(HeadZone.contains(head.zones(), chest));
        }
    }

    @Test void aRayAtTheFaceMeetsTheHatThenTheHead() {
        List<ModelBox> boxes = zombie(0, false);
        // from 2 blocks in front (+z) at eye height, straight back
        double nearestHat = only(boxes, "root/head/hat").clip(0, 1.75, 2, 0, 1.75, -2);
        double nearestHead = only(boxes, "root/head").clip(0, 1.75, 2, 0, 1.75, -2);
        assertEquals((2 - .28125) / 4, nearestHat, 1e-6);
        assertEquals((2 - .25) / 4, nearestHead, 1e-6);
        // beside the arm (which reaches x 0.5) and over the hat (up to y 2.039) the model has nothing
        for (ModelBox b : boxes) {
            assertEquals(-1, b.clip(.55, 1.2, 2, .55, 1.2, -2), b.part());
            assertEquals(-1, b.clip(0, 2.05, 2, 0, 2.05, -2), b.part());
        }
        // the legs are hit at the middle (they overlap there), and the right leg's front face is at z 0.125
        assertEquals((2 - .125) / 4, only(boxes, "root/right_leg").clip(0, .3, 2, 0, .3, -2), 1e-6);
    }

    @Test void creeperLegsAreNamedAs26_2Does() {
        ModelCreeper model = new ModelCreeper();
        model.setRotationAngles(0, 0, 0, 0, 0, SCALE, null);
        Draw d = new Draw(model, 0, 0, 0, 0);
        for (ModelRenderer r : new ModelRenderer[] {model.head, model.body, model.leg1, model.leg2, model.leg3, model.leg4}) d.render(r);
        List<ModelBox> boxes = d.capture.boxes();
        // leg3: point (-2, 18, -4): right (-x) and in front (model -z is the world's +z at yaw 0)
        Vec3 front = ModelContours.point(only(boxes, "root/right_front_leg"), .5, .5, .5);
        assertTrue(front.x < 0 && front.z > 0, front.toString());
        ModelHeads.Resolution head = ModelHeads.resolve("minecraft:creeper", boxes, 0);
        assertEquals("dataset", head.source(), head.reason());
        assertTrue(HeadZone.contains(head.zones(), ModelContours.point(only(boxes, "root/head"), .5, .5, .5)));
    }

    @Test void partsOutsideTheModelAndHiddenPartsAreNotTaken() {
        ModelZombie model = new ModelZombie();
        Draw d = new Draw(model, 0, 0, 0, 0);
        ModelBiped armour = new ModelBiped(1F);
        d.render(armour.bipedHead);
        model.bipedHead.showModel = false;
        d.render(model.bipedHead);
        model.bipedBody.isHidden = true;
        d.render(model.bipedBody);
        assertFalse(d.capture.complete());
        assertTrue(d.capture.boxes().isEmpty());
    }

    @Test void aSecondDrawInTheSamePoseIsNotNewGeometry() {
        ModelZombie model = new ModelZombie();
        Draw d = new Draw(model, 0, 0, 0, 0);
        d.render(model.bipedHead);
        d.render(model.bipedHead);
        assertEquals(1, d.capture.boxes().size());
        model.bipedHead.rotateAngleY = .5F;
        d.render(model.bipedHead);
        assertEquals(List.of("root/head", "root/head#1"), d.capture.boxes().stream().map(ModelBox::part).toList());
    }

    @Test void theCameraAndOriginDropOut() {
        // the same zombie seen from a turned, moved camera at another camera-relative origin
        List<ModelBox> plain = zombie(20);
        ModelZombie model = new ModelZombie();
        model.setRotationAngles(0, 0, 0, 0, 0, SCALE, null);
        float[] view = Mat4.mul(ModelCapture.rotation(33F, 1, 0, 0), Mat4.mul(ModelCapture.rotation(-71F, 0, 1, 0), ModelCapture.translation(.3F, -1.62F, .1F)));
        ModelCapture capture = new ModelCapture(model, view, 4.5, -1.25, -7, 20);
        float[] m = Mat4.mul(view, ModelCapture.translation(4.5F, -1.25F, -7F));
        m = Mat4.mul(m, ModelCapture.rotation(160F, 0, 1, 0));
        m = Mat4.mul(m, ModelCapture.scaling(-1, -1, 1));
        m = Mat4.mul(m, ModelCapture.translation(0, -24F * SCALE - 0.0078125F, 0));
        for (ModelRenderer r : new ModelRenderer[] {model.bipedHead, model.bipedBody, model.bipedRightArm, model.bipedLeftArm,
                model.bipedRightLeg, model.bipedLeftLeg, model.bipedHeadwear}) capture.part(r, SCALE, false, m, 0);
        List<ModelBox> seen = capture.boxes();
        assertEquals(plain.size(), seen.size());
        for (int i = 0; i < plain.size(); i++) {
            double[] a = plain.get(i).numbers(), b = seen.get(i).numbers();
            for (int k = 0; k < a.length; k++) assertEquals(a[k], b[k], 2e-5, plain.get(i).part() + " " + k);
        }
    }

    @Test void dragonDrawsAreNamedAsThe26_2Dataset() throws Exception {
        ModelDragon model = new ModelDragon(0F);
        Map<ModelRenderer, String> names = ModelNames.of(model);
        String spine = null, wing = null, foot = null;
        for (var e : names.entrySet()) {
            if (e.getValue().equals("root/spine")) spine = e.getValue();
            if (e.getValue().endsWith("*_wing")) wing = e.getValue();
            if (e.getValue().endsWith("*_front_foot")) foot = e.getValue();
        }
        assertNotNull(spine);
        assertEquals("root/neck0", ModelNames.draw(model, spine, 0));
        assertEquals("root/neck4", ModelNames.draw(model, spine, 4));
        assertEquals("root/tail0", ModelNames.draw(model, spine, 5));
        assertEquals("root/tail11", ModelNames.draw(model, spine, 16));
        assertEquals("root/body/right_wing", ModelNames.draw(model, wing, 0));
        assertEquals("root/body/left_wing", ModelNames.draw(model, wing, 1));
        // the 26.2 names of the dragon's parts, from its head dataset: the long foot paths are shortened the same way
        var json = new com.google.gson.JsonParser().parse(new java.io.InputStreamReader(
                ModelCaptureTest.class.getResourceAsStream("/ntmgunfeel-heads.json"), java.nio.charset.StandardCharsets.UTF_8));
        List<String> dataset = new ArrayList<>();
        for (var p : json.getAsJsonObject().getAsJsonObject("profiles").getAsJsonObject("minecraft:ender_dragon").getAsJsonArray("parts"))
            dataset.add(p.getAsJsonObject().get("name").getAsString());
        assertTrue(dataset.contains(ModelNames.draw(model, foot, 0)), ModelNames.draw(model, foot, 0));
        assertTrue(dataset.contains(ModelNames.draw(model, foot, 1)), ModelNames.draw(model, foot, 1));
        for (int i = 0; i < 17; i++) assertTrue(dataset.contains(ModelNames.draw(model, spine, i)));
    }

    @Test void unknownModelsKeepFieldNamesAndIndices() {
        ModelBase model = new ModelBase() {
            final ModelRenderer trunk = new ModelRenderer(this);
            final ModelRenderer[] arms = {new ModelRenderer(this), new ModelRenderer(this)};
            {
                ModelRenderer leaf = new ModelRenderer(this);
                trunk.addChild(leaf);
                new ModelRenderer(this); // in boxList only
            }
        };
        var names = new ArrayList<>(ModelNames.of(model).values());
        names.sort(null);
        // boxList: trunk, arms0, arms1, leaf, the loose part (4)
        assertEquals(List.of("root/arms0", "root/arms1", "root/part4", "root/trunk", "root/trunk/0"), names);
    }

    @Test void posePayloadRoundTrip() {
        List<ModelBox> boxes = zombie(12);
        var sent = new ModelPosePayload(42, new java.util.UUID(7, -9), 1.5, 64.25, -3.75, 12F, boxes);
        ByteBuf buf = Unpooled.buffer();
        sent.write(buf);
        assertTrue(buf.readableBytes() < 32767);
        var got = ModelPosePayload.read(buf);
        assertEquals(0, buf.readableBytes());
        assertEquals(sent.entity(), got.entity());
        assertEquals(sent.uuid(), got.uuid());
        assertEquals(sent.y(), got.y());
        assertEquals(boxes.size(), got.boxes().size());
        for (int i = 0; i < boxes.size(); i++) {
            assertEquals(boxes.get(i).part(), got.boxes().get(i).part());
            double[] a = boxes.get(i).numbers(), b = got.boxes().get(i).numbers();
            for (int k = 0; k < a.length; k++) assertEquals((float) a[k], (float) b[k]);
        }
        var mesh = new ModelMeshPayload(5, new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0});
        buf = Unpooled.buffer();
        mesh.write(buf);
        var back = ModelMeshPayload.read(buf);
        assertEquals(5, back.id());
        assertArrayEquals(mesh.triangles(), back.triangles());
    }

    @Test void maxPartNameAndHugeCountsAreRefused() {
        ByteBuf buf = Unpooled.buffer();
        ModelPosePayload.varInt(buf, 1);
        buf.writeLong(0).writeLong(0).writeDouble(0).writeDouble(0).writeDouble(0).writeFloat(0);
        ModelPosePayload.varInt(buf, 129);
        assertThrows(IllegalArgumentException.class, () -> ModelPosePayload.read(buf));
        assertThrows(IllegalArgumentException.class, () -> new ModelPosePayload(1, new java.util.UUID(0, 0), 0, 0, 0, 0,
                List.of(new ModelBox("x".repeat(65), 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1))).write(Unpooled.buffer()));
    }
}
