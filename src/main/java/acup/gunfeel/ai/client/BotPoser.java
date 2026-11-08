package acup.gunfeel.ai.client;

import acup.gunfeel.ai.AiPose;
import acup.gunfeel.ai.AiPoseGeometry;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.util.Mth;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Puts an AI mob's HumanoidModel parts where {@link BotAnimator.Frame} says, after vanilla's setupAnim (walk, look,
 * the item's arm pose, the crawl) has run. In order:
 * <ol>
 * <li>the idle loop's breathing, added on top (UAL Idle_Loop, its change from its first frame);</li>
 * <li>the arms: as vanilla left them (HOLD, NO_GUN), aimed two-handed along the look (AIM), held up over the head
 * (BLIND, firing blind over cover), or along the ground when lying with a gun;</li>
 * <li>vanilla's crouch, by its weight;</li>
 * <li>the action: a reload, a kick of a shot, a throw (UAL OverhandThrow), a wave with the free left arm (KayKit
 * Waving, mirrored), a point, a raised hand to halt;</li>
 * <li>a hit's flinch, added on top (UAL Hit_Chest, Hit_Head);</li>
 * <li>the lean: the hips step aside and everything above them tips (AiPoseGeometry, the server's own numbers).</li>
 * </ol>
 * The parts are siblings, so a part's pivot and turn are all there is: no hidden transform the hitbox would miss.
 */
public final class BotPoser {
    static final String[] UPPER = { "head", "body", "right_arm", "left_arm" };
    static final String[] ALL = { "head", "body", "right_arm", "left_arm", "right_leg", "left_leg" };
    private static final float PI = Mth.PI;

    private BotPoser() {
    }

    /** The six parts, by the clip's bone names; head first. */
    public record Parts(ModelPart head, ModelPart body, ModelPart rightArm, ModelPart leftArm, ModelPart rightLeg,
            ModelPart leftLeg) {
        ModelPart get(String bone) {
            return switch (bone) {
                case "head" -> head;
                case "body" -> body;
                case "right_arm" -> rightArm;
                case "left_arm" -> leftArm;
                case "right_leg" -> rightLeg;
                case "left_leg" -> leftLeg;
                default -> null;
            };
        }
    }

    /**
     * @param swim       the render state's swimAmount: lying (an AI mob's crawl) when above 0
     * @param walkSpeed  the render state's walkAnimationSpeed: the idle loop fades out as it walks
     */
    public static void apply(Parts p, BotAnimator.Frame f, float swim, float walkSpeed) {
        float[] a = new float[3], b = new float[3];
        float lying = Mth.clamp(swim, 0F, 1F);
        float upright = 1F - lying;

        // 1. breathing
        float idleW = upright * (1F - Mth.clamp(walkSpeed * 4F, 0F, 1F));
        BotClip idle = BotClips.get(BotClips.IDLE);
        if (idle != null && idleW > 0F) add(p, idle, idle.local(f.idleSec()), idleW, a, b);

        // 2. the arms
        float headX = p.head.xRot, headY = p.head.yRot;
        float hold = f.arms()[AiPose.HOLD] + f.arms()[AiPose.NO_GUN];
        float aim = f.arms()[AiPose.AIM], blind = f.arms()[AiPose.BLIND];
        if (aim + blind > 0F) {
            float rx = p.rightArm.xRot, ry = p.rightArm.yRot, rz = p.rightArm.zRot;
            float lx = p.leftArm.xRot, ly = p.leftArm.yRot, lz = p.leftArm.zRot;
            // aimed: both hands on the gun along the look, as vanilla's crossbow hold
            float arx = -PI / 2F + headX, ary = -0.1F + headY, alx = arx, aly = 0.5F + headY;
            // blind: the gun held up over the head, the face down behind the cover
            // (AiPoseGeometry.blindMuzzle puts the shot where these put the hand)
            float brx = AiPoseGeometry.BLIND_ARM_X, bry = -AiPoseGeometry.BLIND_ARM_Y, blx = brx, bly = AiPoseGeometry.BLIND_ARM_Y;
            p.rightArm.xRot = hold * rx + aim * arx + blind * brx;
            p.rightArm.yRot = hold * ry + aim * ary + blind * bry;
            p.rightArm.zRot = hold * rz;
            p.leftArm.xRot = hold * lx + aim * alx + blind * blx;
            p.leftArm.yRot = hold * ly + aim * aly + blind * bly;
            p.leftArm.zRot = hold * lz;
            p.head.xRot += blind * 0.35F;
        }
        float armed = 1F - f.arms()[AiPose.NO_GUN];
        if (lying > 0F && armed > 0F) {
            // lying with a gun: the arms reach along the ground (the body's up is forward when lying)
            float w = lying * armed;
            p.rightArm.xRot = Mth.lerp(w, p.rightArm.xRot, -PI + 0.15F);
            p.rightArm.yRot = Mth.lerp(w, p.rightArm.yRot, -0.1F);
            p.rightArm.zRot = Mth.lerp(w, p.rightArm.zRot, 0F);
            p.leftArm.xRot = Mth.lerp(w, p.leftArm.xRot, -PI + 0.25F);
            p.leftArm.yRot = Mth.lerp(w, p.leftArm.yRot, 0.45F);
            p.leftArm.zRot = Mth.lerp(w, p.leftArm.zRot, 0F);
        }

        // 3. vanilla's crouch (HumanoidModel.setupAnim, 26.2), by its weight
        float c = BotAnimator.smooth(f.crouch()) * upright;
        if (c > 0F) {
            p.body.xRot += 0.5F * c;
            // vanilla tips the arms with the body; arms aimed or held up keep their line
            p.rightArm.xRot += 0.4F * c * hold;
            p.leftArm.xRot += 0.4F * c * hold;
            p.rightLeg.z += 4F * c;
            p.leftLeg.z += 4F * c;
            p.head.y += 4.2F * c;
            p.body.y += 3.2F * c;
            p.rightArm.y += AiPoseGeometry.CROUCH_ARM_DROP_PX * c;
            p.leftArm.y += AiPoseGeometry.CROUCH_ARM_DROP_PX * c;
        }

        // 4. the action, and the one it cut short fading out under it
        for (BotAnimator.Act out : f.fading()) action(p, out, headX, headY, a, b);
        if (f.act() != null) action(p, f.act(), headX, headY, a, b);

        // 5. a hit's flinch (and one it cut short, going)
        for (BotAnimator.Hit hit : f.hits()) {
            BotClip h = BotClips.get(hit.clip());
            if (h != null) add(p, h, h.local(hit.sec()), hit.w() * upright, a, b);
        }

        // 6. the lean: what the server takes for the eye and the muzzle (AiPoseGeometry.leanPoint)
        float lean = f.lean() * upright;
        if (lean != 0F) {
            float roll = AiPoseGeometry.modelRollRad(lean), shift = AiPoseGeometry.modelShiftPx(lean);
            float hipY = AiPoseGeometry.modelHipY(AiPose.STAND); // the same crouching: 26.2 moves the legs back only
            float cos = Mth.cos(roll), sin = Mth.sin(roll);
            for (ModelPart part : new ModelPart[] { p.head, p.body, p.rightArm, p.leftArm }) {
                float x = part.x, dy = part.y - hipY;
                part.x = x * cos - dy * sin;
                part.y = hipY + x * sin + dy * cos;
                part.zRot += roll;
            }
            for (ModelPart part : new ModelPart[] { p.head, p.body, p.rightArm, p.leftArm, p.rightLeg, p.leftLeg })
                part.x += shift;
        }
    }

    private static void action(Parts p, BotAnimator.Act act, float headX, float headY, float[] a, float[] b) {
        float w = act.w();
        if (w <= 0F) return;
        switch (act.kind()) {
            case AiPose.RELOAD -> {
                // the gun down to the chest, the left hand to the magazine and back twice (out, in), the eyes on it
                float pull = Mth.sin(PI * ((act.u() * 2F) % 1F));
                turn(p.rightArm, -0.9F, -0.35F, 0F, w);
                turn(p.leftArm, -0.75F - 0.45F * pull, 0.65F, 0F, w);
                p.head.xRot = Mth.lerp(w, p.head.xRot, 0.45F);
            }
            case AiPose.SHOT -> {
                // the muzzle kicks up and settles
                float k = w * (float) Math.exp(-act.t() / 1.5F);
                p.rightArm.xRot -= 0.22F * k;
                p.leftArm.xRot -= 0.22F * k;
                p.body.xRot -= 0.04F * k;
                p.head.xRot -= 0.05F * k;
            }
            case AiPose.THROW -> {
                BotClip t = BotClips.get(BotClips.THROW);
                if (t != null) set(p, t, act.u() * t.length(), w, UPPER, a);
            }
            case AiPose.SIGNAL_COME -> {
                // KayKit waves with the right hand; the gun is there, so the left one waves, mirrored
                BotClip t = BotClips.get(BotClips.WAVE);
                if (t != null) setMirrored(p.leftArm, t, "right_arm", t.local(act.t() / 20F), w, a);
            }
            case AiPose.SIGNAL_POINT -> turn(p.leftArm, -PI / 2F + headX, headY, 0F, w);
            case AiPose.SIGNAL_HALT -> turn(p.leftArm, -PI + 0.15F, 0F, -0.12F, w);
            default -> {
            }
        }
    }

    private static final ThreadLocal<Quaternionf[]> Q = ThreadLocal.withInitial(() -> new Quaternionf[] { new Quaternionf(), new Quaternionf() });
    private static final ThreadLocal<Vector3f> E = ThreadLocal.withInitial(Vector3f::new);

    /**
     * Turns the part w of the way to (x, y, z), along the shortest turn (a slerp: Euler angles straight across can
     * swing the long way round near a quarter turn about Y), kept on the Euler branch nearest the straight blend so
     * the angles added after it (crouch, flinch) act as they would.
     */
    static void turn(ModelPart part, float x, float y, float z, float w) {
        if (w >= 1F) {
            part.setRotation(x, y, z);
            return;
        }
        Quaternionf[] q = Q.get();
        q[0].rotationZYX(part.zRot, part.yRot, part.xRot);
        q[1].rotationZYX(z, y, x);
        q[0].slerp(q[1], w);
        Vector3f e = q[0].getEulerAnglesZYX(E.get());
        float lx = Mth.lerp(w, part.xRot, x), ly = Mth.lerp(w, part.yRot, y), lz = Mth.lerp(w, part.zRot, z);
        // the same turn: (x, y, z) and (x + pi, pi - y, z + pi)
        float x1 = near(e.x, lx), y1 = near(e.y, ly), z1 = near(e.z, lz);
        float x2 = near(e.x + PI, lx), y2 = near(PI - e.y, ly), z2 = near(e.z + PI, lz);
        float d1 = Math.abs(x1 - lx) + Math.abs(y1 - ly) + Math.abs(z1 - lz);
        float d2 = Math.abs(x2 - lx) + Math.abs(y2 - ly) + Math.abs(z2 - lz);
        if (d1 <= d2) part.setRotation(x1, y1, z1);
        else part.setRotation(x2, y2, z2);
    }

    /** the angle equal to a (mod 2 pi) nearest to ref */
    static float near(float a, float ref) {
        return a + Mth.TWO_PI * Math.round((ref - a) / Mth.TWO_PI);
    }

    /** Adds the clip's change from its first frame (turns and pivots), weighted: on top of whatever is there. */
    static void add(Parts p, BotClip clip, float t, float w, float[] a, float[] b) {
        for (String bone : ALL) {
            ModelPart part = p.get(bone);
            if (clip.rotation(bone, t, a) && clip.rotation(bone, 0F, b)) {
                part.xRot += w * Mth.DEG_TO_RAD * (a[0] - b[0]);
                part.yRot += w * Mth.DEG_TO_RAD * (a[1] - b[1]);
                part.zRot += w * Mth.DEG_TO_RAD * (a[2] - b[2]);
            }
            if (clip.position(bone, t, a) && clip.position(bone, 0F, b)) {
                part.x += w * (a[0] - b[0]);
                part.y -= w * (a[1] - b[1]);
                part.z += w * (a[2] - b[2]);
            }
        }
    }

    /** Moves the given parts toward the clip's pose (from the default pose), weighted. */
    static void set(Parts p, BotClip clip, float t, float w, String[] bones, float[] a) {
        for (String bone : bones) {
            ModelPart part = p.get(bone);
            PartPose d = part.getInitialPose();
            if (clip.rotation(bone, t, a))
                turn(part, Mth.DEG_TO_RAD * a[0], Mth.DEG_TO_RAD * a[1], Mth.DEG_TO_RAD * a[2], w);
            if (clip.position(bone, t, a)) {
                part.x = Mth.lerp(w, part.x, d.x() + a[0]);
                part.y = Mth.lerp(w, part.y, d.y() - a[1]);
                part.z = Mth.lerp(w, part.z, d.z() + a[2]);
            }
        }
    }

    /** The other side's bone onto this part, mirrored left for right: turns about Y and Z flip, X moves flip. */
    static void setMirrored(ModelPart part, BotClip clip, String bone, float t, float w, float[] a) {
        PartPose d = part.getInitialPose();
        if (clip.rotation(bone, t, a))
            turn(part, Mth.DEG_TO_RAD * a[0], -Mth.DEG_TO_RAD * a[1], -Mth.DEG_TO_RAD * a[2], w);
        if (clip.position(bone, t, a)) {
            part.x = Mth.lerp(w, part.x, d.x() - a[0]);
            part.y = Mth.lerp(w, part.y, d.y() - a[1]);
            part.z = Mth.lerp(w, part.z, d.z() + a[2]);
        }
    }
}
