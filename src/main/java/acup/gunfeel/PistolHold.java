package acup.gunfeel;

import java.util.IdentityHashMap;
import java.util.Map;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.anim.HbmAnimations;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * A pistol or a revolver held as the reference measurements show it (pistolHold, notes/measure.md is local): from the
 * hip the gun is a little right of the middle and below it, pointing ahead, and it comes up to the sights on a smooth
 * curve. The aimed place is NTM's own for each gun (its sights on the eye's line), moved along that line (aimHold).
 *
 * <p>The hip place is where the gun's middle (the middle of its cloud of vertices) stands from the eye, in the gun's
 * length L, the same for every pistol: guns of a different shape then stand as far in their lengths and look alike.
 * Not a shift from the aimed place: NTM's eye is nearly at the back of its pistols when aimed, at some (the heavy
 * revolver) almost on it, and a shift by the aimed depths would put them all differently. The place is aimed at the
 * measured pistols' picture from the hip, not at their place in L (theirs is 1.0..1.3 L ahead and NTM's guns are drawn
 * otherwise): to the screen's edge its middle at 0.54..0.60 of the screen's width and 0.77..0.81 of its height,
 * 0.07..0.14 wide and 0.38..0.45 high, the axis at -98..-109°. NTM's LAG, Star F, heavy and light revolvers, through
 * their models, give the middle of that with their middle 0.081 L right, 0.334 L down and 0.965 L ahead.
 *
 * <p>Aimed (aimHold), the measured pistols are small: 0.062..0.066 of the screen's width and 0.449..0.490 of its
 * height, from the middle of the screen nearly down to its edge, where NTM's own aimed place, its sights on the eye's
 * line, puts NTM's pistols up to the eye (0.27..1.0 of the width). The aimed place is moved along that line only, so
 * every point on it stays there and the sights stay on the middle of the screen: as far as the gun's picture is as
 * big as the middle of the measured two (the root of its width by its height, in the tangents of the view, so the
 * same at any field of view and window). NTM's models are not of the measured shape: the Star F lands within both
 * ranges, the heavy revolver is a little too wide and low for them (0.067 by 0.447), the light one and the LAG a
 * little too narrow (0.061 and 0.057 wide). The axis' pitch and the muzzle on the screen (7.5..10.4°, 0.528) are
 * where the models' grips and slides put their middle line under the sights, not where the gun is; only a turn
 * would change them, and a turn takes the front sight off the line.
 */
public final class PistolHold {
    // the gun's middle from the hip, from the eye, in L: right, up, ahead
    static final float RIGHT = 0.081F, UP = -0.334F, AHEAD = 0.965F;

    // the aimed picture's size: the root of its width by its height in the view's tangents (x / z, y / z), the middle
    // of the measured pistols' (0.064 of the width and 0.4695 of the height on a screen 70° high and 1.6 wide)
    static final float AIM_SIZE = (float) Math.sqrt(0.064 * 2 * Math.tan(Math.toRadians(35)) * 1.6 * 0.4695 * 2 * Math.tan(Math.toRadians(35)));
    // nothing of the gun nearer the eye than this when aimed (the hands' near plane)
    private static final float NEAR = 0.05F;

    // each gun's two ends and its middle past NTM's placing call (relative to where it puts the gun), from an earlier
    // frame
    private static final Map<Item, Vector3f[]> ENDS = new IdentityHashMap<>();
    // each gun's corners (GunGeometry.extremes) the same way
    private static final Map<Item, Vector3f[]> CORNERS = new IdentityHashMap<>();
    // this frame: the pose past the hip shift and the tilt, for the gun drawn
    private static final Matrix4f FRAME = new Matrix4f();
    private static Item framed;
    // NTM's setupFirstPerson is about to place the gun: its first standardAimingTransform is the placing one
    private static boolean placing;
    // this frame's camera view the hands' pose is drawn through, past a half turn about y: the eye's space is
    // VIEW·pose with the eye looking along +z and the screen's right -x, as the pose space is when the camera looks
    // along the world's +z (yaw 0, pitch 0). The pose space itself is turned with the world, not with the camera
    private static final Matrix4f VIEW = new Matrix4f();

