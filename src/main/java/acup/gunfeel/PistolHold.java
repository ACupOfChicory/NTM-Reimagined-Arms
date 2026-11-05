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
 * curve. The aimed place is NTM's own for each gun (its sights), only the hip place and the curve change.
 *
 * <p>The hip place is where the gun's middle (the middle of its cloud of vertices) stands from the eye, in the gun's
 * length L, the same for every pistol: guns of a different shape then stand as far in their lengths and look alike.
 * Not a shift from the aimed place: NTM's eye is nearly at the back of its pistols when aimed, at some (the heavy
 * revolver) almost on it, and a shift by the aimed depths would put them all differently. The place is aimed at the
 * measured pistols' picture from the hip, not at their place in L (theirs is 1.0..1.3 L ahead and NTM's guns are drawn
 * otherwise): to the screen's edge its middle at 0.54..0.60 of the screen's width and 0.77..0.81 of its height,
 * 0.07..0.14 wide and 0.38..0.45 high, the axis at -98..-109°. NTM's LAG, Star F, heavy and light revolvers, through
 * their models, give the middle of that with their middle 0.081 L right, 0.334 L down and 0.965 L ahead.
 */
public final class PistolHold {
    // the gun's middle from the hip, from the eye, in L: right, up, ahead
    static final float RIGHT = 0.081F, UP = -0.334F, AHEAD = 0.965F;

    // each gun's two ends and its middle past NTM's placing call (relative to where it puts the gun), from an earlier
    // frame
    private static final Map<Item, Vector3f[]> ENDS = new IdentityHashMap<>();
    // this frame: the pose past the hip shift and the tilt, for the gun drawn
    private static final Matrix4f FRAME = new Matrix4f();
    private static Item framed;
    // NTM's setupFirstPerson is about to place the gun: its first standardAimingTransform is the placing one
    private static boolean placing;

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
        return hip(pose.last().pose(), WalkSprint.moved(item), ends, (float) aX, (float) aY, (float) aZ, out);
    }

    /**
     * The hip place for the pose m of standardAimingTransform, the gun's ends and middle past it, the aimed place a.
     * moved is WalkSprint's move in view space, already in m: the place is found in the frame before it (moved⁻¹·m),
     * so the walk's, the run's and the jump's move stays on top of the hip place, as it does of the aimed one.
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
    }

    private static boolean ok(Vector3f v) {
        return Float.isFinite(v.x) && Float.isFinite(v.y) && Float.isFinite(v.z) && v.lengthSquared() < 100F;
    }
}
