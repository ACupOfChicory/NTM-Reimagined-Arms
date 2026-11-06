package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

// port: 26.2's PistolHoldTest (03ee2bd) without the tests on NTM's pistol models (hipPictureIsAsMeasured,
// aimedPictureIsAsMeasured: 26.2's .hbm_mesh, not on this test classpath; the numbers they check are 26.2's and the
// same here), without theTurnsSwayMovesTheHipPlace (it builds 26.2's PoseStack; here the move is read from GL, see
// theMovedFrameIsTheModelviewPastTheHalfTurn), and with the eye's frame as the port has it (the modelview is the
// eye's, turned half round).
class PistolHoldTest {
    @Test
    void curveStartsAndEndsWhereNtmDoes() {
        assertEquals(0F, PistolHold.curve(0F), 1e-6F);
        assertEquals(1F, PistolHold.curve(1F), 1e-6F);
        assertEquals(0F, PistolHold.curve(-0.5F), 1e-6F);
        assertEquals(1F, PistolHold.curve(1.5F), 1e-6F);
    }

    @Test
    void curveNeverGoesBack() {
        float last = 0F;
        for (int i = 1; i <= 200; i++) {
            float v = PistolHold.curve(i / 200F);
            assertTrue(v >= last, "at " + i);
            last = v;
        }
    }

    // the measured pistols aim in and out at the same times: the curve is the same both ways
    @Test
    void curveIsTheSameInAndOut() {
        for (int i = 0; i <= 100; i++) assertEquals(1F - PistolHold.curve(i / 100F), PistolHold.curve(1F - i / 100F), 1e-5F);
    }

    // NTM's progress goes linearly over 0.2 s, a half tick after the key: the curve's 10 / 50 / 90 % come between the
    // two measured pistols' (0.05 / 0.10 / 0.18 s and 0.10 / 0.18 / 0.26 s)
    @Test
    void curveAimsAsTheMeasuredPistols() {
        float[] level = {0.1F, 0.5F, 0.9F}, fast = {0.05F, 0.10F, 0.18F}, slow = {0.10F, 0.18F, 0.26F};
        for (int k = 0; k < 3; k++) {
            float p = 0F;
            while (PistolHold.curve(p) < level[k]) p += 1e-4F;
            float t = 0.025F + 0.2F * p;
            assertTrue(t >= fast[k] - 0.01F && t <= slow[k] + 0.01F, level[k] + " at " + t);
        }
    }

    @Test
    void hipSlidersKeepTheMeasuredPositionAtOneAndMoveEachEyeAxis() {
        Matrix4f pose = new Matrix4f().translation(0F, 0F, 0.875F);
        Vector3f[] ends = {new Vector3f(0F, 0F, -0.5F), new Vector3f(0F, 0F, 2.5F), new Vector3f(0.05F, -0.1F, 1F)};
        float aX = 0F, aY = -0.4F, aZ = 0.5F;
        Vector3f old = new Vector3f(), same = new Vector3f();
        assertTrue(PistolHold.hip(pose, new Matrix4f(), ends, aX, aY, aZ, old));
        assertTrue(PistolHold.hip(pose, new Matrix4f(), ends, aX, aY, aZ, 1F, 1F, 1F, same));
        assertEquals(old.x, same.x, 0F);
        assertEquals(old.y, same.y, 0F);
        assertEquals(old.z, same.z, 0F);

        Vector3f x = new Vector3f(), y = new Vector3f(), z = new Vector3f();
        assertTrue(PistolHold.hip(pose, new Matrix4f(), ends, aX, aY, aZ, 0F, 1F, 1F, x));
        assertTrue(PistolHold.hip(pose, new Matrix4f(), ends, aX, aY, aZ, 1F, 0F, 1F, y));
        assertTrue(PistolHold.hip(pose, new Matrix4f(), ends, aX, aY, aZ, 1F, 1F, 2F, z));
        Vector3f mx = pose.transformPosition(new Vector3f(x).add(ends[2]));
        Vector3f my = pose.transformPosition(new Vector3f(y).add(ends[2]));
        Vector3f mz = pose.transformPosition(new Vector3f(z).add(ends[2]));
        Vector3f m0 = pose.transformPosition(new Vector3f(old).add(ends[2]));
        assertEquals(0F, mx.x, 1e-5F);
        assertEquals(m0.y, mx.y, 1e-5F);
        assertEquals(m0.z, mx.z, 1e-5F);
        assertEquals(0F, my.y, 1e-5F);
        assertEquals(m0.x, my.x, 1e-5F);
        assertEquals(m0.z, my.z, 1e-5F);
        assertEquals(m0.z * 2F, mz.z, 1e-5F);
    }