    private PistolHold() {
    }

    /**
     * The hold is on for the gun drawn (Config selected for it): a pistol or a revolver by its kickPreset. Not with
     * the left main hand yet (NTM draws the gun on the other side then, as WalkSprint.bobOff leaves it).
     */
    public static boolean on() {
        if (!Config.enabled || Config.pistolHold <= 0F || !WalkSprint.pistol(Config.kickPreset)) return false;
        return net.minecraft.client.Minecraft.getInstance().options.mainHand().get() != net.minecraft.world.entity.HumanoidArm.LEFT;
    }

    /**
     * The aimed place by the measurements is on for the gun drawn (aimHold): a pistol or a revolver, as on(), whether
     * its hip place is ours or NTM's.
     */
    public static boolean aimOn() {
        if (!Config.enabled || Config.aimHold <= 0F || !WalkSprint.pistol(Config.kickPreset)) return false;
        return net.minecraft.client.Minecraft.getInstance().options.mainHand().get() != net.minecraft.world.entity.HumanoidArm.LEFT;
    }

    /**
     * The gun is aimed through a scope (the Little Macintosh): NTM's aimed place puts the scope at the eye, with the
     * gun's back behind it, not the sights on the line, and a hip place next to it would put the gun into the camera.
     * NTM's own hold is kept for it.
     */
    public static boolean scoped(ItemStack stack) {
        return stack.getItem() instanceof ItemGunBaseNT gun && gun.getConfig(stack, 0).getScopeTexture(stack) != null;
    }

    /**
     * NTM's linear aiming progress (0.2 s) on a smooth start and a smooth stop, the same in and out, as the measured
     * pistols aim: 10 / 50 / 90 % at 0.05..0.10 / 0.10..0.18 / 0.18..0.26 s, here (a tick's half late, as NTM starts)
     * at 0.06 / 0.13 / 0.19 s.
     */
    public static float curve(float p) {
        float x = Math.clamp(p, 0F, 1F);
        return x * x * (3F - 2F * x);
    }

    /**
     * The hip place in the frame of standardAimingTransform (the pose as it is there), from NTM's aimed place
     * aX, aY, aZ: false while the gun is not measured yet, then NTM's own hold is kept.
     */
    public static boolean hip(PoseStack pose, Item item, double aX, double aY, double aZ, Vector3f out) {
        Vector3f[] ends = ENDS.get(item);
        if (ends == null) return false;
        return hip(VIEW, pose.last().pose(), WalkSprint.moved(item), ends, (float) aX, (float) aY, (float) aZ, out);
    }

    /**
     * The hip place for the camera's view, the pose and moved in the pose space (turned with the world): in the eye's
     * space the pose is view·pose and moved is view·moved·view⁻¹.
     */
    static boolean hip(Matrix4f view, Matrix4f pose, Matrix4f moved, Vector3f[] ends, float aX, float aY, float aZ, Vector3f out) {
        if (Math.abs(view.determinant()) < 1e-12F) return false;
        Matrix4f eyeMoved = new Matrix4f(view).mul(moved).mul(new Matrix4f(view).invert());
        return hip(new Matrix4f(view).mul(pose), eyeMoved, ends, aX, aY, aZ, out);
    }

    /**
     * The camera's view at the start of the hands' drawing (GameRenderer.renderItemInHand): the eye's space looks
     * along -z with the screen's right +x, half a turn about y from the frame hip works in.
     */
    public static void view(org.joml.Matrix4fc view) {
        VIEW.rotationY((float) Math.PI).mul(view);
    }

    static Matrix4f eye() {
        return VIEW;
    }

