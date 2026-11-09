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
 * <li>lying on the move, a crawl on the belly (no clip: {@link #crawl});</li>
 * <li>vanilla's crouch, by its weight;</li>
 * <li>the action: a reload (KayKit Ranged_2H_Reload), a kick of a shot, a throw (UAL OverhandThrow), a wave with the
 * free left arm (KayKit Waving, mirrored), a point, a raised hand to halt, a slide into cover (UAL Slide_Start and
 * Slide_Exit) and a forward roll (UAL Roll), the whole body;</li>
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
    private static final float PI = Mth.PI, PI_HALF = Mth.HALF_PI;

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
    /**
     * from this share of the fall dead on, it settles: the clip (KayKit Death_A, put on our rig) ends with the trunk
     * 12 degrees short of flat and the knees folded down, so it lies on its feet with its back half a block up
     */
    static final float SETTLE_FROM = 0.5F;
    /** lying on its back: the trunk's and the thighs' turn */
    static final float FLAT = -PI_HALF;
    /** where the feet stand in the model, pixels down from its pivot (vanilla's: 24) */
    static final float GROUND = 24F;
    static final String[] LEGS = { "right_leg", "right_shin", "left_leg", "left_shin" };

    /** vanilla's swing at most, radians (its own: 1.4 at a full walk) */
    static final float SWING_MOST = 0.5F;
    /**
     * a leg stands till its step's swing forward begins (the sine of its phase at nought), and is left as it was from
     * this far into it
     */
    static final float PLANT_SWING = 0.5F;
    /** a knee bends this far at most, radians */
    static final float KNEE_MOST = 2.2F;
    /** under this walk (vanilla's walk speed) both feet stand, as at a stand */
    static final float PLANT_WALK = 0.25F;

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
        // vanilla's swing (1.4 walk radians at most) no wider than SWING_MOST: crouched it is the legs' only step,
        // and a thigh swung near level hangs its foot in the air
        float wide = 1.4F * Mth.clamp(walkSpeed, 0F, 1F);
        if (wide > SWING_MOST) {
            float narrow = Mth.lerp(upright, 1F, SWING_MOST / wide);
            p.rightLeg.xRot *= narrow;
            p.leftLeg.xRot *= narrow;
        }
        float crawl = crawlWeight(lying, f.speed());
        if (crawl > 0F) crawl(p, walkPos, crawl, armed);

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
        // the foot it stands on, on the ground (walking back, the leg going forward)
        plant(p, upright * (1F - crawl), walkPos, move * gaitWeights(f.speed(), f.heading())[3], walk);
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
            if (dead > 0F) {
                set(p, death, Math.min(f.deathSec(), death.length()), dead, ALL, a);
                settle(p, dead * BotAnimator.smooth((f.deathSec() - SETTLE_FROM * death.length()) / ((1F - SETTLE_FROM) * death.length())));
                down(p, dead);
            }
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
            case AiPose.SLIDE, AiPose.ROLL -> {
                // the whole body, the clip squeezed into the action's ticks (UAL Slide_Start and Slide_Exit, UAL Roll):
                // only now may the head leave the eye the server keeps (pose-contract §4); it fades into the crouch
                BotClip c = BotClips.get(act.kind() == AiPose.SLIDE ? BotClips.SLIDE : BotClips.ROLL);
                if (c != null) set(p, c, act.u() * c.length(), w, ALL, a);
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

    /** the crawl comes in from MOVE_FROM to this speed, blocks a tick (an AI mob crawls at 0.06: AiProne.CRAWL_SPEED) */
    static final float CRAWL_FULL = 0.03F;
    /** blocks gone in a crawl's full cycle (each leg drawn up once) */
    static final float CRAWL_STRIDE = 1.2F;
    /**
     * a leg drawn up: the thigh out to its side, the knee folded back so the shin lies along the body; radians. And
     * how far the arms on the gun swing aside with each pull
     */
    static final float CRAWL_HIP = 1.3F, CRAWL_KNEE = 1.3F, CRAWL_ARMS = 0.12F;

    /** how much of the crawl shows: lying (the swim amount) and moving */
    public static float crawlWeight(float lying, float speed) {
        return Mth.clamp(lying, 0F, 1F) * BotAnimator.smooth((speed - MOVE_FROM) / (CRAWL_FULL - MOVE_FROM));
    }

    /**
     * Lying on the move: a crawl on the belly, each leg in turn drawn up along the ground, its knee out to the side,
     * and the arms on the gun swinging aside with each pull (with no gun vanilla's crawl moves them; with one vanilla's
     * swim stroke is not run on a bot, BotAnimation.noSwim). No clip: KayKit's
     * Crawling is on hands and knees, its head 12 px up, over the eye the server keeps lying (0.4 blocks); here the
     * head and the hips stay where they lie. Lying, the model's x-y plane is the ground: a turn about z sweeps a limb
     * along it (+ takes a right limb out). Moves the parts w of the way from vanilla's crawl (its legs kick up and down).
     */
    static void crawl(Parts p, float walkPos, float w, float armed) {
        // its walk goes on 4 a block below a block a tick (BotAnimator.move: the swing's speed is 4 times the step)
        float s = Mth.sin(walkPos / (4F * CRAWL_STRIDE) * Mth.TWO_PI);
        crawlLeg(p.rightLeg, p.rightShin, Math.max(s, 0F), 1F, w);
        crawlLeg(p.leftLeg, p.leftShin, Math.max(-s, 0F), -1F, w);
        float arms = CRAWL_ARMS * s * w * armed;
        p.rightArm.zRot += arms;
        p.leftArm.zRot += arms;
    }

    private static void crawlLeg(ModelPart thigh, @org.jspecify.annotations.Nullable ModelPart shin, float drawn, float side, float w) {
        thigh.xRot = Mth.lerp(w, thigh.xRot, 0F);
        thigh.yRot = Mth.lerp(w, thigh.yRot, 0F);
        thigh.zRot = Mth.lerp(w, thigh.zRot, side * (0.08F + CRAWL_HIP * drawn));
        if (shin == null) return;
        shin.xRot = Mth.lerp(w, shin.xRot, 0F);
        shin.zRot = Mth.lerp(w, shin.zRot, -side * CRAWL_KNEE * drawn);
    }

    /**
     * where in each clip (a share of its length) to be at walkPos 0: its legs pass each other where vanilla's do, a
     * quarter of a step on (cos(walkPos 0.6662) at nought, the right leg going forward), so the clips and vanilla's
     * swing stand together where a stop ends its step (BotAnimator.toRest). Its legs pass where the right leg's xRot
     * comes down through the left's, the pass nearest a quarter on from the right leg furthest back.
     */
    private static final java.util.Map<String, Float> PHASE = new java.util.concurrent.ConcurrentHashMap<>();

    static float phase(String name, BotClip c) {
        return PHASE.computeIfAbsent(name, k -> {
            int n = 240;
            float[] r = new float[3], l = new float[3], d = new float[n];
            float best = -Float.MAX_VALUE, back = 0F;
            for (int i = 0; i < n; i++) {
                float t = c.length() * i / n;
                if (!c.rotation("right_leg", t, r)) return 0F;
                if (!c.rotation("left_leg", t, l)) l[0] = 0F;
                d[i] = r[0] - l[0];
                if (r[0] > best) {
                    best = r[0];
                    back = i / (float) n;
                }
            }
            float pass = back + 0.25F, near = Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                float a = d[i], b = d[(i + 1) % n];
                if (a <= 0F || b > 0F) continue;
                float at = (i + a / (a - b)) / n;
                float off = Math.abs(((at - back - 0.25F) % 1F + 1.5F) % 1F - 0.5F);
                if (off < near) {
                    near = off;
                    pass = at;
                }
            }
            return ((pass - 0.25F) % 1F + 1F) % 1F;
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

    /**
     * The foot it stands on, on the ground, w of the way: its shin is turned at the knee to hang straight down (a
     * thigh ahead) or to go on straight from the thigh (one behind: a knee bends only back), whatever the clips and
     * vanilla's swing gave it; its foot is then under its knee or the hip, a pixel or so up at most for the thigh's
     * swing, where blended clips and slow steps left it hanging. Which foot by the step's phase, the same for the
     * clips and vanilla's swing (phase): the right leg swings forward from walkPos 0 to half a step, and stands from
     * there on; walking back (back, the back clip's share) the other way round. Both stand where the legs are
     * furthest apart, half back and half aside, and standing still (walk, vanilla's walk speed, near nought); the one
     * in its step is left as it was.
     */
    static void plant(Parts p, float w, float walkPos, float back, float walk) {
        if (w <= 0F || p.rightShin == null || p.leftShin == null) return;
        // walking back the legs step the other way round; half back, half aside (the two clips' steps at odds) both
        // stand
        float swing = Mth.sin(walkPos * 0.6662F) * (1F - 2F * back), stepping = BotAnimator.smooth(walk / PLANT_WALK);
        float right = BotAnimator.smooth((PLANT_SWING - swing) / PLANT_SWING), left = BotAnimator.smooth((PLANT_SWING + swing) / PLANT_SWING);
        plantLeg(p.rightLeg, p.rightShin, w * Mth.lerp(stepping, 1F, right));
        plantLeg(p.leftLeg, p.leftShin, w * Mth.lerp(stepping, 1F, left));
    }

    private static final ThreadLocal<org.joml.Matrix3f> M = ThreadLocal.withInitial(org.joml.Matrix3f::new);

    private static void plantLeg(ModelPart thigh, ModelPart shin, float w) {
        if (w <= 0F) return;
        org.joml.Matrix3f t = M.get().rotationZYX(thigh.zRot, thigh.yRot, thigh.xRot);
        // the shin straight in the thigh's turn, then bent k at the knee: (0, cos k, sin k); most down where
        // m11 cos k + m21 sin k is most. With the knee's plane near level (a thigh swung up or twisted) no k hangs
        // it down, and it is left as it was
        float down = (float) Math.hypot(t.m11(), t.m21());
        w *= BotAnimator.smooth((down - 0.2F) / 0.3F);
        if (w <= 0F) return;
        float k = Mth.clamp((float) Math.atan2(t.m21(), t.m11()), 0F, KNEE_MOST);
        shin.xRot = Mth.lerp(w, shin.xRot, k);
        shin.yRot = Mth.lerp(w, shin.yRot, 0F);
        shin.zRot = Mth.lerp(w, shin.zRot, 0F);
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

    private static final ThreadLocal<Quaternionf[]> Q = ThreadLocal.withInitial(() -> new Quaternionf[] { new Quaternionf(), new Quaternionf(), new Quaternionf(),
            new Quaternionf(), new Quaternionf() });
    private static final ThreadLocal<Vector3f> E = ThreadLocal.withInitial(Vector3f::new);

    /**
     * Turns the part w of the way to (x, y, z) along a turn that keeps to the side the angles blended straight go (a
     * slerp, not the angles themselves: Euler angles straight across can swing far for a small turn near a quarter
     * turn about Y), kept on the Euler branch nearest the straight blend so the angles added after it (crouch,
     * flinch) act as they would. Not the shortest turn: where the two poses are near half a turn apart (a slide's leg
     * raised high over a leg swinging back on the walk, a throw fading out over a mob lying down) the shortest way flips
     * from one side to the other as the pose moves, and the part with it; the straight blend's side does not.
     */
    static void turn(ModelPart part, float x, float y, float z, float w) {
        if (w >= 1F) {
            part.setRotation(x, y, z);
            return;
        }
        Quaternionf[] q = Q.get();
        float fx = part.xRot, fy = part.yRot, fz = part.zRot;
        float lx = Mth.lerp(w, fx, x), ly = Mth.lerp(w, fy, y), lz = Mth.lerp(w, fz, z);
        q[0].rotationZYX(fz, fy, fx);
        q[1].rotationZYX(z, y, x);
        // the target's sign: the straight blend's path followed through its quarters, each the side of the one before
        // (a quarter is under half a turn unless the whole is over two full turns)
        q[3].set(q[0]);
        for (int k = 1; k <= 3; k++) {
            float f = k / 4F;
            q[4].rotationZYX(Mth.lerp(f, fz, z), Mth.lerp(f, fy, y), Mth.lerp(f, fx, x));
            if (q[3].dot(q[4]) < 0F) q[4].set(-q[4].x, -q[4].y, -q[4].z, -q[4].w);
            q[3].set(q[4]);
        }
        if (q[3].dot(q[1]) < 0F) q[1].set(-q[1].x, -q[1].y, -q[1].z, -q[1].w);
        slerpAlong(q[0], q[1], w);
        setNear(part, q[0], lx, ly, lz);
    }

    /** Sets the part to the turn q, in the angles nearest (lx, ly, lz): (x, y, z) or the same turn (x + pi, pi - y, z + pi). */
    static void setNear(ModelPart part, Quaternionf q, float lx, float ly, float lz) {
        Vector3f e = q.getEulerAnglesZYX(E.get());
        float x1 = near(e.x, lx), y1 = near(e.y, ly), z1 = near(e.z, lz);
        float x2 = near(e.x + PI, lx), y2 = near(PI - e.y, ly), z2 = near(e.z + PI, lz);
        if (Math.abs(x1 - lx) + Math.abs(y1 - ly) + Math.abs(z1 - lz) <= Math.abs(x2 - lx) + Math.abs(y2 - ly) + Math.abs(z2 - lz))
            part.setRotation(x1, y1, z1);
        else part.setRotation(x2, y2, z2);
    }

    /** a slerp from a to b as they are (no flip to the shorter way), w of the way, into a */
    static void slerpAlong(Quaternionf a, Quaternionf b, float w) {
        float cos = Mth.clamp(a.dot(b), -1F, 1F), ka, kb;
        float theta = (float) Math.acos(cos), sin = Mth.sin(theta);
        if (Math.abs(sin) < 1e-4F) {
            ka = 1F - w;
            kb = w;
        } else {
            ka = Mth.sin((1F - w) * theta) / sin;
            kb = Mth.sin(w * theta) / sin;
        }
        a.set(ka * a.x + kb * b.x, ka * a.y + kb * b.y, ka * a.z + kb * b.z, ka * a.w + kb * b.w).normalize();
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
                setNear(part, q[0], lx, ly, lz);
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

    /**
     * Settles a body fallen dead, by s: the trunk flat on its back with no roll (the head and the arms turned with it
     * about the hips, as one), the thighs flat and the knees straight, the arms down along it a little out on the
     * ground, the head face up and a little aside, on the back of the skull: the clip's own end does none of it.
     */
    static void settle(Parts p, float s) {
        if (s <= 0F) return;
        for (ModelPart shin : new ModelPart[] { p.rightShin, p.leftShin })
            if (shin != null) shin.xRot = Mth.lerp(s, shin.xRot, 0F);
        turn(p.rightLeg, FLAT, p.rightLeg.yRot, p.rightLeg.zRot, s);
        turn(p.leftLeg, FLAT, p.leftLeg.yRot, p.leftLeg.zRot, s);
        org.joml.Matrix4f m = M4.get();
        // hips is the scratch V: read it at once, before V is used again
        Vector3f hips = frame(p.body, m).transformPosition(V.get().set(0F, 12F, 0F));
        float hx = hips.x, hy = hips.y, hz = hips.z;
        Quaternionf[] q = Q.get();
        // the turn from the trunk as it is to flat, s of it
        q[0].rotationZYX(p.body.zRot, p.body.yRot, p.body.xRot).conjugate();
        q[1].rotationZYX(0F, p.body.yRot, FLAT).mul(q[0]);
        q[3].identity().slerp(q[1], s);
        for (ModelPart part : new ModelPart[] { p.head, p.body, p.rightArm, p.leftArm }) {
            Vector3f v = q[3].transform(V.get().set(part.x - hx, part.y - hy, part.z - hz));
            part.setPos(hx + v.x, hy + v.y, hz + v.z);
            q[4].set(q[3]).mul(q[2].rotationZYX(part.zRot, part.yRot, part.xRot));
            setNear(part, q[4], part.xRot, part.yRot, part.zRot);
        }
        turn(p.rightArm, FLAT, ARM_OUT, 0F, s);
        turn(p.leftArm, FLAT, -ARM_OUT, 0F, s);
        for (ModelPart fore : new ModelPart[] { p.rightForearm, p.leftForearm })
            if (fore != null) fore.xRot = Mth.lerp(s, fore.xRot, -ELBOW_LYING);
        // the head face up with the trunk, turned a little aside the way the clip turned it; 8 px through where the
        // trunk is 4, it would hold the back up off the ground: it rests on the back of the skull
        turn(p.head, FLAT, 0F, Math.copySign(HEAD_ASIDE, p.head.zRot), s);
        float head = lowest(frame(p.head, m), -4F, -8F, -4F, 8F, 8F, 8F), trunk = lowest(frame(p.body, m), -4F, 0F, -2F, 8F, 12F, 4F);
        if (head > trunk) p.head.y -= s * (head - trunk);
    }

    /** lying dead: the arms out from the trunk on the ground, the elbows bent a little, radians */
    static final float ARM_OUT = 0.3F, ELBOW_LYING = 0.25F;
    /** lying dead: the head turned aside, radians */
    static final float HEAD_ASIDE = 0.3F;

    /**
     * Puts a body fallen dead on the ground, by w: all of it moved up or down so its lowest point is where the feet
     * stand (vanilla's boxes: the head 8 px, the trunk 12 by 4, each limb's half 6, a limb not split 12). Armour
     * grows the boxes by 0.5 to 1 px: it goes that far into the ground.
     */
    static void down(Parts p, float w) {
        if (w <= 0F) return;
        float low = -Float.MAX_VALUE;
        org.joml.Matrix4f m = M4.get();
        low = Math.max(low, lowest(frame(p.head, m), -4F, -8F, -4F, 8F, 8F, 8F));
        low = Math.max(low, lowest(frame(p.body, m), -4F, 0F, -2F, 8F, 12F, 4F));
        low = Math.max(low, limb(p.rightArm, p.rightForearm, -3F, -2F, m));
        low = Math.max(low, limb(p.leftArm, p.leftForearm, -1F, -2F, m));
        low = Math.max(low, limb(p.rightLeg, p.rightShin, -2F, 0F, m));
        low = Math.max(low, limb(p.leftLeg, p.leftShin, -2F, 0F, m));
        float by = w * (GROUND - low);
        for (ModelPart part : new ModelPart[] { p.head, p.body, p.rightArm, p.leftArm, p.rightLeg, p.leftLeg }) part.y += by;
    }

    private static float limb(ModelPart upper, @org.jspecify.annotations.Nullable ModelPart lower, float x0, float y0, org.joml.Matrix4f m) {
        frame(upper, m);
        if (lower == null) return lowest(m, x0, y0, -2F, 4F, 12F, 4F);
        float low = lowest(m, x0, y0, -2F, 4F, BotModel.HALF, 4F);
        m.translate(lower.x, lower.y, lower.z).rotateZYX(lower.zRot, lower.yRot, lower.xRot);
        return Math.max(low, lowest(m, -2F, 0F, -2F, 4F, BotModel.HALF, 4F));
    }

    private static org.joml.Matrix4f frame(ModelPart part, org.joml.Matrix4f m) {
        return m.translation(part.x, part.y, part.z).rotateZYX(part.zRot, part.yRot, part.xRot);
    }

    /** the box's lowest corner in the model (its largest y) */
    private static float lowest(org.joml.Matrix4f m, float x0, float y0, float z0, float w, float h, float d) {
        float low = -Float.MAX_VALUE;
        Vector3f v = V.get();
        for (int i = 0; i < 8; i++) {
            m.transformPosition(v.set(x0 + (i & 1) * w, y0 + (i >> 1 & 1) * h, z0 + (i >> 2 & 1) * d));
            low = Math.max(low, v.y);
        }
        return low;
    }

    private static final ThreadLocal<org.joml.Matrix4f> M4 = ThreadLocal.withInitial(org.joml.Matrix4f::new);
    private static final ThreadLocal<Vector3f> V = ThreadLocal.withInitial(Vector3f::new);

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
