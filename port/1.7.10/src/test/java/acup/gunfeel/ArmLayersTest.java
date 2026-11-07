package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;

// armArmor on 1.7.10 (ArmLayers; port: 1.12.2's ArmLayersTest, cdb2911): the player model posed as the arm box, the
// armour models following that pose, the cut planes, and the stand's reading of GL's feedback back into the arm's pixels
class ArmLayersTest {
    // the posed player model (1.7.10's RenderPlayer.modelBipedMain is a ModelBiped): the drawn arm where the arm box
    // hangs, unturned; every other part far down; the pose taken back exactly
    @Test
    void thePlayerModelIsPosedAsTheArmBox() {
        for (float grow : new float[] {0F, 0.5F}) {
            for (boolean right : new boolean[] {true, false}) {
                ModelBiped model = new ModelBiped(grow);
                scramble(model, 0.7F);
                ArmLayers.Pose before = new ArmLayers.Pose(model);
                ArmLayers.pose(model, right);
                ModelRenderer arm = right ? model.bipedRightArm : model.bipedLeftArm;
                assertEquals(right ? -5F : 5F, arm.rotationPointX);
                assertEquals(2F, arm.rotationPointY);
                assertEquals(0F, arm.rotationPointZ);
                assertEquals(0F, arm.rotateAngleX);
                assertEquals(0F, arm.rotateAngleY);
                assertEquals(0F, arm.rotateAngleZ);
                for (ModelRenderer p : ArmLayers.parts(model))
                    if (p != arm) assertTrue(p.rotationPointY >= ArmLayers.AWAY - 20F, "moved away");
                before.load(model);
                assertArrayEquals(before.v, new ArmLayers.Pose(model).v);
            }
        }
    }

    // vanilla's armour model (RenderPlayer's modelArmorChestplate, 1 pixel out) takes the posed pose after its own
    // setRotationAngles: its arm is the arm box's 1 pixel out on every side (classic: x- 1, x+ 1, y 1, 1, z 1, 1; a
    // slim arm box, x -2..1, makes x- 2: 26.2's slim numbers), the rest far away
    @Test
    void anArmourModelFollowsThePosedArm() throws Exception {
        ModelBiped parent = new ModelBiped(0F);
        ArmLayers.pose(parent, true);
        ModelBiped chest = new ModelBiped(1F);
        // its own pose first, as ModelBiped.render does: swinging, sneaking, riding
        chest.isSneak = true;
        chest.isRiding = true;
        chest.onGround = 0.3F;
        posing(parent);
        try {
            chest.setRotationAngles(1.3F, 0.8F, 40F, 15F, 5F, 0.0625F, null);
            ArmLayers.Pose own = new ArmLayers.Pose(chest);
            ArmLayers.follow(chest);
            ArmLayers.follow(chest);
            ModelRenderer[] a = ArmLayers.parts(parent), b = ArmLayers.parts(chest);
            for (int i = 0; i < a.length; i++) {
                assertEquals(a[i].rotationPointX, b[i].rotationPointX);
                assertEquals(a[i].rotationPointY, b[i].rotationPointY);
                assertEquals(a[i].rotationPointZ, b[i].rotationPointZ);
                assertEquals(a[i].rotateAngleX, b[i].rotateAngleX);
                assertEquals(a[i].rotateAngleY, b[i].rotateAngleY);
                assertEquals(a[i].rotateAngleZ, b[i].rotateAngleZ);
            }
            // the armour arm's box in the arm part's pixels (the part's rotation point at -5, 2, 0)
            // (ModelBox keeps its size as given, the armour's 1 pixel out goes to its vertices)
            ModelBox box = (ModelBox) chest.bipedRightArm.cubeList.get(0);
            float g = 1F;
            float x0 = chest.bipedRightArm.rotationPointX + box.posX1 - g + 5F, x1 = chest.bipedRightArm.rotationPointX + box.posX2 + g + 5F;
            float y0 = chest.bipedRightArm.rotationPointY + box.posY1 - g - 2F, y1 = chest.bipedRightArm.rotationPointY + box.posY2 + g - 2F;
            // classic right arm box: x -3..1, y -2..10, z -2..2; slim: x -2..1
            assertEquals(1F, -3F - x0);
            assertEquals(2F, -2F - x0);
            assertEquals(1F, x1 - 1F);
            assertEquals(1F, -2F - y0);
            assertEquals(1F, y1 - 10F);
            assertEquals(1F, -2F - (box.posZ1 - g));
            assertEquals(1F, box.posZ2 + g - 2F);
            // the player model drawn again by a layer gets its posed pose back
            ArmLayers.Pose posedPose = new ArmLayers.Pose(parent);
            scramble(parent, 0.5F);
            ArmLayers.follow(parent);
            assertArrayEquals(posedPose.v, new ArmLayers.Pose(parent).v);
            // after the layers the armour model has its own pose back: its setRotationAngles never sets the body's
            // rotation point, which would stay AWAY in third person (RenderPlayer's armour models are every player's)
            ArmLayers.unfollow();
            assertArrayEquals(own.v, new ArmLayers.Pose(chest).v);
            assertTrue(chest.bipedBody.rotationPointY < 100F);
        } finally {
            posing(null);
        }
        // not posing: nothing follows
        ModelBiped other = new ModelBiped(0.5F);
        other.setRotationAngles(1.3F, 0.8F, 40F, 15F, 5F, 0.0625F, null);
        float x = other.bipedRightArm.rotateAngleX;
        ArmLayers.follow(other);
        assertEquals(x, other.bipedRightArm.rotateAngleX);
    }