    /**
     * The hip place for the pose m of standardAimingTransform in the eye's space (the eye at the origin, looking
     * along +z: VIEW·pose), the gun's ends and middle past it, the aimed place a. moved is the move in the same space
     * before NTM places the gun (the turn's sway, WalkSprint, NTM's bob), already in m: the place is found in the
     * frame before it (moved⁻¹·m), so the sway as the camera turns and the walk's, the run's and the jump's move stay
     * on top of the hip place, as they do of the aimed one.
     */
    static boolean hip(Matrix4f pose, Matrix4f moved, Vector3f[] ends, float aX, float aY, float aZ, Vector3f out) {
        if (Math.abs(moved.determinant()) < 1e-12F) return false;
        Matrix4f m = new Matrix4f(moved).invert().mul(pose);
        // the ends and the middle when aimed: the eye is at the pose space's origin and looks along +z
        Vector3f b = m.transformPosition(aX + ends[0].x, aY + ends[0].y, aZ + ends[0].z, new Vector3f());
        Vector3f f = m.transformPosition(aX + ends[1].x, aY + ends[1].y, aZ + ends[1].z, new Vector3f());
        Vector3f c = m.transformPosition(aX + ends[2].x, aY + ends[2].y, aZ + ends[2].z, new Vector3f());
        float len = b.distance(f);
        Matrix3f r = m.get3x3(new Matrix3f());
        if (!Float.isFinite(len) || len < 1e-4F || Math.abs(r.determinant()) < 1e-12F) return false;
        // the pose space is turned half round from the screen's: the screen's right is -x and the eye looks along +z
        // (Arms.cameraFrame); the middle goes from where it is aimed to its hip place, and the inverse takes that
        // through what NTM's renderer did before this frame
        Vector3f d = new Vector3f(-RIGHT * len - c.x, UP * len - c.y, AHEAD * len - c.z);
        r.invert().transform(d);
        out.set(aX + d.x, aY + d.y, aZ + d.z);
        return Float.isFinite(out.x) && Float.isFinite(out.y) && Float.isFinite(out.z);
    }

    /**
     * The aimed place in the frame of standardAimingTransform, from NTM's aimed place aX, aY, aZ: false while the gun
     * is not measured yet, then NTM's own place is kept.
     */
    public static boolean aim(PoseStack pose, Item item, double aX, double aY, double aZ, Vector3f out) {
        Vector3f[] corners = CORNERS.get(item);
        if (corners == null) return false;
        return aim(VIEW, pose.last().pose(), WalkSprint.moved(item), corners, (float) aX, (float) aY, (float) aZ, out);
    }

    /** The aimed place for the camera's view, the pose and moved in the pose space, as hip. */
    static boolean aim(Matrix4f view, Matrix4f pose, Matrix4f moved, Vector3f[] corners, float aX, float aY, float aZ, Vector3f out) {
        if (Math.abs(view.determinant()) < 1e-12F) return false;
        Matrix4f eyeMoved = new Matrix4f(view).mul(moved).mul(new Matrix4f(view).invert());
        return aim(new Matrix4f(view).mul(pose), eyeMoved, corners, aX, aY, aZ, out);
    }

    /**
     * The aimed place for the pose of standardAimingTransform in the eye's space (the eye at the origin, looking
     * along +z), the gun's corners past it and NTM's aimed place a, found before moved as hip's: NTM's place moved
     * along the eye's line (the eye's z) until the gun's picture is AIM_SIZE big. A point on that line stays on it,
     * so the sights NTM puts there stay on the middle of the screen; nothing turns. The sway and the walk on top of
     * it (moved) move the gun with its sights off the line, as they move NTM's aimed place, and back onto it when the
     * camera stands still.
     */
    static boolean aim(Matrix4f pose, Matrix4f moved, Vector3f[] corners, float aX, float aY, float aZ, Vector3f out) {
        if (corners.length == 0 || Math.abs(moved.determinant()) < 1e-12F) return false;
        Matrix4f m = new Matrix4f(moved).invert().mul(pose);
        Matrix3f r = m.get3x3(new Matrix3f());
        if (Math.abs(r.determinant()) < 1e-12F) return false;
        Vector3f[] eye = new Vector3f[corners.length];
        float nearest = Float.MAX_VALUE, across = 0F;
        for (int i = 0; i < corners.length; i++) {
            eye[i] = m.transformPosition(aX + corners[i].x, aY + corners[i].y, aZ + corners[i].z, new Vector3f());
            nearest = Math.min(nearest, eye[i].z);
            across = Math.max(across, Math.max(Math.abs(eye[i].x), Math.abs(eye[i].y)));
        }
        if (!Float.isFinite(nearest) || !Float.isFinite(across)) return false;
        // the picture shrinks as the gun goes away: from all of it in front of the eye to where its nearest point is
        // so far that no side of the picture can be wider than AIM_SIZE (2 across / its depth)
        float lo = NEAR - nearest, hi = Math.max(lo, 2F * across / AIM_SIZE - nearest);
        float dz;
        if (size(eye, lo) <= AIM_SIZE) dz = lo;
        else if (size(eye, hi) >= AIM_SIZE) dz = hi;
        else {
            for (int k = 0; k < 40; k++) {
                float mid = (lo + hi) / 2F;
                if (size(eye, mid) > AIM_SIZE) lo = mid;
                else hi = mid;
            }
            dz = (lo + hi) / 2F;
        }
        Vector3f d = r.invert().transform(new Vector3f(0F, 0F, dz));
        out.set(aX + d.x, aY + d.y, aZ + d.z);
        return Float.isFinite(out.x) && Float.isFinite(out.y) && Float.isFinite(out.z);
    }

