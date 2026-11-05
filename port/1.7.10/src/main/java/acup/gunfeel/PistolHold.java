package acup.gunfeel;

import java.nio.FloatBuffer;
import java.util.IdentityHashMap;
import java.util.Map;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.anim.HbmAnimations;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * A pistol or a revolver held as TaCZ holds them (pistolHold): from the hip the gun stays almost on the sight line,
 * a little to the right, lower and closer than when aimed and pointing the same way, and it comes up to the sights
 * on a smooth curve. The aimed place is NTM's own for each gun (its sights), only the hip place and the curve change.
 *
 * <p>The shift is measured by the gun's two ends as they are aimed: their depths' harmonic mean H, by which a shift
 * moves the gun's picture on the screen (each end moves by the shift over its depth). TaCZ's pistols (glock_17,
 * m1911, cz75, deagle, p320, b93r) hold the gun from the hip 0.07..0.29 H to the right of the aimed place,
 * 0.08..0.24 H lower and 0..0.21 H closer; here the middle of that. Not by the gun's length: NTM's eye is nearly at
 * the back of its pistols and TaCZ's 0.6..1 of the length behind, so the same share of the length would move the
 * gun on the screen several times as far. notes/tacz-pistol-hold-scheme.md has the numbers.
 */
// port: copied from 26.2 (3ea930c, notes/ports/port-1.7.10-copied.md). The numbers, the curve and the measuring are
// unchanged; the pose is the GL modelview (26.2: PoseStack), which here is 26.2's turned half round about y (GL's eye
// looks along -z with the screen's right +x, see WalkSprint): hip takes it into 26.2's frame first, so the shift is
// 26.2's. No left main hand on 1.7.10.
public final class PistolHold {
    // the hip place against the aimed one, in H: right, up, toward the eye
    static final float RIGHT = 0.16F, UP = -0.20F, BACK = 0.07F;
    // an end nearer than this share of the gun's length counts as that far (an end at the eye would make H zero)
    static final float NEAREST = 0.05F;
    // NTM's aiming progress goes linearly over 0.2 s; TaCZ's pistols are 90 % in at about 0.15 s and settle by 0.2 s
    static final float CURVE_END = 0.85F;

    // each gun's two ends in the frame past NTM's placing call (relative to where it puts the gun), from an earlier frame
    private static final Map<Item, Vector3f[]> ENDS = new IdentityHashMap<>();
    // this frame: the modelview past the hip shift and the tilt, for the gun drawn
    private static final Matrix4f FRAME = new Matrix4f();
    private static Item framed;
    // NTM's setupFirstPerson is about to place the gun: its first standardAimingTransform is the placing one
    private static boolean placing;
    private static final FloatBuffer GL_BUF = BufferUtils.createFloatBuffer(16);

    private PistolHold() {
    }

    /** The hold is on for the gun drawn (Config selected for it): a pistol or a revolver by its kickPreset. */
    public static boolean on() {
        return Config.enabled && Config.pistolHold > 0F && WalkSprint.pistol(Config.kickPreset);
    }

    /**
     * The gun is aimed through a scope (the Little Macintosh): NTM's aimed place puts the scope at the eye, with the
     * gun's back behind it, not the sights on the line, and a hip place next to it would put the gun into the camera.
     * NTM's own hold is kept for it.
     */
    public static boolean scoped(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemGunBaseNT gun && gun.getConfig(stack, 0).getScopeTexture(stack) != null;
    }

    /** NTM's linear aiming progress on TaCZ's curve: a smooth start and a smooth stop, in a little sooner. */
    public static float curve(float p) {
        float x = Math.clamp(p / CURVE_END, 0F, 1F);
        return x * x * (3F - 2F * x);
    }

    private static Matrix4f modelview(Matrix4f out) {
        GL_BUF.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, GL_BUF);
        return out.set(GL_BUF);
    }

    /**
     * The hip place in the frame of standardAimingTransform (the modelview as it is there), from NTM's aimed place
     * aX, aY, aZ: false while the gun is not measured yet, then NTM's own hold is kept.
     */
    public static boolean hip(Item item, double aX, double aY, double aZ, Vector3f out) {
        Vector3f[] ends = ENDS.get(item);
        if (ends == null) return false;
        // 26.2's pose: the GL modelview turned half round about y
        Matrix4f m = new Matrix4f().rotationY((float) Math.PI).mul(modelview(new Matrix4f()));
        // the ends' depths when aimed: the eye is at the pose space's origin and looks along +z
        Vector3f b = m.transformPosition((float) aX + ends[0].x, (float) aY + ends[0].y, (float) aZ + ends[0].z, new Vector3f());
        Vector3f f = m.transformPosition((float) aX + ends[1].x, (float) aY + ends[1].y, (float) aZ + ends[1].z, new Vector3f());
        float near = NEAREST * b.distance(f);
        float zb = Math.max(near, b.z), zf = Math.max(near, f.z);
        float dist = 2F / (1F / zb + 1F / zf);
        Matrix3f r = m.get3x3(new Matrix3f());
        if (!Float.isFinite(dist) || dist < 1e-4F || Math.abs(r.determinant()) < 1e-12F) return false;
        // in 26.2's pose the screen's right is -x and the eye looks along +z (Arms.cameraFrame); the inverse takes the
        // shift through what NTM's renderer did before this frame
        Vector3f d = new Vector3f(-RIGHT * dist, UP * dist, -BACK * dist);
        r.invert().transform(d);
        out.set((float) aX + d.x, (float) aY + d.y, (float) aZ + d.z);
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
    public static void framed(Item item) {
        modelview(FRAME);
        framed = item;
    }

    /**
     * After NTM drew the gun: its ends in the frame past the placing call, so as they are when aimed at aX, aY, aZ
     * with no tilt; only while no NTM animation moves the gun's body.
     */
    public static void placed(Item item) {
        if (framed != item) return;
        framed = null;
        if (HbmAnimations.getRelevantAnim(0) != null) return;
        Vector3f back = new Vector3f(), front = new Vector3f();
        if (!Arms.gunEndsAimed(item, back, front)) return;
        Matrix4f inv = new Matrix4f(FRAME).invert();
        inv.transformPosition(back);
        inv.transformPosition(front);
        if (ok(back) && ok(front) && back.distanceSquared(front) > 1e-6F) ENDS.put(item, new Vector3f[] {back, front});
    }

    private static boolean ok(Vector3f v) {
        return Float.isFinite(v.x) && Float.isFinite(v.y) && Float.isFinite(v.z) && v.lengthSquared() < 100F;
    }
}
