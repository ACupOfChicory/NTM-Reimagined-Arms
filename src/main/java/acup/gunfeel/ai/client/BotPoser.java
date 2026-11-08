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
 * <li>on the move, the legs from the clips (UAL Walk_Loop, Jog_Fwd_Loop, Sprint_Loop; KayKit Walking_Backwards,
 * Running_Strafe_Left/Right), by its speed and which way it goes from where it faces, in step with vanilla's swing;
 * the elbows with no gun;</li>
 * <li>the arms: as vanilla left them (HOLD, NO_GUN), aimed two-handed along the look (AIM), held up over the head
 * (BLIND, firing blind over cover), or along the ground when lying with a gun;</li>
 * <li>vanilla's crouch, by its weight;</li>
 * <li>the action: a reload (KayKit Ranged_2H_Reload), a kick of a shot, a throw (UAL OverhandThrow), a wave with the
 * free left arm (KayKit Waving, mirrored), a point, a raised hand to halt;</li>
 * <li>a hit's flinch, added on top (UAL Hit_Chest, Hit_Head; KayKit Hit_B when a blast throws it);</li>
 * <li>its fall dead (KayKit Death_A, the whole body: vanilla's tip onto its side is not drawn then);</li>
 * <li>the lean: the hips step aside and everything above them tips (AiPoseGeometry, the server's own numbers).</li>
 * </ol>
 * Each arm and leg is two parts (BotModel): the forearm and the shin are children of the upper arm and the thigh, turned
 * from them, so what turns an arm carries its forearm. The hitbox walks the same tree: nothing it would miss.
 */
public final class BotPoser {
    static final String[] UPPER = { "head", "body", "right_arm", "right_forearm", "left_arm", "left_forearm" };
    /** the reload's clip turns these */
    static final String[] RELOAD_BONES = { "head", "right_arm", "right_forearm", "left_arm", "left_forearm" };
    static final String[] ALL = { "head", "body", "right_arm", "right_forearm", "left_arm", "left_forearm", "right_leg",
            "right_shin", "left_leg", "left_shin" };
    private static final float PI = Mth.PI;

    private BotPoser() {
    }

    /**
     * The ten parts (BotModel), by the clip's bone names; head first. The forearms and the shins are their limb's
     * children, so their turns are from the limb above; a model not split (none: only the bots have them) has none,
     * and a clip's bones for them are left out.
     */
    public record Parts(ModelPart head, ModelPart body, ModelPart rightArm, ModelPart leftArm, ModelPart rightLeg,
            ModelPart leftLeg, @org.jspecify.annotations.Nullable ModelPart rightForearm,
            @org.jspecify.annotations.Nullable ModelPart leftForearm, @org.jspecify.annotations.Nullable ModelPart rightShin,
            @org.jspecify.annotations.Nullable ModelPart leftShin) {
        public Parts(ModelPart head, ModelPart body, ModelPart rightArm, ModelPart leftArm, ModelPart rightLeg,
                ModelPart leftLeg) {
            this(head, body, rightArm, leftArm, rightLeg, leftLeg, BotModel.lower(rightArm), BotModel.lower(leftArm),
                    BotModel.lower(rightLeg), BotModel.lower(leftLeg));
        }

        @org.jspecify.annotations.Nullable ModelPart get(String bone) {
            return switch (bone) {
                case "head" -> head;
                case "body" -> body;
                case "right_arm" -> rightArm;
                case "left_arm" -> leftArm;
                case "right_leg" -> rightLeg;
                case "left_leg" -> leftLeg;
                case "right_forearm" -> rightForearm;
                case "left_forearm" -> leftForearm;
                case "right_shin" -> rightShin;
                case "left_shin" -> leftShin;
                default -> null;
            };
        }
    }

    /** vanilla's walk: a leg's swing is cos(walkPos 0.6662): a full step of both legs this much walkPos */
    static final float CYCLE = Mth.TWO_PI / 0.6662F;
    /** the clips' legs come in from this speed to this, blocks a tick (vanilla's straight swing below) */
    static final float MOVE_FROM = 0.01F, MOVE_FULL = 0.05F;
    /** from a walk to a jog, from a jog to a sprint, blocks a tick (an AI mob walks at 0.19, runs at 0.33: BotStand) */
    static final float JOG_FROM = 0.20F, JOG_FULL = 0.26F, SPRINT_FROM = 0.27F, SPRINT_FULL = 0.33F;
    /** ticks for the reload's clip to come in over the arms */
    static final float RELOAD_IN = 8F;
    /** ticks for the fall dead to come in over the pose it had */
    static final float DEATH_IN = 2F;
    static final String[] LEGS = { "right_leg", "right_shin", "left_leg", "left_shin" };

    /** how far a knee bends as the leg swings forward walking, radians at full speed */
    static final float KNEE_WALK = 1.0F;
    /** an elbow's bend with no gun, radians, more as it walks */
    static final float ELBOW_FREE = 0.15F, ELBOW_WALK = 0.35F;

    /**
     * @param swim       the render state's swimAmount: lying (an AI mob's crawl) when above 0
     * @param walkSpeed  the render state's walkAnimationSpeed: the idle loop fades out as it walks
     * @param walkPos    the render state's walkAnimationPos: where vanilla's legs are in their swing
     */
    public static void apply(Parts p, BotAnimator.Frame f, float swim, float walkSpeed, float walkPos) {
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

        // on the move: the legs from the clips (crouching: vanilla's crouched legs, the clips' would lower the hips
        // and the eye the server keeps)
        float move = upright * (1F - BotAnimator.smooth(f.crouch()))
                * BotAnimator.smooth((f.speed() - MOVE_FROM) / (MOVE_FULL - MOVE_FROM));
        if (move > 0F) gait(p, f.speed(), f.heading(), walkPos, move, a);

        // the knees, slower than the clips: vanilla swings straight legs (xRot cos(pos 0.6662) 1.4 speed); a leg
        // swinging forward lifts its foot. The elbows: bent a little with no gun, more as it walks; with a gun the
        // hands stay where vanilla's straight arms put the gun (AiPoseGeometry's muzzle)
        float walk = Mth.clamp(walkSpeed, 0F, 1F) * upright;
        if (walk > 0F && move < 1F) {
            float swing = Mth.sin(walkPos * 0.6662F), k = KNEE_WALK * walk * (1F - move);
            if (p.rightShin != null) p.rightShin.xRot += k * Math.max(0F, swing);
            if (p.leftShin != null) p.leftShin.xRot += k * Math.max(0F, -swing);
        }
        float free = f.arms()[AiPose.NO_GUN] * upright;
        if (free > 0F) {
            float bend = free * (ELBOW_FREE + ELBOW_WALK * walk);
            if (p.rightForearm != null) p.rightForearm.xRot -= bend;
            if (p.leftForearm != null) p.leftForearm.xRot -= bend;
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

        // its fall dead, over everything
        BotClip death = f.deathSec() >= 0F ? BotClips.get(BotClips.DEATH) : null;
        float dead = 0F;
        if (death != null) {
            dead = upright * BotAnimator.smooth(f.deathSec() * 20F / DEATH_IN);
            if (dead > 0F) set(p, death, Math.min(f.deathSec(), death.length()), dead, ALL, a);
        }

        // 6. the lean: what the server takes for the eye and the muzzle (AiPoseGeometry.leanPoint); falling dead it
        // goes with the fall
        float lean = f.lean() * upright * (1F - dead);
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
                BotClip r = BotClips.get(BotClips.RELOAD);
                if (r != null) {
                    // KayKit's rifle reload, stretched to the reload's length (coming in over RELOAD_IN: BotAnimator):
                    // the arms and the head's turn only, as the reload without it: the body keeps its crouch and the
                    // head its place at the eye the server keeps
                    for (String bone : RELOAD_BONES) {
                        ModelPart part = p.get(bone);
                        if (part != null && r.rotation(bone, act.u() * r.length(), a))
                            turn(part, Mth.DEG_TO_RAD * a[0], Mth.DEG_TO_RAD * a[1], Mth.DEG_TO_RAD * a[2], w);
                    }
                    return;
                }
                // no clip: the gun down to the chest, the left hand to the magazine and back twice (out, in), the
                // eyes on it, the elbows bent: the gun in to the chest, the left hand up to it
                float pull = Mth.sin(PI * ((act.u() * 2F) % 1F));
                turn(p.rightArm, -0.6F, -0.35F, 0F, w);
                turn(p.leftArm, -0.45F - 0.35F * pull, 0.65F, 0F, w);
                if (p.rightForearm != null) turn(p.rightForearm, -0.7F, 0F, 0F, w);
                if (p.leftForearm != null) turn(p.leftForearm, -0.9F - 0.3F * pull, 0F, 0F, w);
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
                if (t != null) {
                    float tt = t.local(act.t() / 20F);
                    setMirrored(p.leftArm, t, "right_arm", tt, w, a);
                    if (p.leftForearm != null) setMirrored(p.leftForearm, t, "right_forearm", tt, w, a);
                }
            }
            case AiPose.SIGNAL_POINT -> turn(p.leftArm, -PI / 2F + headX, headY, 0F, w);
            case AiPose.SIGNAL_HALT -> {
                // the hand up, the palm out: the elbow a little bent
                turn(p.leftArm, -PI + 0.35F, 0F, -0.12F, w);
                if (p.leftForearm != null) turn(p.leftForearm, -0.35F, 0F, 0F, w);
            }
            default -> {
            }
        }
    }

    /** where in each clip the right leg is furthest back (vanilla's swing at walkPos 0): the clips step with vanilla */
    private static final java.util.Map<String, Float> PHASE = new java.util.concurrent.ConcurrentHashMap<>();

    static float phase(String name, BotClip c) {
        return PHASE.computeIfAbsent(name, k -> {
            float[] r = new float[3];
            float best = -Float.MAX_VALUE, at = 0F;
            for (int i = 0; i < 120; i++) {
                float t = c.length() * i / 120F;
                if (c.rotation("right_leg", t, r) && r[0] > best) {
                    best = r[0];
                    at = i / 120F;
                }
            }
            return at;
        });
    }

    /**
     * The legs on the move: forward a walk, a jog or a sprint by its speed, back, or sideways, blended by which way it
     * goes from where it faces; each clip as far through its step as vanilla's swing is (walkPos), so the feet keep
     * the pace the game moves it at. Moves the legs w of the way from vanilla's.
     */
    static void gait(Parts p, float speed, float heading, float walkPos, float w, float[] a) {
        float u = walkPos / CYCLE;
        float[][] acc = new float[LEGS.length][3];
        float total = 0F;
        String[] names = gaitClips(heading);
        float[] ws = gaitWeights(speed, heading);
        for (int i = 0; i < names.length; i++) {
            if (ws[i] <= 1e-4F) continue;
            BotClip clip = BotClips.get(names[i]);
            if (clip == null) continue;
            float t = ((u + phase(names[i], clip)) % 1F + 1F) % 1F * clip.length();
            for (int b = 0; b < LEGS.length; b++)
                if (clip.rotation(LEGS[b], t, a)) for (int k = 0; k < 3; k++) acc[b][k] += ws[i] * a[k];
            total += ws[i];
        }
        if (total <= 0F) return;
        for (int b = 0; b < LEGS.length; b++) {
            ModelPart part = p.get(LEGS[b]);
            if (part == null) continue;
            part.xRot = Mth.lerp(w, part.xRot, Mth.DEG_TO_RAD * acc[b][0] / total);
            part.yRot = Mth.lerp(w, part.yRot, Mth.DEG_TO_RAD * acc[b][1] / total);
            part.zRot = Mth.lerp(w, part.zRot, Mth.DEG_TO_RAD * acc[b][2] / total);
        }
    }

    /** the clips gait blends: walk, jog, sprint, back, and the strafe to the side it goes */
    public static String[] gaitClips(float heading) {
        return new String[] { BotClips.WALK, BotClips.JOG, BotClips.SPRINT, BotClips.BACK,
                Mth.sin(heading) > 0F ? BotClips.STRAFE_RIGHT : BotClips.STRAFE_LEFT };
    }

    /** each of gaitClips' weights at this speed and heading, summing to 1 */
    public static float[] gaitWeights(float speed, float heading) {
        float c = Mth.cos(heading), s = Mth.sin(heading);
        float ahead = Math.max(c, 0F), back = Math.max(-c, 0F), side = Math.abs(s), sum = ahead + back + side;
        float jog = BotAnimator.smooth((speed - JOG_FROM) / (JOG_FULL - JOG_FROM));
        float sprint = BotAnimator.smooth((speed - SPRINT_FROM) / (SPRINT_FULL - SPRINT_FROM));
        return new float[] { ahead / sum * (1F - jog), ahead / sum * jog * (1F - sprint), ahead / sum * jog * sprint, back / sum, side / sum };
    }

    /** |q0 . q1| below which the shortest turn gives way to the straight blend of the angles, and over how much */
    static final float FACING = 0.1F, FACING_BLEND = 0.25F;
    private static final ThreadLocal<Quaternionf[]> Q = ThreadLocal.withInitial(() -> new Quaternionf[] { new Quaternionf(), new Quaternionf(), new Quaternionf() });
    private static final ThreadLocal<Vector3f> E = ThreadLocal.withInitial(Vector3f::new);

    /**
     * Turns the part w of the way to (x, y, z), along the shortest turn (a slerp: Euler angles straight across can
     * swing the long way round near a quarter turn about Y; between two turns nearly half a turn apart, the angles
     * blended straight), kept on the Euler branch nearest the straight blend so the angles added after it (crouch,
     * flinch) act as they would.
     */
    static void turn(ModelPart part, float x, float y, float z, float w) {
        if (w >= 1F) {
            part.setRotation(x, y, z);
            return;
        }
        Quaternionf[] q = Q.get();
        float lx = Mth.lerp(w, part.xRot, x), ly = Mth.lerp(w, part.yRot, y), lz = Mth.lerp(w, part.zRot, z);
        q[0].rotationZYX(part.zRot, part.yRot, part.xRot);
        q[1].rotationZYX(z, y, x);
        q[2].rotationZYX(lz, ly, lx);
        // the shortest turn, but where the two turns come to face each other (a throw fading out over a mob lying
        // down) the shortest way flips to the other side: there it gives way, smoothly, to the angles blended
        // straight, which move on through it
        float k = BotAnimator.smooth((Math.abs(q[0].dot(q[1])) - FACING) / FACING_BLEND);
        q[0].slerp(q[1], w);
        if (k < 1F) {
            if (q[0].dot(q[2]) < 0F) q[0].set(-q[0].x, -q[0].y, -q[0].z, -q[0].w);
            q[0].set(k * q[0].x + (1F - k) * q[2].x, k * q[0].y + (1F - k) * q[2].y, k * q[0].z + (1F - k) * q[2].z,
                    k * q[0].w + (1F - k) * q[2].w).normalize();
        }
        Vector3f e = q[0].getEulerAnglesZYX(E.get());
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

    /**
     * Adds the clip's change from its first frame (turns and pivots), weighted: on top of whatever is there. The turn
     * is added as a turn (w of the turn from the first frame to this one, after the part's own), not as angles: a
     * clip's angles near a quarter turn about Y swing far for a small turn (the arms of UAL's Hit_Knockback), and
     * added as angles at a part weight they would give a turn the clip never makes.
     */
    static void add(Parts p, BotClip clip, float t, float w, float[] a, float[] b) {
        for (String bone : ALL) {
            ModelPart part = p.get(bone);
            if (part == null) continue;
            if (clip.rotation(bone, t, a) && clip.rotation(bone, 0F, b)) {
                Quaternionf[] q = Q.get();
                q[1].rotationZYX(Mth.DEG_TO_RAD * b[2], Mth.DEG_TO_RAD * b[1], Mth.DEG_TO_RAD * b[0]).conjugate();
                q[2].rotationZYX(Mth.DEG_TO_RAD * a[2], Mth.DEG_TO_RAD * a[1], Mth.DEG_TO_RAD * a[0]);
                q[1].mul(q[2]);
                q[2].identity().slerp(q[1], w);
                q[0].rotationZYX(part.zRot, part.yRot, part.xRot).mul(q[2]);
                // kept on the branch nearest the angles added straight (what the flinches did before, where it holds)
                float lx = part.xRot + w * Mth.DEG_TO_RAD * (a[0] - b[0]), ly = part.yRot + w * Mth.DEG_TO_RAD * (a[1] - b[1]),
                        lz = part.zRot + w * Mth.DEG_TO_RAD * (a[2] - b[2]);
                Vector3f e = q[0].getEulerAnglesZYX(E.get());
                float x1 = near(e.x, lx), y1 = near(e.y, ly), z1 = near(e.z, lz);
                float x2 = near(e.x + PI, lx), y2 = near(PI - e.y, ly), z2 = near(e.z + PI, lz);
                if (Math.abs(x1 - lx) + Math.abs(y1 - ly) + Math.abs(z1 - lz) <= Math.abs(x2 - lx) + Math.abs(y2 - ly) + Math.abs(z2 - lz))
                    part.setRotation(x1, y1, z1);
                else part.setRotation(x2, y2, z2);
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
            if (part == null) continue;
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
