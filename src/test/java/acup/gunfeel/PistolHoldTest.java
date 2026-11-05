package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.hbm.render.loader.GroupObject;
import com.hbm.render.loader.ObjMesh;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

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

    // ---- the picture from the hip, worked out from NTM's own pistols: how NTM places them aimed (setupFirstPerson
    // and renderFirstPerson at rest) and their models. Space as in standardAimingTransform: the eye at 0, looking
    // along +z, +x to the screen's left; the screen of the measurements: 70° high, 1.6 wide.
    static final double TAN = Math.tan(Math.toRadians(35)), ASPECT = 1.6;

    record Gun(String model, Set<String> parts, Vector3f aimed, float scale, boolean turned) {
    }

    static final Gun[] GUNS = {
            // ItemRenderLag: 0.875 forward, aimed at (0, -0.421875, 0.5), the model at 0.25 turned 90° about y
            new Gun("mike_hawk", Set.of("Grip", "Slide", "Hammer", "Magazine"), new Vector3f(0F, -0.421875F, 1.375F), 0.25F, true),
            // ItemRenderStarF: 0.875 forward, aimed at (0, -0.953125, 1), the model at 0.25
            new Gun("star_f", Set.of("Gun", "Slide", "Hammer", "Mag"), new Vector3f(0F, -0.953125F, 1.875F), 0.25F, false),
            // ItemRenderHeavyRevolver: 1 forward, aimed at (0, -0.484375, 0), the model at 0.125 turned 90° about y;
            // the eye almost at its back end
            new Gun("lilmac", Set.of("Gun", "Cylinder", "Hammer", "Pivot"), new Vector3f(0F, -0.484375F, 1F), 0.125F, true),
            // ItemRenderAtlas (the light revolvers): 0.875 forward, aimed at (0, -0.390625, 0.25), the model at 0.125
            new Gun("bio_revolver", Set.of("Barrel", "Latch", "Hammer", "Drum", "Grip"), new Vector3f(0F, -0.390625F, 1.125F), 0.125F, false)};

    static List<Vector3f> vertices(Gun gun) throws Exception {
        List<Vector3f> out = new ArrayList<>();
        try (var stream = PistolHoldTest.class.getResourceAsStream("/assets/hbm/models/weapons/" + gun.model() + ".hbm_mesh")) {
            assertNotNull(stream, gun.model());
            for (GroupObject g : ObjMesh.read(stream, gun.model())) {
                if (!gun.parts().contains(g.name)) continue;
                float[] q = g.quads(false);
                for (int i = 0; i + 2 < q.length; i += GroupObject.STRIDE) {
                    Vector3f v = new Vector3f(q[i], q[i + 1], q[i + 2]);
                    // Axis.YP 90°: (x, y, z) -> (z, y, -x)
                    if (gun.turned()) v.set(v.z, v.y, -v.x);
                    out.add(v.mul(gun.scale()));
                }
            }
        }
        return out;
    }

    static float[] screen(Vector3f p) {
        return new float[] {(float) (0.5 + (-p.x / p.z) / (2 * TAN * ASPECT)), (float) (0.5 - (p.y / p.z) / (2 * TAN))};
    }

    /** The middle of the gun's cloud of vertices: their mean across, halfway along (as Arms.gunMiddleAimed). */
    static Vector3f middle(List<Vector3f> model) {
        Vector3f sum = new Vector3f();
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (Vector3f v : model) {
            sum.add(v);
            minZ = Math.min(minZ, v.z);
            maxZ = Math.max(maxZ, v.z);
        }
        return new Vector3f(sum.x / model.size(), sum.y / model.size(), (minZ + maxZ) / 2);
    }

    /** The picture of the gun placed at o: the middle of its frame to the screen's edge, its size, its axis' angle. */
    static float[] picture(List<Vector3f> model, Vector3f o) {
        float l = 1, r = 0, t = 1, b = 0;
        Vector3f min = new Vector3f(Float.MAX_VALUE), max = new Vector3f(-Float.MAX_VALUE);
        for (Vector3f v : model) {
            min.min(v);
            max.max(v);
            Vector3f p = new Vector3f(v).add(o);
            if (p.z < 0.05F) continue;
            float[] s = screen(p);
            l = Math.min(l, Math.max(0F, s[0]));
            r = Math.max(r, Math.min(1F, s[0]));
            t = Math.min(t, Math.max(0F, s[1]));
            b = Math.max(b, Math.min(1F, s[1]));
        }
        Vector3f mid = middle(model);
        float cx = mid.x, cy = mid.y;
        float[] back = screen(new Vector3f(cx, cy, min.z).add(o)), front = screen(new Vector3f(cx, cy, max.z).add(o));
        float angle = (float) Math.toDegrees(Math.atan2((front[1] - back[1]) * 800, (front[0] - back[0]) * 1280));
        return new float[] {(l + r) / 2, (t + b) / 2, r - l, b - t, angle};
    }

    // the hip place as PistolHold.hip puts it (no turn before standardAimingTransform): the middle at RIGHT, UP,
    // AHEAD in the gun's length from the eye, the length along the model's axis as Arms measures it
    static Vector3f hip(List<Vector3f> model) {
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (Vector3f v : model) {
            minZ = Math.min(minZ, v.z);
            maxZ = Math.max(maxZ, v.z);
        }
        float len = maxZ - minZ;
        return new Vector3f(-PistolHold.RIGHT * len, PistolHold.UP * len, PistolHold.AHEAD * len).sub(middle(model));
    }

    // from the hip the picture is within the two measured pistols': the middle of the frame to the screen's edge at
    // 0.539..0.601 and 0.775..0.808, 0.069..0.144 wide and 0.384..0.451 high, the axis at -108.8..-97.6°
    @Test
    void hipPictureIsAsMeasured() throws Exception {
        float[][] range = {{0.539F, 0.601F}, {0.7745F, 0.808F}, {0.069F, 0.144F}, {0.384F, 0.451F}, {-108.8F, -97.6F}};
        String[] names = {"x", "y", "width", "height", "angle"};
        for (Gun gun : GUNS) {
            List<Vector3f> model = vertices(gun);
            float[] p = picture(model, hip(model));
            for (int k = 0; k < 5; k++)
                assertTrue(p[k] >= range[k][0] && p[k] <= range[k][1], gun.model() + " " + names[k] + " " + p[k]);
            // aimed, NTM's own place: the gun is on the middle line
            assertEquals(0.5F, picture(model, gun.aimed())[0], 0.01F, gun.model());
        }
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

    // the sway as the camera turns (WeaponSway) and NTM's own bob are in the pose before NTM places the gun, and
    // WalkSprint.moved holds them with WalkSprint at rest: the gun's middle from the hip goes with them, it does not
    // stand still on the screen while the camera turns
    @Test
    void theTurnsSwayMovesTheHipPlace() {
        Matrix4f ntm = new Matrix4f().translation(0F, 0F, 0.875F);
        Vector3f[] ends = {new Vector3f(0F, 0F, -0.5F), new Vector3f(0F, 0F, 2.5F), new Vector3f(0.05F, -0.1F, 1F)};
        float aX = 0F, aY = -0.4F, aZ = 0.5F;
        Vector3f still = new Vector3f();
        assertTrue(PistolHold.hip(ntm, new Matrix4f(), ends, aX, aY, aZ, still));
        Vector3f p0 = ntm.transformPosition(new Vector3f(still).add(ends[2]));
        // moved knows the gun only by reference: null stands for the gun drawn (no registries in a unit test)
        net.minecraft.world.item.Item item = null;
        // a quick turn right and down, then NTM's transform up to setupFirstPerson: its half turn about y, its bob
        PoseStack pose = new PoseStack();
        WalkSprint.base(pose);
        WeaponSway.apply(pose, WeaponSway.sample(10F, 200F, 0F));
        WalkSprint.applied(pose, item);
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180F));
        pose.translate(0.02F, -0.03F, 0F);
        pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(1.5F));
        // the move to undo: everything but the half turn
        Matrix4f sway = new Matrix4f(pose.last().pose()).rotateY((float) Math.PI);
        WalkSprint.setup(pose);
        Matrix4f moved = WalkSprint.moved(item);
        for (int i = 0; i < 16; i++) assertEquals(sway.get(i / 4, i % 4), moved.get(i / 4, i % 4), 1e-6F);
        Vector3f hip = new Vector3f();
        assertTrue(PistolHold.hip(new Matrix4f(sway).mul(ntm), moved, ends, aX, aY, aZ, hip));
        Vector3f got = new Matrix4f(sway).mul(ntm).transformPosition(new Vector3f(hip).add(ends[2]));
        Vector3f want = sway.transformPosition(new Vector3f(p0));
        assertEquals(want.x, got.x, 1e-4F);
        assertEquals(want.y, got.y, 1e-4F);
        assertEquals(want.z, got.z, 1e-4F);
        // and the sway moves it noticeably (more than 0.05 of the gun's length)
        assertTrue(want.distance(p0) > 0.15F, "sway " + want.distance(p0));
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

    static Vector3f[] corners(List<Vector3f> model) {
        float[] xyz = new float[model.size() * 3];
        for (int i = 0; i < model.size(); i++) {
            xyz[i * 3] = model.get(i).x;
            xyz[i * 3 + 1] = model.get(i).y;
            xyz[i * 3 + 2] = model.get(i).z;
        }
        return GunGeometry.extremes(xyz);
    }

    /** The visible frame of the gun placed at o, to the screen's edge: left, right, top, bottom. */
    static float[] frame(List<Vector3f> model, Vector3f o) {
        float l = 1, r = 0, t = 1, b = 0;
        for (Vector3f v : model) {
            Vector3f p = new Vector3f(v).add(o);
            if (p.z < 0.05F) continue;
            float[] s = screen(p);
            l = Math.min(l, Math.max(0F, s[0]));
            r = Math.max(r, Math.min(1F, s[0]));
            t = Math.min(t, Math.max(0F, s[1]));
            b = Math.max(b, Math.min(1F, s[1]));
        }
        return new float[] {l, r, t, b};
    }

    // aimed, the picture is within the two measured pistols': the middle of the visible frame at 0.500 across and
    // 0.7235..0.746 down, its bottom at 0.948..0.991, the root of its width by its height at 0.167..0.180 (0.062 by
    // 0.449 .. 0.066 by 0.490); the top stays on the middle line (0.500 ± 0.005), or no farther from it than NTM puts
    // it (the LAG's sights stand a little over it). NTM's models are not of the measured shape: the width and the
    // height each within a tenth past the measured ranges, the Star F's within them; the LAG's bottom at the edge
    @Test
    void aimedPictureIsAsMeasured() throws Exception {
        for (Gun gun : GUNS) {
            List<Vector3f> model = vertices(gun);
            Vector3f a = gun.aimed(), out = new Vector3f();
            assertTrue(PistolHold.aim(new Matrix4f(), new Matrix4f(), corners(model), a.x, a.y, a.z, out), gun.model());
            float[] f = frame(model, out), ntm = frame(model, a);
            float w = f[1] - f[0], h = f[3] - f[2], size = (float) Math.sqrt(w * h);
            String at = gun.model() + " " + java.util.Arrays.toString(f);
            assertEquals(0.5F, (f[0] + f[1]) / 2, 0.005F, at);
            // the LAG, taller for its width than the measured pistols, reaches the screen's edge (to 1.004 unclipped)
            boolean tall = gun.model().equals("mike_hawk");
            assertTrue((f[2] + f[3]) / 2 >= 0.7235F && (f[2] + f[3]) / 2 <= (tall ? 0.748F : 0.746F), at);
            assertTrue(f[3] >= 0.948F && f[3] <= (tall ? 1F : 0.991F), at);
            assertTrue(size >= 0.1668F && size <= 0.1798F, at + " size " + size);
            assertTrue(Math.abs(f[2] - 0.5F) <= Math.max(0.005F, Math.abs(ntm[2] - 0.5F) + 1e-4F), at + " NTM's top " + ntm[2]);
            assertTrue(w >= 0.062F * 0.9F && w <= 0.066F * 1.1F && h >= 0.449F * 0.9F && h <= 0.490F * 1.1F, at);
            if (gun.model().equals("star_f")) assertTrue(w >= 0.062F && w <= 0.066F && h >= 0.449F && h <= 0.490F, at);
            // NTM's own place is far bigger
            assertTrue(ntm[1] - ntm[0] > 2F * w, at);
        }
    }

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

    // the camera's view looks along -z with the screen's right +x; looking along the world's +z (yaw 0, pitch 0) it is
    // half a turn about y, and hip's frame (ahead +z, right -x) is then the pose space itself
    @Test
    void theEyeAtYawZeroIsThePoseSpace() {
        PistolHold.view(new Matrix4f().rotationY((float) Math.PI));
        Matrix4f eye = PistolHold.eye();
        for (int i = 0; i < 16; i++) assertEquals(i / 4 == i % 4 ? 1F : 0F, eye.get(i / 4, i % 4), 1e-6F);
        PistolHold.view(new Matrix4f());
    }
}