    // a pose of its own
    private static void scramble(ModelBiped model, float k) {
        int i = 1;
        for (ModelRenderer p : ArmLayers.parts(model)) {
            p.rotationPointX += k * i;
            p.rotationPointY -= k * i * 0.5F;
            p.rotationPointZ += k;
            p.rotateAngleX = k * i;
            p.rotateAngleY = -k;
            p.rotateAngleZ = k * 0.3F * i++;
        }
    }

    private static void posing(ModelBiped parent) throws Exception {
        Field posed = ArmLayers.class.getDeclaredField("posed");
        Field pose = ArmLayers.class.getDeclaredField("posedPose");
        posed.setAccessible(true);
        pose.setAccessible(true);
        posed.set(null, parent);
        pose.set(null, parent == null ? null : new ArmLayers.Pose(parent));
    }

    // the cut keeps the hand end (y at or past the cut in the arm's frame), the far plane what is within KEEP
    @Test
    void thePlanesKeepTheArmToItsCut() {
        float cut = 6 / 16F;
        assertTrue(side(ArmLayers.cutPlane(cut), 0F, 0.5F, 0F) > 0);
        assertTrue(side(ArmLayers.cutPlane(cut), 0F, 0.2F, 0F) < 0);
        assertEquals(0.0, side(ArmLayers.cutPlane(cut), 3F, cut, -2F), 1e-7);
        assertTrue(side(ArmLayers.keepPlane(), 0F, 0.6F, 0F) > 0);
        // a part moved away (AWAY pixels = AWAY / 16 blocks) is past it
        assertTrue(side(ArmLayers.keepPlane(), 0F, ArmLayers.AWAY / 16F, 0F) < 0);
        assertTrue(ArmLayers.AWAY / 16F > 4096F, "past the hand's far plane at any view distance");
    }

    private static double side(double[] eq, float x, float y, float z) {
        return eq[0] * x + eq[1] * y + eq[2] * z + eq[3];
    }