    @Test
    void hipSliderOffsetsTurnWithTheCamera() {
        Matrix4f ntm = new Matrix4f().translation(0F, 0F, 0.875F);
        Vector3f[] ends = {new Vector3f(0F, 0F, -0.5F), new Vector3f(0F, 0F, 2.5F), new Vector3f(0.05F, -0.1F, 1F)};
        float aX = 0F, aY = -0.4F, aZ = 0.5F;
        Vector3f still = new Vector3f(), turned = new Vector3f();
        assertTrue(PistolHold.hip(ntm, new Matrix4f(), ends, aX, aY, aZ, 0.5F, 1.5F, 1.25F, still));
        Matrix4f view = new Matrix4f().rotateX(0.52F).rotateY(2.36F);
        Matrix4f inView = new Matrix4f(view).invert();
        Matrix4f moved = new Matrix4f(inView).mul(new Matrix4f().translation(0.05F, 0F, 0F).rotateY(0.1F)).mul(view);
        assertTrue(PistolHold.hip(view, new Matrix4f(moved).mul(inView).mul(ntm), moved,
                ends, aX, aY, aZ, 0.5F, 1.5F, 1.25F, turned));
        assertEquals(still.x, turned.x, 1e-4F);
        assertEquals(still.y, turned.y, 1e-4F);
        assertEquals(still.z, turned.z, 1e-4F);
    }

    // the defaults are cached per gun: going from a gun shipped with its own hipZ to one without it takes the new one's
    @Test
    void hipDefaultsFollowTheGunInHand() {
        float was = Config.hipZ;
        try {
            Config.hipZ = 1F;
            assertEquals(1F, PistolHold.hipScales("hbm:gun_laser_pistol")[2], 1e-6F);
            Config.hipZ = 0.75F;
            assertEquals(1F, PistolHold.hipScales("hbm:gun_star_f")[2], 1e-6F);
            Config.hipZ = 1.5F;
            assertEquals(2F, PistolHold.hipScales("hbm:gun_star_f")[2], 1e-6F);
            assertEquals(1.5F, PistolHold.hipScales("hbm:gun_laser_pistol")[2], 1e-6F);
        } finally {
            Config.hipZ = was;
        }
    }

    @Test
    void hipDefaultsComeFromShippedGunSettingsOrBuiltinValues() {
        assertEquals(0.75F, PistolHold.defaultHip("hbm:gun_heavy_revolver", "hipZ"), 0F);
        assertEquals(0.6F, PistolHold.defaultHip("hbm:gun_star_f", "hipX"), 0F);
        assertEquals(0.75F, PistolHold.defaultHip("hbm:gun_star_f", "hipZ"), 0F);
        // a value shipped for the gun wins over the built-in one
        assertEquals(0.74F, PistolHold.defaultHip("hbm:gun_greasegun", "hipX"), 0F);
        assertEquals(1F, PistolHold.hipScale(0F, 0F), 0F);
        assertEquals(1.25F, PistolHold.hipScale(0.25F, 0F), 0F);
    }