    // the root of the picture's width by its height, in the view's tangents, of the points moved dz along the eye's z
    static float size(Vector3f[] eye, float dz) {
        float l = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = Float.MAX_VALUE, t = -Float.MAX_VALUE;
        for (Vector3f p : eye) {
            float z = p.z + dz;
            if (z < NEAR * 0.5F) continue;
            l = Math.min(l, p.x / z);
            r = Math.max(r, p.x / z);
            b = Math.min(b, p.y / z);
            t = Math.max(t, p.y / z);
        }
        return l > r ? 0F : (float) Math.sqrt((r - l) * (t - b));
    }

    /** NTM's aimed place a, w of the way (aimHold, at most 1) to the measured one. */
    public static Vector3f blend(float w, double aX, double aY, double aZ, Vector3f measured) {
        float k = Math.clamp(w, 0F, 1F);
        return new Vector3f((float) (aX + (measured.x - aX) * k), (float) (aY + (measured.y - aY) * k), (float) (aZ + (measured.z - aZ) * k));
    }

    /** NTM is about to call setupFirstPerson for the gun drawn. */
    public static void setup() {
        placing = true;
    }

    /** This standardAimingTransform is the first since setupFirstPerson began: the one placing the gun. */
    public static boolean placing() {
        boolean was = placing;
        placing = false;
        return was;
    }

    /** The frame the gun is drawn in from the placing call on (its hip shift and tilt done), for the gun drawn. */
    public static void framed(PoseStack pose, Item item) {
        FRAME.set(pose.last().pose());
        framed = item;
    }

    /**
     * After NTM drew the gun: its ends and middle in the frame past the placing call, so as they are when aimed at aX, aY, aZ
     * with no tilt; only while no NTM animation moves the gun's body.
     */
    public static void placed(Item item) {
        if (framed != item) return;
        framed = null;
        if (HbmAnimations.getRelevantAnim(0) != null) return;
        Vector3f back = new Vector3f(), front = new Vector3f(), mid = new Vector3f();
        if (!Arms.gunEndsAimed(item, back, front) || !Arms.gunMiddleAimed(item, mid)) return;
        Matrix4f inv = new Matrix4f(FRAME).invert();
        inv.transformPosition(back);
        inv.transformPosition(front);
        inv.transformPosition(mid);
        if (ok(back) && ok(front) && ok(mid) && back.distanceSquared(front) > 1e-6F) ENDS.put(item, new Vector3f[] {back, front, mid});
        Vector3f[] corners = Arms.gunExtremesAimed(item);
        if (corners == null || corners.length == 0) return;
        for (Vector3f c : corners) if (!ok(inv.transformPosition(c))) return;
        CORNERS.put(item, corners);
    }

    private static boolean ok(Vector3f v) {
        return Float.isFinite(v.x) && Float.isFinite(v.y) && Float.isFinite(v.z) && v.lengthSquared() < 100F;
    }
}