    // feedback values made from known points (in the arm part's pixels, through the arm's frame, a hand projection
    // and a viewport) come back as those points, each layer and texture in its own box
    @Test
    void feedbackComesBackInTheArmsPixels() {
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70), 1280F / 720F, 0.05F, 1024F);
        // the arm's frame: turned, moved before the eye, stretched as a thick arm
        Matrix4f m = new Matrix4f().translate(0.3F, -0.4F, -0.9F).rotateXYZ(1.1F, -0.4F, 0.25F).scale(1.3F, 0.9F, 1.3F);
        int[] viewport = {0, 0, 1280, 720};
        List<Float> f = new ArrayList<>();
        // draw 0 (the armour), texture 0: a quad of the armour's arm (x -4..2, y -3..11, z -3)
        f.add((float) ArmLayers.PASS);
        f.add(0F);
        ArmLayers.Feedback.TEXTURES.clear();
        ArmLayers.Feedback.TEXTURES.add("minecraft:textures/models/armor/leather_layer_1.png");
        f.add((float) ArmLayers.PASS);
        f.add(-1F);
        polygon(f, proj, m, viewport, true, new float[][] {{-4F, -3F, -3F}, {2F, -3F, -3F}, {2F, 11F, -3F}, {-4F, 11F, -3F}});
        // a point and a line are passed over
        f.add((float) ArmLayers.POINT);
        f.add(1F);
        f.add(2F);
        f.add(0.5F);
        f.add((float) ArmLayers.LINE);
        for (int k = 0; k < 6; k++) f.add(1F);
        polygon(f, proj, m, viewport, true, new float[][] {{-4F, 0F, 3F}, {2F, 0F, 3F}, {2F, 5F, 3F}});
        // draw 2 (the glint): another box
        f.add((float) ArmLayers.PASS);
        f.add(2F);
        polygon(f, proj, m, viewport, true, new float[][] {{-1F, 4F, -1F}, {0F, 4F, -1F}, {0F, 6F, 1F}});
        float[] a = new float[f.size()];
        for (int k = 0; k < a.length; k++) a[k] = f.get(k);
        Matrix4f toArm = new Matrix4f(proj).mul(m).invert();
        Map<String, ArmLayers.Box> boxes = ArmLayers.boxes(a, a.length, toArm, viewport, true, "ItemArmor");
        assertEquals(2, boxes.size());
        ArmLayers.Box b = boxes.get("ItemArmor/armor minecraft:textures/models/armor/leather_layer_1.png");
        assertEquals(2, b.polygons);
        assertEquals(7, b.vertices);
        near(new float[] {-4F, -3F, -3F}, b.min);
        near(new float[] {2F, 11F, 3F}, b.max);
        ArmLayers.Box c = boxes.get("ItemArmor/glint -");
        near(new float[] {-1F, 4F, -1F}, c.min);
        near(new float[] {0F, 6F, 1F}, c.max);
        // the left arm's part hangs at x 5
        Vector4f l = ArmLayers.armPixels(a[6], a[7], a[8], toArm, viewport, false, new Vector4f());
        assertEquals(-4F - 10F, l.x, 2e-3F);
    }

    private static void polygon(List<Float> f, Matrix4f proj, Matrix4f m, int[] viewport, boolean right, float[][] pixels) {
        f.add((float) ArmLayers.POLYGON);
        f.add((float) pixels.length);
        Matrix4f pm = new Matrix4f(proj).mul(m);
        for (float[] p : pixels) {
            // the arm part's pixels -> the arm's frame (blocks) -> clip -> window
            Vector4f v = new Vector4f((p[0] - (right ? 5F : -5F)) / 16F, (p[1] + 2F) / 16F, p[2] / 16F, 1F);
            pm.transform(v);
            v.div(v.w);
            f.add(viewport[0] + (v.x + 1F) / 2F * viewport[2]);
            f.add(viewport[1] + (v.y + 1F) / 2F * viewport[3]);
            f.add((v.z + 1F) / 2F);
        }
    }

    private static void near(float[] want, float[] got) {
        for (int i = 0; i < 3; i++) assertEquals(want[i], got[i], 2e-3F, "axis " + i);
    }

    // the sleeves (Et Futurum's skins): shown without an NTM suit; no player (before a world): shown
    @Test
    void sleevesWithoutASuit() {
        assertFalse(ArmLayers.suit(null));
        assertTrue(ArmLayers.sleeveShown(null, true));
        assertTrue(ArmLayers.sleeveShown(null, false));
    }

    // a world left for the main menu: the player is held weakly and let go (26.2's ArmLayersLeaveTest)
    @Test
    void leavingLetsThePlayerGo() throws Exception {
        Field owner = ArmLayers.class.getDeclaredField("owner");
        Field prepared = ArmLayers.class.getDeclaredField("prepared");
        owner.setAccessible(true);
        prepared.setAccessible(true);
        assertEquals(WeakReference.class, owner.getType());
        Object player = new Object();
        owner.set(null, new WeakReference<>(player));
        prepared.setBoolean(null, true);
        ArmLayers.leave();
        assertNull(((WeakReference<?>) owner.get(null)).get());
        assertFalse(prepared.getBoolean(null));
    }
}