    // the hip place is found before WalkSprint's move and the move stays on top: with a shift and a turn around the
    // gun's middle (PIVOT) the gun's middle from the hip goes by the shift and turns around PIVOT, as when aimed
    @Test
    void walkSprintMovesTheHipPlace() {
        Matrix4f ntm = new Matrix4f().translation(0F, 0F, 0.875F);
        Vector3f[] ends = {new Vector3f(0F, 0F, -0.5F), new Vector3f(0F, 0F, 2.5F), new Vector3f(0.05F, -0.1F, 1F)};
        float aX = 0F, aY = -0.4F, aZ = 0.5F;
        Vector3f still = new Vector3f();
        assertTrue(PistolHold.hip(ntm, new Matrix4f(), ends, aX, aY, aZ, still));
        // the middle at rest, in view space
        Vector3f p0 = ntm.transformPosition(new Vector3f(still).add(ends[2]));
        float len = 3F;
        assertEquals(-PistolHold.RIGHT * len, p0.x, 1e-4F);
        assertEquals(PistolHold.UP * len, p0.y, 1e-4F);
        assertEquals(PistolHold.AHEAD * len, p0.z, 1e-4F);
        // WalkSprint's move as apply makes it from an identity view: the shift, then the turns around the pivot
        Vector3f shift = new Vector3f(0.1F, -0.3F, 0.15F), pivot = new Vector3f(-0.2F, -0.9F, 2.6F);
        Matrix3f turn = new Matrix3f().rotateZ(0.25F).rotateY(0.5F).rotateX(0.5F);
        Matrix4f move = new Matrix4f().translation(shift).translate(pivot).rotateZ(0.25F).rotateY(0.5F).rotateX(0.5F)
                .translate(-pivot.x, -pivot.y, -pivot.z);
        Vector3f hip = new Vector3f();
        assertTrue(PistolHold.hip(new Matrix4f(move).mul(ntm), move, ends, aX, aY, aZ, hip));
        assertEquals(still.x, hip.x, 1e-5F);
        assertEquals(still.y, hip.y, 1e-5F);
        assertEquals(still.z, hip.z, 1e-5F);
        Vector3f got = new Matrix4f(move).mul(ntm).transformPosition(new Vector3f(hip).add(ends[2]));
        Vector3f want = turn.transform(new Vector3f(p0).sub(pivot)).add(pivot).add(shift);
        assertEquals(want.x, got.x, 1e-4F);
        assertEquals(want.y, got.y, 1e-4F);
        assertEquals(want.z, got.z, 1e-4F);
    }

    // the hands' pose space is turned with the world (the camera's view brings it to the eye): the hip place is the
    // same however the camera looks, so the gun turns with the camera and does not stay where it was in the world
    @Test
    void theHipPlaceTurnsWithTheCamera() {
        Matrix4f ntm = new Matrix4f().translation(0F, 0F, 0.875F);
        Vector3f[] ends = {new Vector3f(0F, 0F, -0.5F), new Vector3f(0F, 0F, 2.5F), new Vector3f(0.05F, -0.1F, 1F)};
        float aX = 0F, aY = -0.4F, aZ = 0.5F;
        Vector3f still = new Vector3f();
        assertTrue(PistolHold.hip(new Matrix4f(), ntm, new Matrix4f(), ends, aX, aY, aZ, still));
        // looking 135° to the side and 30° down, with the turn's sway in the pose
        Matrix4f view = new Matrix4f().rotateX(0.52F).rotateY(2.36F);
        Matrix4f inView = new Matrix4f(view).invert();
        Matrix4f sway = new Matrix4f().translation(0.05F, 0F, 0F).rotateY(0.1F);
        Matrix4f moved = new Matrix4f(inView).mul(sway).mul(view);
        Vector3f hip = new Vector3f();
        assertTrue(PistolHold.hip(view, new Matrix4f(moved).mul(inView).mul(ntm), moved, ends, aX, aY, aZ, hip));
        assertEquals(still.x, hip.x, 1e-4F);
        assertEquals(still.y, hip.y, 1e-4F);
        assertEquals(still.z, hip.z, 1e-4F);
    }

    // ---- aimed (aimHold): NTM's aimed place moved along the eye's line until the picture is as big as the measured
    // pistols'

    // the eye's space as NTM's frame may be turned in it, and a gun of two boxes: a slide on the eye's line, a grip
    static final Matrix4f TURNED = new Matrix4f().rotationY((float) Math.PI).rotateX(0.04F).translate(0.02F, -0.3F, 0.875F);
    static final Vector3f[] BOXES = boxes();

