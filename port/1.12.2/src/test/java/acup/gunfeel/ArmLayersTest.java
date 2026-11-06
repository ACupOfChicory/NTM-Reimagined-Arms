package acup.gunfeel;

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
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.entity.layers.LayerArrow;
import net.minecraft.client.renderer.entity.layers.LayerBipedArmor;
import net.minecraft.client.renderer.entity.layers.LayerCape;
import net.minecraft.client.renderer.entity.layers.LayerCustomHead;
import net.minecraft.client.renderer.entity.layers.LayerDeadmau5Head;
import net.minecraft.client.renderer.entity.layers.LayerElytra;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;

// armArmor on 1.12.2 (ArmLayers): the player model posed as the arm box, the armour models following that pose, the
// cut planes, and the stand's reading of GL's feedback back into the arm's pixels
class ArmLayersTest {
    // the posed player model: the drawn arm where the arm box hangs (slim too: 1.12.2's slim model hangs it at y 2.5),
    // unturned; every other part far down; the pose taken back exactly
    @Test
    void thePlayerModelIsPosedAsTheArmBox() {
        for (boolean slim : new boolean[] {true, false}) {
            for (boolean right : new boolean[] {true, false}) {
                ModelPlayer model = new ModelPlayer(0F, slim);
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
                assertEquals(0, java.util.Arrays.compare(before.v, new ArmLayers.Pose(model).v));
            }
        }
    }

    // vanilla's armour model (LayerBipedArmor's ModelBiped, chestplate 1 pixel out) takes the posed pose after its own
    // setRotationAngles: its arm is the arm box's 1 pixel out on every side, the 26.2 stand's numbers for a slim arm
    // (x- 2, x+ 1, y 1, 1, z 1, 1), the rest far away
    @Test
    void anArmourModelFollowsThePosedArm() throws Exception {
        ModelPlayer parent = new ModelPlayer(0F, true);
        ArmLayers.pose(parent, true);
        ModelBiped chest = new ModelBiped(1F);
        // its own pose first, as ModelBiped.render does: swinging, sneaking, riding
        chest.isSneak = true;
        chest.isRiding = true;
        chest.swingProgress = 0.3F;
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
            ModelBox box = chest.bipedRightArm.cubeList.get(0);
            float g = 1F;
            float x0 = chest.bipedRightArm.rotationPointX + box.posX1 - g + 5F, x1 = chest.bipedRightArm.rotationPointX + box.posX2 + g + 5F;
            float y0 = chest.bipedRightArm.rotationPointY + box.posY1 - g - 2F, y1 = chest.bipedRightArm.rotationPointY + box.posY2 + g - 2F;
            // slim right arm box: x -2..1, y -2..10, z -2..2
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
            assertEquals(0, java.util.Arrays.compare(posedPose.v, new ArmLayers.Pose(parent).v));
            // after the layers the armour model has its own pose back: its setRotationAngles never sets the body's
            // rotation point, which would stay AWAY in third person (LayerBipedArmor's models are every player's)
            ArmLayers.unfollow();
            assertEquals(0, java.util.Arrays.compare(own.v, new ArmLayers.Pose(chest).v));
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

    // a pose of its own (ModelPlayer.setRotationAngles wants an entity)
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
        // layer 0, texture 0: a quad of the armour's arm (x -4..2, y -3..11, z -3)
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
        // layer 2: another box
        f.add((float) ArmLayers.PASS);
        f.add(2F);
        polygon(f, proj, m, viewport, true, new float[][] {{-1F, 4F, -1F}, {0F, 4F, -1F}, {0F, 6F, 1F}});
        float[] a = new float[f.size()];
        for (int k = 0; k < a.length; k++) a[k] = f.get(k);
        Matrix4f toArm = new Matrix4f(proj).mul(m).invert();
        @SuppressWarnings("rawtypes")
        List<LayerRenderer> layers = null;
        Map<String, ArmLayers.Box> boxes = ArmLayers.boxes(a, a.length, toArm, viewport, true, layers);
        assertEquals(2, boxes.size());
        ArmLayers.Box b = boxes.get("layer0 minecraft:textures/models/armor/leather_layer_1.png");
        assertEquals(2, b.polygons);
        assertEquals(7, b.vertices);
        near(new float[] {-4F, -3F, -3F}, b.min);
        near(new float[] {2F, 11F, 3F}, b.max);
        ArmLayers.Box c = boxes.get("layer2 -");
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

    // the layers drawn on the arm and those left out (the gun, the cape, stuck arrows, the head item, elytra, ears)
    @Test
    void layersOffTheArmAreLeftOut() {
        assertTrue(ArmLayers.skipped(new LayerHeldItem(null)));
        assertTrue(ArmLayers.skipped(new LayerArrow(null)));
        assertTrue(ArmLayers.skipped(new LayerCape(null)));
        assertTrue(ArmLayers.skipped(new LayerCustomHead(new ModelRenderer(new ModelBiped()))));
        assertTrue(ArmLayers.skipped(new LayerElytra(null)));
        assertTrue(ArmLayers.skipped(new LayerDeadmau5Head(null)));
        assertTrue(ArmLayers.skipped(new net.minecraft.client.renderer.entity.layers.LayerEntityOnShoulder(null)));
        assertFalse(ArmLayers.skipped(new LayerBipedArmor(null)));
        assertFalse(ArmLayers.skipped(new com.hbm.render.entity.layers.LayerArmorMod(null)));
    }

    // the sleeves: no chestplate or vanilla's, as Skin Customization has them; no player (before a world): shown
    @Test
    void sleevesWithoutASuit() {
        assertFalse(ArmLayers.suit(null));
        assertFalse(ArmLayers.suit(net.minecraft.item.ItemStack.EMPTY));
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
