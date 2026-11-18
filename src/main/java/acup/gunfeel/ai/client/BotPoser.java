package acup.gunfeel.ai.client;

import acup.gunfeel.ai.AiPose;
import acup.gunfeel.ai.AiPoseGeometry;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.util.Mth;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Puts an AI mob's HumanoidModel parts where {@link BotAnimator.Frame} says, after vanilla's setupAnim (walk, look,
 * the item's arm pose, the crawl) has run. In order:
 * <ol>
 * <li>the idle loop's breathing, added on top (UAL Idle_Loop, its change from its first frame);</li>
 * <li>on the move, the legs from the clips (UAL Walk_Loop, Jog_Fwd_Loop, Sprint_Loop; KayKit Walking_Backwards,
 * Running_Strafe_Left/Right), by its speed and which way it goes from where it faces, in step with vanilla's swing;
 * the elbows with no gun;</li>
 * <li>the arms: as vanilla left them (HOLD, NO_GUN), aimed two-handed along the look (AIM), held up over the head
 * (BLIND, firing blind over cover), the gun close at the chest with its muzzle up (STACK, beside a doorway), or
 * along the ground when lying with a gun;</li>
 * <li>lying on the move, a crawl on the belly (no clip: {@link #crawl});</li>
 * <li>in a fight at a stand, a foot ahead and a foot back; in the air, the legs of a jump (UAL Jump_Loop);</li>
 * <li>the crouch, by its weight: the head, the trunk's top and the shoulders where vanilla's crouch has them (the eye
 * the server keeps), under them down on a knee at a stand (as UAL Fixing_Kneeling kneels) and a walk bent low on the
 * move (KayKit Crouching), their legs fitted to that head (tools/quaternius fit.py);</li>
 * <li>the action: a reload (KayKit Ranged_2H_Reload), a kick of a shot, a throw (UAL OverhandThrow), a wave with the
 * free left arm (KayKit Waving, mirrored), a point, a raised hand to halt, a door pushed open with the left hand (UAL
 * Interact), a slide into cover (UAL Slide_Start and Slide_Exit) and a forward roll (UAL Roll), the whole body;</li>
 * <li>a hit's flinch, added on top (UAL Hit_Chest, Hit_Head, Quaternius SWAT HitRecieve and HitRecieve_2 by turns;
 * KayKit Hit_B when a blast throws it);</li>
 * <li>its fall dead (KayKit Death_A, the whole body: vanilla's tip onto its side is not drawn then);</li>
 * <li>the lean: the hips step aside and everything above them tips (AiPoseGeometry, the server's own numbers);</li>
 * <li>standing, each foot where it stands on the floor (BotAnimator.feet): the leg reaches it, a step when it must.</li>
 * </ol>
 * Each arm and leg is two parts (BotModel): the forearm and the shin are children of the upper arm and the thigh, turned
 * from them, so what turns an arm carries its forearm. The hitbox walks the same tree: nothing it would miss.
 */
public final class BotPoser {
    static final String[] UPPER = { "head", "body", "right_arm", "right_forearm", "left_arm", "left_forearm" };
    /**
     * the reload's clip turns these: the arms, not the head (eyes on the threat, as soldiers are taught: the head keeps
     * its aim, and its middle the eye the server sees and shoots from; KayKit's look down at the magazine took it up
     * to 8.6 cm off)
     */
    static final String[] RELOAD_BONES = { "right_arm", "right_forearm", "left_arm", "left_forearm" };
    /**
     * a throw recorded from a person turns these: the trunk and the arms; the head keeps its aim at the one the grenade
     * goes to (and its middle the server's eye), as a reload's
     */
    static final String[] THROW_BONES = { "body", "right_arm", "right_forearm", "left_arm", "left_forearm" };
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
    /**
     * from a walk to a jog, from a jog to a sprint, blocks a tick. An AI mob walks at 0.19, marches at 0.27 (a jog) and
     * runs at 0.32 (a sprint): the paces' modifiers (AiSoldier) go twice into the game's push. The jog is whole under
     * the march and the sprint goes from half way between the march and the run, so a pace's speed going up and down
     * a little does not swing the legs from one clip to the other.
     */
    static final float JOG_FROM = 0.20F, JOG_FULL = 0.24F, SPRINT_FROM = 0.29F, SPRINT_FULL = 0.305F;
    /**
     * the run's legs (Config.botRunGait) come in from a walk's speed to a march's, whole at a march and a run, where
     * vanilla's pace goes no faster (4 times the step, a block a tick at most: from 0.25 blocks a tick): tempo slows the
     * steps to this share (a step of both legs in 0.6 s, the sprint clip's own 0.67 s; vanilla's 0.47), stride swings
     * the thighs this much wider about the run clips' middle (the jog's and the sprint's thighs go from -79 and -73 to
     * 41 degrees: about -17)
     */
    static final float RUN_FROM = 0.22F, RUN_FULL = 0.26F, TEMPO_RUN = 0.78F, STRIDE_RUN = 1.3F, STRIDE_MIDDLE = -17F;

    /**
     * going ahead under the walk's clip put in (BotGaitOverride.stroll: the mob's own runs from people, made for 3 to 4
     * blocks a second), a walk's clip in its place: whole up to this speed, blocks a tick, gone from this. A creep
     * (AiSoldier.CREEP, 0.09) walks, a walk (0.19) runs as people do at its speed
     */
    static final float STROLL_FROM = 0.10F, STROLL_FULL = 0.15F;

    /** the stroll's share of the walk's at this speed, 1 .. 0 */
    static float strolling(float speed) {
        return 1F - BotAnimator.smooth((speed - STROLL_FROM) / (STROLL_FULL - STROLL_FROM));
    }

    /** how far the run's legs are in at this speed, 0 .. 1 */
    static float running(float speed) {
        return BotAnimator.smooth((speed - RUN_FROM) / (RUN_FULL - RUN_FROM));
    }

    /**
     * The legs' pace (walkPos a tick) with a stand's gait clips in place of the mod's ({@code o}, BotGaitOverride;
     * {@code own} the mod's own pace): each clip with its own pace (blocks a second) goes as far through its step as
     * the mob goes along (speed, blocks a tick), so its foot down stands
     */
    static float pace(float own, float speed, float heading, @Nullable BotGaitOverride o) {
        return pace(own, speed, gaitOf(speed), heading, o);
    }

    /** ... at a gait drawn of its own (Frame.gait) */
    static float pace(float own, float speed, float gait, float heading, @Nullable BotGaitOverride o) {
        return pace(own, speed, gait, heading, 0F, o);
    }

    /** ... crouched this far (0 .. 1): the walk bent low's clip (BotGaitOverride.crouch) in place of them all */
    static float pace(float own, float speed, float gait, float heading, float crouch, @Nullable BotGaitOverride o) {
        if (o == null) return own;
        BotGaitOverride.Way back = o.back(), side = o.side(heading), low = o.crouch(), stroll = o.walk() == null ? null : o.stroll();
        String[] names = { o.walk(), o.jog(), o.sprint(), back == null ? null : back.clip(), side == null ? null : side.clip(),
                stroll == null ? null : stroll.clip() };
        float[] at = { o.walkSpeed(), o.jogSpeed(), o.sprintSpeed(), back == null ? 0F : back.speed(), side == null ? 0F : side.speed(),
                stroll == null ? 0F : stroll.speed() }, ws = strolled(gaitShares(gait, heading), stroll == null ? 0F : strolling(speed));
        float out = own;
        for (int i = 0; i < names.length; i++) {
            BotClip clip = names[i] == null || at[i] <= 0F || ws[i] <= 0F ? null : BotClips.get(names[i]);
            if (clip == null || clip.length() <= 0F) continue;
            // a step of the clip a cycle: it goes at[i] * its length blocks
            out += ws[i] * (CYCLE * speed / (at[i] * clip.length()) - own);
        }
        // crouched, the walk bent low's (played backwards going back, as fast): the one put in, or the mod's at its own
        float c = BotAnimator.smooth(crouch);
        if (c <= 0F) return out;
        BotClip bent = low == null || low.speed() <= 0F ? null : BotClips.get(low.clip());
        float bentPace = bent != null && bent.length() > 0F ? CYCLE * speed / (low.speed() * bent.length()) : own;
        return out + c * (bentPace - out);
    }

    /** the share of vanilla's pace the steps go at (Config.botRunGait tempo), 1 at a walk */
    static float tempo(float speed) {
        return "tempo".equals(acup.gunfeel.Config.botRunGait) ? 1F - (1F - TEMPO_RUN) * running(speed) : 1F;
    }

    /** how much wider the thighs swing (Config.botRunGait stride), 1 at a walk */
    static float stride(float speed) {
        return "stride".equals(acup.gunfeel.Config.botRunGait) ? 1F + (STRIDE_RUN - 1F) * running(speed) : 1F;
    }

    /** ticks for the reload's clip to come in over the arms */
    static final float RELOAD_IN = 8F;
    /**
     * the gun held, by the arms' turns (x, y, z) and the forearms' bend, right then left: calm, lowered in front of
     * it, the right hand at the hip, the left under the barrel; on the run, across the chest, the right hand at the
     * chest, the left on the handguard (worked out for the hands' places: notes, c7 anims 2026-11-10)
     */
    static final float[] CARRY = { -0.2F, -0.7F, 0F, -0.9F, -0.8F, 0.65F, 0F, -0.05F },
            CHEST = { -0.25F, -0.7F, 0F, -1.2F, -1.1F, 0.45F, 0F, 0F };
    /**
     * ... and beside a doorway (STACK), close at the chest with its muzzle up and forward: the right hand in front of
     * the right shoulder, the forearm (the gun's line) raised 37 degrees over the level (as steep as the short upper
     * arm lets it be with the hand below the face), the left hand 3 px up the gun
     */
    static final float[] READY = { 0.05F, -0.55F, -0.44F, -2.09F, -1.55F, 0.88F, 0.13F, -0.15F };
    /**
     * in a fight at a stand, the thighs' turn: the left foot ahead (its knee bent, the shin hung under it), the right
     * leg straight back, radians
     */
    static final float STANCE_AHEAD = 0.3F, STANCE_BACK = 0.25F;
    /**
     * crouched on the move, the walk bent low (one clip, a walk ahead) goes from played on to played backwards, stepping
     * back, from 100 to 120 degrees off ahead (the cosines -0.17 .. -0.5): a step aside stays played on, as half of each
     * (the two at odds) would hold the legs nearly still
     */
    static final float CROUCH_BACK_FROM = -0.17F, CROUCH_BACK_FULL = -0.5F;
    /** off only for the legs' replay "before": crouched, the walk bent low is played on whichever way it goes */
    static boolean crouchBack = true;
    /** vanilla's crouch leans the trunk this far, radians, and its arms with it by this much of the lean */
    static final float CROUCH_LEAN = 0.5F, CROUCH_ARMS = 0.8F;
    static final String[] LEFT_ARM = { "left_arm", "left_forearm" };
    /** the push of a door, seconds of its clip (UAL Interact: the left hand out from 0.5 to 1 s): from, and how long */
    static final float DOOR_FROM = 0.2F, DOOR_FOR = 1.2F;
    /** a jog's share of the run's pose (a sprint's is all of it) */
    static final float JOG_RUN = 0.6F;
    /**
     * the trunk forward and the knees' bend (both thighs forward, each shin as it was), radians: on the run, walking
     * in a fight. Walking, no bend: with the head within 3 cm of the eye, a bend that shows (0.1 rad and more) lifts
     * the foot it comes down on over a pixel
     */
    static final float RUN_LEAN = 0.35F, RUN_BEND = 0.1F, TAC_LEAN = 0.12F, TAC_BEND = 0F;
    /** the most the bent knees take it all down, pixels (2.8 cm: pose-contract's head within 3 cm of the eye) */
    static final float DOWN_MOST = 0.45F;

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
        apply(p, f, swim, walkSpeed, walkPos, null);
    }

    /**
     * @param carry where the rifle goes through a throw recorded from a person (left as it is with no such throw):
     *              {@link #carried}
     */
    public static void apply(Parts p, BotAnimator.Frame f, float swim, float walkSpeed, float walkPos, @Nullable Carry carry) {
        float[] a = new float[3], b = new float[3];
        float lying = Mth.clamp(swim, 0F, 1F);
        float upright = 1F - lying;

        // 1. breathing
        float idleW = upright * (1F - Mth.clamp(walkSpeed * 4F, 0F, 1F));
        BotClip idle = BotClips.get(BotClips.IDLE);
        if (idle != null && idleW > 0F) add(p, idle, idle.local(f.idleSec()), idleW, a, b);

        // 2. the arms
        float headX = p.head.xRot, headY = p.head.yRot;
        float stack = f.arm(AiPose.STACK), plain = f.arm(AiPose.HOLD) + f.arm(AiPose.NO_GUN), hold = plain + stack;
        float aim = f.arm(AiPose.AIM), blind = f.arm(AiPose.BLIND);
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
        // the gun held (HOLD; vanilla's is NTM's bow pose, along the look): calm, lowered in front of it; on the run,
        // across the chest; in a fight walking or standing, ready along the look as vanilla has it
        float move = upright * (1F - BotAnimator.smooth(f.crouch()))
                * BotAnimator.smooth((f.speed() - MOVE_FROM) / (MOVE_FULL - MOVE_FROM));
        float[] gw = gaitShares(f.gaitPace(), f.heading());
        // gait clips in place of the mod's (the mob's own from recordings, BotGaits, or a stand's; null with
        // Config.botMoves old): their share of the gait going ahead
        BotGaitOverride o = BotGaitOverride.of(f.entity());
        BotGaitOverride.Way oBack = o == null ? null : o.back(), oSide = o == null ? null : o.side(f.heading());
        float aheadSwapped = o == null ? 0F : (o.walk() != null ? gw[0] : 0F) + (o.jog() != null ? gw[1] : 0F) + (o.sprint() != null ? gw[2] : 0F);
        float swapped = aheadSwapped + (oBack != null ? gw[3] : 0F) + (oSide != null ? gw[4] : 0F);
        float bare = o != null && o.bare() ? move * swapped : 0F;
        float run = move * (gw[1] * JOG_RUN + gw[2]), alert = BotAnimator.smooth(f.alert());
        float gun = f.arm(AiPose.HOLD) * upright;
        if (gun > 0F) {
            carry(p, CARRY, gun * (1F - alert));
            carry(p, CHEST, gun * run);
        }
        // beside a doorway: the gun close, its muzzle up
        carry(p, READY, stack * upright);
        float armed = 1F - f.arm(AiPose.NO_GUN);
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
        if (move > 0F) gait(p, f.speed(), f.gaitPace(), f.heading(), walkPos, move, a, o);

        // the knees, slower than the clips: vanilla swings straight legs (xRot cos(pos 0.6662) 1.4 speed); a leg
        // swinging forward lifts its foot. The elbows: bent a little with no gun, more as it walks; with a gun the
        // hands stay where vanilla's straight arms put the gun (AiPoseGeometry's muzzle)
        float walk = Mth.clamp(walkSpeed, 0F, 1F) * upright;
        if (walk > 0F && move < 1F) {
            float swing = Mth.sin(walkPos * 0.6662F), k = KNEE_WALK * walk * (1F - move);
            if (p.rightShin != null) p.rightShin.xRot += k * Math.max(0F, swing);
            if (p.leftShin != null) p.leftShin.xRot += k * Math.max(0F, -swing);
        }
        // in a fight at a stand: a foot ahead and a foot back, a shooter's stance (calm, feet together); the shins
        // are plant's
        float stance = alert * upright * (1F - BotAnimator.smooth(f.crouch())) * (1F - BotAnimator.smooth(f.speed() / MOVE_FULL))
                * (1F - BotAnimator.smooth(walk / PLANT_WALK));
        if (stance > 0F) {
            p.leftLeg.xRot -= STANCE_AHEAD * stance;
            p.rightLeg.xRot += STANCE_BACK * stance;
        }
        // the foot it stands on, on the ground (walking back, the leg going forward); on the run and walking in a
        // fight its knee bent (stoop)
        float tac = alert * move * (1F - run), knees = (RUN_BEND * run + TAC_BEND * tac) * (1F - bare);
        // a clip put in bare: its foot down is on the floor already (BotGaitOverride.bare)
        plant(p, upright * (1F - crawl) * (1F - bare), walkPos, move * gw[3], walk, knees);
        // a move from recordings holding the legs (BotMoves: a turn on the spot, its feet standing as the body turns),
        // each over the one before
        float moved = 0F, twist = 0F;
        for (BotAnimator.Moving mv : f.moves()) {
            set(p, mv.clip(), mv.t(), mv.w() * upright, LEGS, a);
            moved = Math.max(moved, mv.w() * upright * mv.drop());
            twist = Mth.lerp(mv.w() * upright, twist, mv.twist());
        }
        twistLegs(p, twist);
        float free = f.arm(AiPose.NO_GUN) * upright;
        if (free > 0F) {
            float bend = free * (ELBOW_FREE + ELBOW_WALK * walk);
            if (p.rightForearm != null) p.rightForearm.xRot -= bend;
            if (p.leftForearm != null) p.leftForearm.xRot -= bend;
        }

        // the body on the move: a run leans the trunk into it, the knees a little bent, the gun at the chest going
        // with the trunk (a combat sprint, not a light-footed jog); a walk in a fight a little forward (a tactical
        // walk); calm, a stroll as the clips have it
        stoop(p, RUN_LEAN * run + TAC_LEAN * tac, knees, gun * run);
        // the hips (and all over them) down as far as the gait's clips put in are made for (BotGaits, a stand's), on
        // top of the stoop's, or a move's (its legs made for it): the one height both are made for, not one under the
        // other (the head within 3 cm of the eye)
        float drop = Math.max(moved, o == null ? 0F
                : move * (o.drop() * aheadSwapped + (oBack != null ? oBack.drop() * gw[3] : 0F) + (oSide != null ? oSide.drop() * gw[4] : 0F)));
        if (drop > 0F) for (ModelPart part : new ModelPart[] { p.head, p.body, p.rightArm, p.leftArm, p.rightLeg, p.leftLeg }) part.y += drop;

        // in the air (a jump up a block, a fall): the legs of a jump, one knee drawn up
        float c = BotAnimator.smooth(f.crouch()) * upright;
        float air = BotAnimator.smooth(f.air()) * upright * (1F - c);
        BotClip jump = air > 0F ? BotClips.get(BotClips.JUMP) : null;
        if (jump != null) turns(p, jump, jump.local(f.idleSec()), air, LEGS, a);

        // 3. the crouch, by its weight: the head, the trunk's top and the shoulders where vanilla's crouch has them
        // (HumanoidModel.setupAnim, 26.2: the eye the server keeps, and the hand of blind fire); under them, down on
        // a knee at a stand and a walk bent low on the move, their legs and the trunk's lean fitted to that head
        if (c > 0F) {
            // a stand's walk bent low in place of the mod's (BotGaitOverride.crouch)
            BotGaitOverride.Way oLow = o == null || o.crouch() == null || BotClips.get(o.crouch().clip()) == null ? null : o.crouch();
            String lowName = oLow != null ? oLow.clip() : BotClips.CROUCH_WALK;
            BotClip kneel = BotClips.get(BotClips.KNEEL), low = BotClips.get(lowName);
            float lean = CROUCH_LEAN;
            if (kneel != null && low != null) {
                // under a slide or a roll and till a few ticks after it, down on the knee: the dash's clip fading out
                // over a leg in the middle of its step, near half a turn from the clip's, would take that leg right
                // round in a frame
                float go = BotAnimator.smooth((f.speed() - MOVE_FROM) / (MOVE_FULL - MOVE_FROM)) * (1F - BotAnimator.smooth(f.dashed()));
                // backwards about the clip's phase: at walkPos 0 both at the same pose, so going from one to the other
                // does not shift the step
                float ph = phase(lowName, low), u = walkPos / CYCLE;
                float t = ((u + ph) % 1F + 1F) % 1F * low.length(), tBack = ((ph - u) % 1F + 1F) % 1F * low.length();
                // going back the clip is played backwards: a foot on the ground goes ahead under it, as the mob goes
                // back (played on, the legs walked ahead while it went back)
                float back = crouchBack(f.heading());
                // the three by their shares, all of the way c from what the legs were: down on the knee, then the walk
                // ahead, then back, each set over the ones before
                float backW = c * go * back, aheadW = c * go * (1F - back);
                float ahead = backW >= 1F - 1e-4F ? 0F : aheadW / (1F - backW);
                float rest = (1F - backW) * (1F - ahead), kneeling = rest <= 1e-4F ? 0F : c * (1F - go) / rest;
                set(p, kneel, 0F, kneeling, LEGS, a);
                set(p, low, t, ahead, LEGS, a);
                set(p, low, tBack, backW, LEGS, a);
                // a stand's: its legs are made for hips its drop under a standing mob's and have no pivots of their
                // own; the hips there, under the trunk's bottom leaned back from where vanilla's crouch has its top
                float behind = 0F;
                if (oLow != null) {
                    float under = p.rightLeg.getInitialPose().y() + oLow.drop() - CROUCH_BODY;
                    behind = (float) Math.sqrt(Math.max(0F, TRUNK * TRUNK - under * under));
                    for (ModelPart thigh : new ModelPart[] { p.rightLeg, p.leftLeg }) {
                        hip(thigh, oLow.drop(), behind, ahead);
                        hip(thigh, oLow.drop(), behind, backW);
                    }
                }
                // each of the two stands on the ground, a mix of them (starting, stopping, going down) does not: a
                // leg that would go into the ground is lifted to it, up into the trunk
                org.joml.Matrix4f m = M4.get();
                float right = limb(p.rightLeg, p.rightShin, -2F, 0F, m) - GROUND, left = limb(p.leftLeg, p.leftShin, -2F, 0F, m) - GROUND;
                if (right > 0F) p.rightLeg.y -= right * c;
                if (left > 0F) p.leftLeg.y -= left * c;
                float k = kneel.rotation("body", 0F, a) ? a[0] : 0F, l = low.rotation("body", t, a) ? a[0] : 0F;
                if (back > 0F) l = Mth.lerp(back, l, low.rotation("body", tBack, a) ? a[0] : 0F);
                if (oLow != null) l = Mth.RAD_TO_DEG * (float) Math.asin(behind / TRUNK);
                lean = Mth.DEG_TO_RAD * Mth.lerp(go, k, l);
            } else {
                // no clips: vanilla's, the legs back
                p.rightLeg.z += 4F * c;
                p.leftLeg.z += 4F * c;
            }
            p.body.xRot += lean * c;
            // vanilla tips the arms with the body; arms aimed, held up or close at the chest keep their line
            p.rightArm.xRot += CROUCH_ARMS * lean * c * plain;
            p.leftArm.xRot += CROUCH_ARMS * lean * c * plain;
            p.head.y += 4.2F * c;
            p.body.y += CROUCH_BODY * c;
            p.rightArm.y += AiPoseGeometry.CROUCH_ARM_DROP_PX * c;
            p.leftArm.y += AiPoseGeometry.CROUCH_ARM_DROP_PX * c;
        }

        // 4. the action, and the one it cut short fading out under it. A throw recorded from a person: the rifle goes
        // with the left hand as the arms hold it under the throw (Carry)
        if (carry != null) carry.w = carry.k = 0F;
        for (BotAnimator.Act out : f.fading()) action(p, out, headX, headY, a, b, carry);
        if (f.act() != null) action(p, f.act(), headX, headY, a, b, carry);
        if (carry != null && carry.w > 0F) {
            // the left hand's whole as the throw comes in; handed back to the right hand as it goes out, or as
            // what cut it short (a slide, a fall dead) comes over it
            BotAnimator.Act on = f.act();
            carry.k = on != null && on.kind() == AiPose.THROW ? (on.u() < 0.5F ? 1F : BotAnimator.smooth(on.w() / CARRY_FULL))
                    : BotAnimator.smooth(carry.w / CARRY_FULL) * (1F - (on != null ? on.w() : 0F));
        }

        // 5. a hit's flinch (and one it cut short, going); through a throw on the arms before it too (Carry.before)
        Parts unthrown = carry != null && carry.w > 0F && f.hits().length > 0 ? carry.before() : null;
        for (BotAnimator.Hit hit : f.hits()) {
            BotClip h = BotClips.get(hit.clip());
            if (h == null) continue;
            add(p, h, h.local(hit.sec()), hit.w() * upright, a, b, true);
            if (unthrown != null) add(unthrown, h, h.local(hit.sec()), hit.w() * upright, a, b, true);
        }
        if (unthrown != null) carry.flinched(unthrown);

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

        // 7. a hit's push (BotImpulse) and the trunk's weight (BotAnimator.weigh): the trunk turned about the neck, the
        // hips and the legs' roots under it, the head where it is (the server's eye); upright and alive only
        kick(p, f.kick(), upright * (1F - dead));

        // 8. standing, the feet where they stand on the floor (BotAnimator.feet): the body turns, leans and sways over
        // them, and a foot steps when it must
        lock(p, f.feet());
    }

    /**
     * A hit's push w of the way: kick[0..2] added to the trunk's turn, the legs' roots moved as the body's bottom goes
     * from where the pose's own turn has it (BotImpulse.hips: the pose's trunk may be bent or twisted already)
     */
    static void kick(Parts p, float @Nullable [] kick, float w) {
        if (kick == null || w <= 0F) return;
        float[] was = new float[6], now = new float[6];
        BotImpulse.hips(p.body.xRot, p.body.yRot, p.body.zRot, was);
        p.body.xRot += kick[0] * w;
        p.body.yRot += kick[1] * w;
        p.body.zRot += kick[2] * w;
        BotImpulse.hips(p.body.xRot, p.body.yRot, p.body.zRot, now);
        for (ModelPart leg : new ModelPart[] { p.rightLeg, p.leftLeg }) {
            leg.x += now[3] - was[3];
            leg.y += now[4] - was[4];
            leg.z += now[5] - was[5];
        }
    }

    /**
     * Each leg reaches its foot where it stands (BotAnimator.Feet, by its weight): the knee bends (or straightens) to
     * the hip's distance from it, the thigh turns to point there, and the leg turns about that line to the foot's way.
     * Writes back where the pose had each ankle before, for the next step.
     */
    static void lock(Parts p, BotAnimator.@org.jspecify.annotations.Nullable Feet feet) {
        if (feet == null || p.rightShin == null || p.leftShin == null) return;
        lockLeg(p.rightLeg, p.rightShin, feet, 0);
        lockLeg(p.leftLeg, p.leftShin, feet, 1);
    }

    /**
     * Both legs turned about the mob's upright (the model's Y through its middle, where it turns on the spot) by yaw
     * (radians, as the model's yRot: + the way the body turns to its right): the hips go round, each thigh turned so
     * (the shin goes with it)
     */
    static void twistLegs(Parts p, float yaw) {
        if (yaw == 0F) return;
        Quaternionf by = new Quaternionf().rotationY(yaw);
        float c = Mth.cos(yaw), s = Mth.sin(yaw);
        for (ModelPart thigh : new ModelPart[] { p.rightLeg, p.leftLeg }) {
            float x = thigh.x, z = thigh.z;
            thigh.x = c * x + s * z;
            thigh.z = -s * x + c * z;
            Vector3f e = new Quaternionf().rotationZYX(thigh.zRot, thigh.yRot, thigh.xRot).premul(by).getEulerAnglesZYX(new Vector3f());
            thigh.xRot = e.x;
            thigh.yRot = e.y;
            thigh.zRot = e.z;
        }
    }

    /** the thigh and the shin are BotModel.HALF px each: the ankle's distance from the hip, straight */
    private static final float LEG = 2F * BotModel.HALF;
    /**
     * Past this share of LEG the knee straightens softly (soft IK): straight, a knee's bend for a small change of reach
     * has no bound (2 acos), and a foot set down by a nearly straight leg would snap the knee
     */
    private static final float SOFT = 0.99F;

    /** the knee's bend (radians) for an ankle this far from the hip (px), the reach eased past SOFT */
    static float kneeFor(float reach) {
        float s = SOFT * LEG, d = reach <= s ? reach : s + (LEG - s) * (1F - (float) Math.exp(-(reach - s) / (LEG - s)));
        return 2F * (float) Math.acos(Mth.clamp(d / LEG, -1F, 1F));
    }

    private static void lockLeg(ModelPart thigh, ModelPart shin, BotAnimator.Feet feet, int k) {
        Vector3f a = ankle(thigh, shin, new Vector3f());
        feet.neutral[k * 3] = a.x;
        feet.neutral[k * 3 + 1] = a.y;
        feet.neutral[k * 3 + 2] = a.z;
        feet.shortOf[k] = 0F;
        float w = Mth.clamp(feet.w, 0F, 1F);
        if (w <= 0F) return;
        Vector3f hip = new Vector3f(thigh.x, thigh.y, thigh.z);
        Vector3f to = new Vector3f(feet.at[k * 4], a.y - feet.at[k * 4 + 2], feet.at[k * 4 + 1]);
        feet.shortOf[k] = Math.max(0F, to.distance(hip) - LEG);
        to.lerp(new Vector3f(a), 1F - w);
        float twist = feet.at[k * 4 + 3] * w;
        // the knee, a hinge (BotModel), bends one way only: its bend gives the hip-to-ankle distance 2 HALF cos(bend / 2);
        // by the feet's weight from the pose's own (a nearly straight leg's soft reach bends it some 10 degrees)
        shin.xRot = Mth.lerp(w, shin.xRot, kneeFor(to.distance(hip)));
        ankle(thigh, shin, a);
        // the thigh: turned so the ankle goes there, then about that line to the foot's way
        Vector3f from = new Vector3f(a).sub(hip), there = new Vector3f(to).sub(hip);
        if (from.lengthSquared() < 1e-6F || there.lengthSquared() < 1e-6F) return;
        Quaternionf q = new Quaternionf().rotationTo(from, there);
        q.premul(new Quaternionf().rotationAxis(twist, there.normalize()));
        Quaternionf t = new Quaternionf().rotationZYX(thigh.zRot, thigh.yRot, thigh.xRot).premul(q);
        Vector3f e = t.getEulerAnglesZYX(new Vector3f());
        thigh.xRot = e.x;
        thigh.yRot = e.y;
        thigh.zRot = e.z;
    }

    /** where a leg's ankle is (the shin's far end, its middle), px in the model */
    static Vector3f ankle(ModelPart thigh, ModelPart shin, Vector3f out) {
        org.joml.Matrix4f m = M4.get();
        frame(thigh, m).translate(shin.x, shin.y, shin.z).rotateZYX(shin.zRot, shin.yRot, shin.xRot);
        return m.transformPosition(out.set(0F, BotModel.HALF, 0F));
    }

    static void action(Parts p, BotAnimator.Act act, float headX, float headY, float[] a, float[] b, @Nullable Carry carry) {
        float w = act.w();
        if (w <= 0F) return;
        switch (act.kind()) {
            case AiPose.RELOAD -> {
                BotClip r = BotClips.get(BotClips.RELOAD);
                if (r != null) {
                    // the rifle's reload (tools/quaternius bot_clips ARMS: the gun up in front of the chest, the
                    // magazine in under the line of sight), stretched to the reload's length (coming in over RELOAD_IN:
                    // BotAnimator): the arms only (RELOAD_BONES): the body keeps its crouch and the head its aim
                    for (String bone : RELOAD_BONES) {
                        ModelPart part = p.get(bone);
                        if (part != null && r.rotation(bone, act.u() * r.length(), a))
                            turn(part, Mth.DEG_TO_RAD * a[0], Mth.DEG_TO_RAD * a[1], Mth.DEG_TO_RAD * a[2], w);
                    }
                    return;
                }
                // no clip: the gun down to the chest, the left hand to the magazine and back twice (out, in), the
                // eyes on the threat, the elbows bent: the gun in to the chest, the left hand up to it
                float pull = Mth.sin(PI * ((act.u() * 2F) % 1F));
                turn(p.rightArm, -0.6F, -0.35F, 0F, w);
                turn(p.leftArm, -0.45F - 0.35F * pull, 0.65F, 0F, w);
                if (p.rightForearm != null) turn(p.rightForearm, -0.7F, 0F, 0F, w);
                if (p.leftForearm != null) turn(p.leftForearm, -0.9F - 0.3F * pull, 0F, 0F, w);
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
                BotClip t = throwClip();
                if (t != null && t == BotClips.get(BotClips.THROW_PERSON)) {
                    // the rifle where the arms hold it under the throw (whatever went on under it: a reload cut
                    // short), the left hand's from here on; the clip's shoulders moved from where they are (crouched
                    // they stay down), its turns; the head on its aim (THROW_BONES)
                    if (carry != null && w >= carry.w) carry.hold(p, w);
                    turns(p, t, act.u() * t.length(), w, THROW_BONES, a);
                    shifts(p, t, act.u() * t.length(), w, THROW_BONES, a);
                } else if (t != null) set(p, t, act.u() * t.length(), w, UPPER, a);
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
            case AiPose.DOOR -> {
                // the free left hand out to the door and back (UAL Interact's left arm), the gun kept in the right
                BotClip t = BotClips.get(BotClips.DOOR);
                if (t != null) turns(p, t, Math.min(DOOR_FROM + act.u() * DOOR_FOR, t.length()), w, LEFT_ARM, a);
                else turn(p.leftArm, -PI / 2F, 0.2F, 0F, w);
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

    /**
     * A grenade's throw: recorded from a person (Config.botMoves clips: BotClips.THROW_PERSON, the right hand lets the
     * grip go and throws, the left keeps the rifle down at the side) or UAL's OverhandThrow (old, or no such clip).
     */
    static @Nullable BotClip throwClip() {
        BotClip c = BotMoves.on() ? BotClips.get(BotClips.THROW_PERSON) : null;
        return c != null ? c : BotClips.get(BotClips.THROW);
    }

    /**
     * ticks a throw recorded from a person comes in over: its clip starts with the arm drawn back and lets go at 0.13 s
     * (the server sends the grenade off on the throw's first tick: AiSoldier.THROW_TICKS), so the swing is not washed
     * out under the hold; the others' come in over BotAnimator.ACTION_FADE
     */
    static final float THROW_IN = 2F;

    /** a throw on (or one going out under the action that cut it short) */
    static boolean throwing(BotAnimator.Frame f) {
        if (f.act() != null && f.act().kind() == AiPose.THROW) return true;
        for (BotAnimator.Act out : f.fading()) if (out.kind() == AiPose.THROW) return true;
        return false;
    }

    static boolean throwsAsAPerson() {
        BotClip c = throwClip();
        return c != null && c == BotClips.get(BotClips.THROW_PERSON);
    }

    /**
     * The rifle through a throw recorded from a person: where it sits from the left hand (the left forearm's frame, px,
     * the model's) as the arms hold it under the throw (a reload it cut short too), the throw's weight and how much of
     * the way the left hand has it. Its fore-end stays in the left hand: as that arm goes down at the side the muzzle
     * goes down and aside. Coming in the left hand has it whole from the first frame: its arm is where it holds the
     * rifle as the right hand does, so the rifle stays where it was while the right hand lets go and swings back.
     * Going out, and when something cuts the throw short (a slide, a fall dead: each arm its own way), it goes back to
     * the right hand over the throw's last CARRY_FULL of weight, not in a frame.
     */
    public static final class Carry {
        final org.joml.Matrix4f fromLeft = new org.joml.Matrix4f();
        float w, k;

        public float w() {
            return w;
        }

        /** the arms as hold found them (right arm, right forearm, left arm, left forearm: pivot x y z, turn x y z) */
        private final float[] before = new float[24];

        /** the rifle where the arms hold it now, the left hand's whole from here on (none: a model not split) */
        public Carry hold(Parts p, float w) {
            this.w = p.rightForearm != null && p.leftForearm != null ? Math.max(0F, w) : 0F;
            k = this.w > 0F ? 1F : 0F;
            if (this.w > 0F) {
                int i = 0;
                for (ModelPart a : new ModelPart[] { p.rightArm, p.rightForearm, p.leftArm, p.leftForearm }) {
                    before[i++] = a.x;
                    before[i++] = a.y;
                    before[i++] = a.z;
                    before[i++] = a.xRot;
                    before[i++] = a.yRot;
                    before[i++] = a.zRot;
                }
                take(p);
            }
            return this;
        }

        private void take(Parts p) {
            hand(p.leftArm, p.leftForearm, fromLeft).invert().mul(hand(p.rightArm, p.rightForearm, M4.get()));
        }

        /**
         * The arms as hold found them, on their own: a hit's flinch laid on the arms after the throw (BotPoser.apply's
         * 5) is laid on these too, and {@link #flinched} takes the rifle from them, so it is where the right hand holds
         * it flinched as the throw comes in (each hand turns its own way in a flinch: taken before it, the rifle would
         * jump to the left hand's)
         */
        Parts before() {
            ModelPart[] arms = new ModelPart[4];
            for (int i = 0; i < arms.length; i++) {
                ModelPart a = arms[i] = new ModelPart(java.util.List.of(), java.util.Map.of());
                a.x = before[i * 6];
                a.y = before[i * 6 + 1];
                a.z = before[i * 6 + 2];
                a.setRotation(before[i * 6 + 3], before[i * 6 + 4], before[i * 6 + 5]);
            }
            ModelPart none = new ModelPart(java.util.List.of(), java.util.Map.of());
            return new Parts(none, none, arms[0], arms[2], none, none, arms[1], arms[3], null, null);
        }

        /** the rifle from the arms before the throw, as they are flinched now ({@link #before}) */
        void flinched(Parts before) {
            take(before);
        }
    }

    /**
     * The hand an arm holds its item with (the frame ItemInHandLayer draws it in: the upper arm's, then the forearm's
     * turn about the elbow, BotAnimation.hand) into m: px, the model's.
     */
    static org.joml.Matrix4f hand(ModelPart arm, ModelPart forearm, org.joml.Matrix4f m) {
        return frame(arm, m).translate(forearm.x, forearm.y, forearm.z).rotateZYX(forearm.zRot, forearm.yRot, forearm.xRot)
                .translate(-forearm.x, -forearm.y, -forearm.z);
    }

    /**
     * Where the rifle is drawn (px, the model's), into out: through a throw the left hand's frame with the rifle where
     * that hand holds it (Carry), else the right hand's.
     */
    static org.joml.Matrix4f carried(Parts p, Carry c, org.joml.Matrix4f out) {
        if (p.rightForearm == null) return frame(p.rightArm, out);
        float k = c.w > 0F ? c.k : 0F;
        if (k <= 0F || p.leftForearm == null) return hand(p.rightArm, p.rightForearm, out);
        hand(p.leftArm, p.leftForearm, out).mul(c.fromLeft);
        if (k >= 1F) return out;
        org.joml.Matrix4f right = hand(p.rightArm, p.rightForearm, new org.joml.Matrix4f());
        Vector3f t0 = right.getTranslation(new Vector3f()), t1 = out.getTranslation(new Vector3f());
        Quaternionf q0 = right.getNormalizedRotation(new Quaternionf()), q1 = out.getNormalizedRotation(new Quaternionf());
        return out.translation(t0.lerp(t1, k)).rotate(q0.slerp(q1, k));
    }

    /** going out, the throw's weight from which the left hand has the rifle whole (Carry) */
    static final float CARRY_FULL = 0.3F;

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
        gait(p, speed, heading, walkPos, w, a, null);
    }

    /** ... with a stand's clips in place of the mod's ({@code o}, BotGaitOverride), played at their own stride */
    static void gait(Parts p, float speed, float heading, float walkPos, float w, float[] a, @Nullable BotGaitOverride o) {
        gait(p, speed, gaitOf(speed), heading, walkPos, w, a, o);
    }

    /** ... at a gait drawn of its own (Frame.gait) */
    static void gait(Parts p, float speed, float gait, float heading, float walkPos, float w, float[] a, @Nullable BotGaitOverride o) {
        float[] legs = new float[LEGS.length * 3];
        if (!gaitLegs(speed, gait, heading, walkPos, o, legs, a)) return;
        for (int b = 0; b < LEGS.length; b++) {
            ModelPart part = p.get(LEGS[b]);
            if (part == null) continue;
            part.xRot = Mth.lerp(w, part.xRot, Mth.DEG_TO_RAD * legs[b * 3]);
            part.yRot = Mth.lerp(w, part.yRot, Mth.DEG_TO_RAD * legs[b * 3 + 1]);
            part.zRot = Mth.lerp(w, part.zRot, Mth.DEG_TO_RAD * legs[b * 3 + 2]);
        }
    }

    /**
     * The gait's legs at walkPos (gait): each of LEGS' rotation, degrees (x, y, z after each other) into out; false if
     * no clip is there. {@code a} a scratch of 3
     */
    static boolean gaitLegs(float speed, float gait, float heading, float walkPos, @Nullable BotGaitOverride o, float[] out, float[] a) {
        float u = walkPos / CYCLE;
        float[][] acc = new float[LEGS.length][3];
        float total = 0F;
        String[] names = java.util.Arrays.copyOf(gaitClips(heading), 6);
        BotGaitOverride.Way stroll = o == null || o.walk() == null ? null : o.stroll();
        float[] ws = strolled(gaitShares(gait, heading), stroll == null ? 0F : strolling(speed));
        if (stroll != null) names[5] = stroll.clip();
        if (o != null) {
            if (o.walk() != null) names[0] = o.walk();
            if (o.jog() != null) names[1] = o.jog();
            if (o.sprint() != null) names[2] = o.sprint();
            if (o.back() != null) names[3] = o.back().clip();
            BotGaitOverride.Way side = o.side(heading);
            if (side != null) names[4] = side.clip();
        }
        for (int i = 0; i < names.length; i++) {
            if (ws[i] <= 1e-4F || names[i] == null) continue;
            BotClip clip = BotClips.get(names[i]);
            if (clip == null) continue;
            float t = ((u + phase(names[i], clip)) % 1F + 1F) % 1F * clip.length();
            for (int b = 0; b < LEGS.length; b++)
                if (clip.rotation(LEGS[b], t, a)) for (int k = 0; k < 3; k++) acc[b][k] += ws[i] * a[k];
            total += ws[i];
        }
        if (total <= 0F) return false;
        // ahead only (the walk's, the jog's and the sprint's share): the clips back and aside swing about another middle;
        // a stand's clips at their own stride
        float ahead = (o != null && o.walk() != null ? 0F : ws[0]) + (o != null && o.jog() != null ? 0F : ws[1])
                + (o != null && o.sprint() != null ? 0F : ws[2]);
        float wide = 1F + (stride(speed) - 1F) * ahead;
        for (int b = 0; b < LEGS.length; b++) {
            float x = acc[b][0] / total;
            // the thighs (not the shins) wider on the run
            if (wide != 1F && (b == 0 || b == 2)) x = STRIDE_MIDDLE + (x - STRIDE_MIDDLE) * wide;
            out[b * 3] = x;
            out[b * 3 + 1] = acc[b][1] / total;
            out[b * 3 + 2] = acc[b][2] / total;
        }
        return true;
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
        plant(p, w, walkPos, back, walk, 0F);
    }

    /** ... and the knees bent this far (both thighs forward, each shin as it was: the one it stands on hung under its knee) */
    static void plant(Parts p, float w, float walkPos, float back, float walk, float knees) {
        if (w <= 0F || p.rightShin == null || p.leftShin == null) return;
        // walking back the legs step the other way round; half back, half aside (the two clips' steps at odds) both
        // stand
        float swing = Mth.sin(walkPos * 0.6662F) * (1F - 2F * back), stepping = BotAnimator.smooth(walk / PLANT_WALK);
        float right = BotAnimator.smooth((PLANT_SWING - swing) / PLANT_SWING), left = BotAnimator.smooth((PLANT_SWING + swing) / PLANT_SWING);
        if (knees > 0F) {
            p.rightLeg.xRot -= w * knees;
            p.leftLeg.xRot -= w * knees;
            p.rightShin.xRot += w * knees;
            p.leftShin.xRot += w * knees;
        }
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

    /** crouched on the move: the share of the walk bent low played backwards, 0 going ahead .. 1 going back */
    static float crouchBack(float heading) {
        if (!crouchBack) return 0F;
        return BotAnimator.smooth((Mth.cos(heading) - CROUCH_BACK_FROM) / (CROUCH_BACK_FULL - CROUCH_BACK_FROM));
    }

    /** the clips gait blends: walk, jog, sprint, back, and the strafe to the side it goes */
    public static String[] gaitClips(float heading) {
        return new String[] { BotClips.WALK, BotClips.JOG, BotClips.SPRINT, BotClips.BACK,
                Mth.sin(heading) > 0F ? BotClips.STRAFE_RIGHT : BotClips.STRAFE_LEFT };
    }

    /**
     * gaitShares with a sixth, the stroll's (BotGaitOverride.stroll): this share of the walk's going to it (none: 0)
     */
    static float[] strolled(float[] ws, float stroll) {
        float[] out = java.util.Arrays.copyOf(ws, 6);
        out[5] = ws[0] * stroll;
        out[0] = ws[0] - out[5];
        return out;
    }

    /** each of gaitClips' weights at this speed and heading, summing to 1 */
    public static float[] gaitWeights(float speed, float heading) {
        return gaitShares(gaitOf(speed), heading);
    }

    /**
     * the gait at this speed: 0 a walk .. 1 a jog (JOG_FROM .. JOG_FULL) .. 2 a sprint (SPRINT_FROM .. SPRINT_FULL),
     * the bands apart
     */
    public static float gaitOf(float speed) {
        return BotAnimator.smooth((speed - JOG_FROM) / (JOG_FULL - JOG_FROM))
                + BotAnimator.smooth((speed - SPRINT_FROM) / (SPRINT_FULL - SPRINT_FROM));
    }

    /** each of gaitClips' weights at this gait (gaitOf) and heading, summing to 1 */
    public static float[] gaitShares(float gait, float heading) {
        float c = Mth.cos(heading), s = Mth.sin(heading);
        float ahead = Math.max(c, 0F), back = Math.max(-c, 0F), side = Math.abs(s), sum = ahead + back + side;
        float jog = Mth.clamp(gait, 0F, 1F), sprint = Mth.clamp(gait - 1F, 0F, 1F);
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
     *
     */
    static void add(Parts p, BotClip clip, float t, float w, float[] a, float[] b) {
        add(p, clip, t, w, a, b, false);
    }

    /**
     * ... and a clip made to keep the head where it is ({@code pinned}: the flinches, tools/quaternius bot_clips
     * HEAD_PINNED) keeps it whichever way the head looks: the clip's nod D (the turn added) is made for a head looking
     * straight ahead, and the neck it moves to keep the head's middle where it is (fit.pin_head: head, trunk, arms and
     * hips by one shift) holds it only there. A head turned P already (aimed up, down, aside) nods its middle by
     * P(D - I)C, not (D - I)C: those six are moved the rest, -(P - I)(D - I)C ({@link #HEAD_MIDDLE}). Not for a clip
     * that nods the head alone (the idle's breath): its trunk would sway with the nod.
     */
    static void add(Parts p, BotClip clip, float t, float w, float[] a, float[] b, boolean pinned) {
        float nx = 0F, ny = 0F, nz = 0F;
        for (String bone : ALL) {
            ModelPart part = p.get(bone);
            if (part == null) continue;
            if (clip.rotation(bone, t, a) && clip.rotation(bone, 0F, b)) {
                Quaternionf[] q = Q.get();
                q[1].rotationZYX(Mth.DEG_TO_RAD * b[2], Mth.DEG_TO_RAD * b[1], Mth.DEG_TO_RAD * b[0]).conjugate();
                q[2].rotationZYX(Mth.DEG_TO_RAD * a[2], Mth.DEG_TO_RAD * a[1], Mth.DEG_TO_RAD * a[0]);
                q[1].mul(q[2]);
                q[2].identity().slerp(q[1], w);
                q[0].rotationZYX(part.zRot, part.yRot, part.xRot);
                if (pinned && part == p.head()) {
                    // (P - I)(D - I)C: the nod of the middle (D - I)C, turned by the head's own P, less itself
                    Vector3f nod = q[2].transform(new Vector3f(HEAD_MIDDLE)).sub(HEAD_MIDDLE);
                    Vector3f more = q[0].transform(new Vector3f(nod)).sub(nod);
                    nx = -more.x;
                    ny = -more.y;
                    nz = -more.z;
                }
                q[0].mul(q[2]);
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
        if (nx != 0F || ny != 0F || nz != 0F)
            for (ModelPart part : new ModelPart[] { p.head(), p.body(), p.rightArm(), p.leftArm(), p.rightLeg(), p.leftLeg() }) {
                part.x += nx;
                part.y += ny;
                part.z += nz;
            }
    }

    /** px from the head's pivot (the neck) to its middle, the model's axes (Y down) */
    static final org.joml.Vector3fc HEAD_MIDDLE = new Vector3f(0F, -4F, 0F);

    /** Moves the given parts toward the clip's pose (from the default pose), weighted. */
    static void set(Parts p, BotClip clip, float t, float w, String[] bones, float[] a) {
        if (w <= 0F) return;
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

    /** px vanilla's crouch puts the trunk's top down, and the trunk's length */
    static final float CROUCH_BODY = 3.2F, TRUNK = 12F;

    /** the thigh's pivot w of the way to down px under its own and behind px back of it */
    private static void hip(ModelPart thigh, float down, float behind, float w) {
        if (w <= 0F) return;
        PartPose d = thigh.getInitialPose();
        thigh.y = Mth.lerp(w, thigh.y, d.y() + down);
        thigh.z = Mth.lerp(w, thigh.z, d.z() + behind);
    }

    /** Moves the given parts' pivots by the clip's shifts (from the default pose's), weighted, from where they are. */
    static void shifts(Parts p, BotClip clip, float t, float w, String[] bones, float[] a) {
        if (w <= 0F) return;
        for (String bone : bones) {
            ModelPart part = p.get(bone);
            if (part == null || !clip.position(bone, t, a)) continue;
            part.x += w * a[0];
            part.y -= w * a[1];
            part.z += w * a[2];
        }
    }

    /** Turns the given parts toward the clip's pose, weighted; their pivots stay where they are. */
    static void turns(Parts p, BotClip clip, float t, float w, String[] bones, float[] a) {
        if (w <= 0F) return;
        for (String bone : bones) {
            ModelPart part = p.get(bone);
            if (part != null && clip.rotation(bone, t, a))
                turn(part, Mth.DEG_TO_RAD * a[0], Mth.DEG_TO_RAD * a[1], Mth.DEG_TO_RAD * a[2], w);
        }
    }

    /** an action of the whole body */
    static boolean dash(byte kind) {
        return kind == AiPose.SLIDE || kind == AiPose.ROLL;
    }

    /** The arms to a way of holding the gun (CARRY, CHEST, READY), w of the way. */
    static void carry(Parts p, float[] c, float w) {
        if (w <= 0F) return;
        turn(p.rightArm, c[0], c[1], c[2], w);
        turn(p.leftArm, c[4], c[5], c[6], w);
        if (p.rightForearm != null) p.rightForearm.xRot = Mth.lerp(w, p.rightForearm.xRot, c[3]);
        if (p.leftForearm != null) p.leftForearm.xRot = Mth.lerp(w, p.leftForearm.xRot, c[7]);
    }

    /**
     * The trunk leaned forward (about the neck, as vanilla's crouch: the hips go back and the legs with them), and all
     * of it down till the lower foot is on the ground again after the knees' bend (a thigh forward by bend, the foot
     * it stands on put under its knee lifts it), DOWN_MOST at most: the head within pose-contract's 3 cm of the eye.
     * The arms holding the gun (armsWith) go with the trunk.
     */
    static void stoop(Parts p, float lean, float bend, float armsWith) {
        if (lean <= 0F && bend <= 0F) return;
        p.body.xRot += lean;
        float back = 12F * Mth.sin(lean);
        p.rightLeg.z += back;
        p.leftLeg.z += back;
        float shoulder = 2F * Mth.sin(lean);
        p.rightArm.z += shoulder;
        p.leftArm.z += shoulder;
        p.rightArm.xRot += lean * armsWith;
        p.leftArm.xRot += lean * armsWith;
        // the lower foot back on the ground, as far as the head may go down with it
        org.joml.Matrix4f m = M4.get();
        float low = Math.max(limb(p.rightLeg, p.rightShin, -2F, 0F, m), limb(p.leftLeg, p.leftShin, -2F, 0F, m));
        float down = Mth.clamp(GROUND - low, 0F, DOWN_MOST * Math.min(1F, bend / RUN_BEND));
        for (ModelPart part : new ModelPart[] { p.head, p.body, p.rightArm, p.leftArm, p.rightLeg, p.leftLeg }) part.y += down;
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