    static Vector3f[] boxes() {
        List<Vector3f> out = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            out.add(new Vector3f((i & 1) == 0 ? -0.1F : 0.1F, (i & 2) == 0 ? -0.2F : 0F, (i & 4) == 0 ? -0.6F : 0.6F));
            out.add(new Vector3f((i & 1) == 0 ? -0.1F : 0.1F, (i & 2) == 0 ? -0.9F : -0.2F, (i & 4) == 0 ? -0.6F : -0.2F));
        }
        return out.toArray(new Vector3f[0]);
    }

    // the aimed place goes along the eye's line only: a point of the gun on that line stays on it, farther, and the
    // picture is then AIM_SIZE big
    @Test
    void theAimedPlaceKeepsTheSightLine() {
        // NTM's aimed place: the slide's top on the eye's line
        Vector3f a = new Matrix4f(TURNED).invert().transformPosition(new Vector3f(0F, 0F, 0.9F));
        Vector3f out = new Vector3f();
        assertTrue(PistolHold.aim(TURNED, new Matrix4f(), BOXES, a.x, a.y, a.z, out));
        // the gun's point that was at the eye's line, 0.9 ahead, and another one on it, its rear sight
        for (Vector3f q : new Vector3f[] {new Vector3f(), new Matrix4f(TURNED).invert().transformPosition(new Vector3f(0F, 0F, 1.4F)).sub(a)}) {
            Vector3f before = TURNED.transformPosition(new Vector3f(a).add(q)), after = TURNED.transformPosition(new Vector3f(out).add(q));
            assertEquals(0F, before.x, 1e-5F);
            assertEquals(0F, before.y, 1e-5F);
            assertEquals(0F, after.x, 1e-4F);
            assertEquals(0F, after.y, 1e-4F);
            assertTrue(after.z > before.z + 0.5F, "farther: " + before.z + " -> " + after.z);
        }
        Vector3f[] eye = new Vector3f[BOXES.length];
        for (int i = 0; i < eye.length; i++) eye[i] = TURNED.transformPosition(new Vector3f(out).add(BOXES[i]));
        assertEquals(PistolHold.AIM_SIZE, PistolHold.size(eye, 0F), 1e-4F);
    }

    // a gun wider than it is deep still ends as big as AIM_SIZE: the search reaches far enough for its width
    @Test
    void aWideGunIsAimedAsSmall() {
        List<Vector3f> c = new ArrayList<>();
        for (int i = 0; i < 4; i++) c.add(new Vector3f((i & 1) == 0 ? -1F : 1F, (i & 2) == 0 ? -1F : 1F, 1F));
        Vector3f out = new Vector3f();
        assertTrue(PistolHold.aim(new Matrix4f(), new Matrix4f(), c.toArray(new Vector3f[0]), 0F, 0F, 0F, out));
        Vector3f[] eye = new Vector3f[c.size()];
        for (int i = 0; i < eye.length; i++) eye[i] = new Vector3f(c.get(i)).add(out);
        assertEquals(PistolHold.AIM_SIZE, PistolHold.size(eye, 0F), 1e-4F);
    }

    // as the hip place: the aimed place is the same however the camera looks, with the turn's sway in the pose, and
    // WalkSprint's move stays on top of it
    @Test
    void theAimedPlaceTurnsWithTheCamera() {
        Vector3f a = new Matrix4f(TURNED).invert().transformPosition(new Vector3f(0F, 0F, 0.9F));
        Vector3f still = new Vector3f();
        assertTrue(PistolHold.aim(new Matrix4f(), TURNED, new Matrix4f(), BOXES, a.x, a.y, a.z, still));
        Matrix4f view = new Matrix4f().rotateX(0.52F).rotateY(2.36F);
        Matrix4f inView = new Matrix4f(view).invert();
        Matrix4f sway = new Matrix4f().translation(0.05F, 0F, 0F).rotateY(0.1F);
        Matrix4f moved = new Matrix4f(inView).mul(sway).mul(view);
        Vector3f aimed = new Vector3f();
        assertTrue(PistolHold.aim(view, new Matrix4f(moved).mul(inView).mul(TURNED), moved, BOXES, a.x, a.y, a.z, aimed));
        assertEquals(still.x, aimed.x, 1e-4F);
        assertEquals(still.y, aimed.y, 1e-4F);
        assertEquals(still.z, aimed.z, 1e-4F);
        // and it did move: 0.5 along the eye's line at least
        assertTrue(still.distance(a) > 0.5F, "moved " + still.distance(a));
    }

    // aimHold 0: NTM's own aimed place; in between, the way between the two
    @Test
    void aimHoldOffIsNtmsPlace() {
        float was = Config.aimHold;
        try {
            Config.aimHold = 0F;
            assertFalse(PistolHold.aimOn());
        } finally {
            Config.aimHold = was;
        }
        Vector3f measured = new Vector3f(0.3F, -0.1F, 2.5F);
        Vector3f off = PistolHold.blend(0F, 0.0, -0.4, 0.5, measured), half = PistolHold.blend(0.5F, 0.0, -0.4, 0.5, measured);
        assertEquals(0F, off.x, 0F);
        assertEquals(-0.4F, off.y, 0F);
        assertEquals(0.5F, off.z, 0F);
        assertEquals(0.15F, half.x, 1e-6F);
        assertEquals(-0.25F, half.y, 1e-6F);
        assertEquals(1.5F, half.z, 1e-6F);
        Vector3f over = PistolHold.blend(2F, 0.0, -0.4, 0.5, measured);
        assertEquals(measured.z, over.z, 1e-6F);
    }

    // the move PistolHold undoes: the sway, WalkSprint and NTM's bob between WalkSprint.base and setupFirstPerson, all
    // but NTM's half turn (CE's setupTransformsAndRender: the sway here, then glRotate 180 about y, then its bob); the
    // hip place found before it is NTM's rest frame, and the gun's middle from the hip goes with the move
    @Test
    void theMovedFrameIsTheModelviewPastTheHalfTurn() {
        Matrix4f base = new Matrix4f().rotateX(0.01F);
        Matrix4f sway = new Matrix4f().translation(0.03F, -0.02F, 0F).rotateY(0.12F).rotateX(-0.05F);
        Matrix4f bob = new Matrix4f().translation(0.02F, -0.03F, 0F).rotateZ(0.026F);
        Matrix4f at = new Matrix4f(base).mul(sway).rotateY((float) Math.PI).mul(bob);
        Matrix4f moved = WalkSprint.movedFrom(base, at, new Matrix4f());
        Matrix4f rest = new Matrix4f(moved).invert().mul(at), want = new Matrix4f(base).rotateY((float) Math.PI);
        for (int i = 0; i < 16; i++) assertEquals(want.get(i / 4, i % 4), rest.get(i / 4, i % 4), 1e-5F);
        // at rest it is identity
        Matrix4f still = WalkSprint.movedFrom(base, new Matrix4f(base).rotateY((float) Math.PI), new Matrix4f());
        for (int i = 0; i < 16; i++) assertEquals(i / 4 == i % 4 ? 1F : 0F, still.get(i / 4, i % 4), 1e-5F);
        // the gun from the hip goes with it (the modelview at standardAimingTransform: at·ntm)
        Matrix4f ntm = new Matrix4f().translation(0F, 0F, 0.875F);
        Vector3f[] ends = {new Vector3f(0F, 0F, -0.5F), new Vector3f(0F, 0F, 2.5F), new Vector3f(0.05F, -0.1F, 1F)};
        Matrix4f view = PistolHold.eye();
        Vector3f calm = new Vector3f(), hip = new Vector3f();
        assertTrue(PistolHold.hip(view, new Matrix4f(base).rotateY((float) Math.PI).mul(ntm), new Matrix4f(), ends, 0F, -0.4F, 0.5F, calm));
        assertTrue(PistolHold.hip(view, new Matrix4f(at).mul(ntm), moved, ends, 0F, -0.4F, 0.5F, hip));
        assertEquals(calm.x, hip.x, 1e-4F);
        assertEquals(calm.y, hip.y, 1e-4F);
        assertEquals(calm.z, hip.z, 1e-4F);
    }

    // the GL modelview is the eye's already (CE draws the hand from an identity one): the eye's frame hip works in
    // (ahead +z, right -x) is the modelview turned half round about y, at any turn of the camera
    @Test
    void theEyeIsTheModelviewTurnedHalfRound() {
        Matrix4f eye = PistolHold.eye();
        Matrix4f half = new Matrix4f().rotationY((float) Math.PI);
        for (int i = 0; i < 16; i++) assertEquals(half.get(i / 4, i % 4), eye.get(i / 4, i % 4), 1e-6F);
    }
}
