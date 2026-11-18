package acup.gunfeel.ai.client;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ai.AiPose;
import acup.gunfeel.ai.AiPoseGeometry;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

/**
 * What an AI mob's body does this frame, worked out once per frame when its render state is filled (the renderer's
 * extractRenderState): how far it crouches, which way its arms go, the action under way and how far through it is,
 * the flinch of a hit. {@link BotPoser} then puts the model's parts there in setupAnim, so the hitbox, read from the
 * same parts, takes the pose.
 *
 * <p>Nothing jumps: a stance or an arms change moves its weights at a fixed rate from wherever they are, even when a
 * second change comes halfway; an action fades in and out; a lean takes AiPoseGeometry.leanFrac.
 */
public final class BotAnimator {
    /** ticks for a full crouch or stand up */
    static final float CROUCH_TICKS = 4F;
    /** ticks from a stroll to the gun ready, or back */
    static final float ALERT_TICKS = 10F;
    /** ticks for the arms to go from one way to another */
    static final float ARMS_TICKS = 4F;
    /** ticks an action fades in and out */
    static final float ACTION_FADE = 5F;
    /** ticks a shot's kick lasts when the server gives it no length */
    static final int SHOT_TICKS = 4;
    /** ticks a shot's kick takes to come in */
    static final float SHOT_IN = 0.5F;
    /** ticks a slide or a roll takes to come in (it is on the run: no slow fade from the run's pose) */
    static final float DASH_IN = 2F;
    /** ticks after a slide or a roll in which the legs under it come back to their step */
    static final float DASH_OUT = 4F;
    private float dashed;

    private static final Map<LivingEntity, BotAnimator> ALL = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * One frame of a mob: plain numbers, filled when the render state is and read by setupAnim.
     *
     * @param crouch  0 standing .. 1 vanilla's crouch
     * @param lean    −1 left .. 1 right (0 lying)
     * @param arms    weight of each arms way (AiPose.HOLD, AIM, BLIND, NO_GUN, STACK), summing to 1
     * @param act     the action under way, or null
     * @param fading  the actions it cut short, fading out under it (often none)
     * @param hits    the flinches showing: the last, and any it cut short fading out
     * @param idleSec seconds into the idle loop
     * @param entity  the mob's id
     * @param speed   how fast it goes over the ground, blocks a tick (smoothed)
     * @param heading which way it goes, from where its body faces: radians, 0 ahead, + to its right
     * @param deathSec seconds since it fell dead, or below 0 alive
     * @param alert   0 calm (the gun lowered, a stroll) .. 1 in a fight (the gun ready, a tactical walk)
     * @param air     0 on the ground .. 1 in the air (a jump, a fall)
     * @param dashed  1 in a slide or a roll and just after it, going over a few ticks (the legs under it kept still)
     * @param bodyYaw the body's yaw as drawn, degrees ({@link #turn}), or NaN for the game's own
     * @param headYaw the head's, the same
     * @param feet    where the feet stand on the floor, standing ({@link #feet}), or null: where the pose puts them
     * @param gait    which gait it goes at, 0 a walk .. 1 a jog .. 2 a sprint (BotPoser.gaitOf), going to the speed's
     *                over a few frames (GAIT_SPRING); NaN: the speed's own
     * @param kick    the trunk's turn about the neck now, rad (kick[0..2]: a hit's push, BotImpulse.sample, with the
     *                trunk's weight's lean, weigh, in 0 and 2; kick[3..5] the push's own hips, px), or null: none
     * @param moves   the moves from recordings holding the legs (BotMoves: a turn on the spot), each over the one before
     */
    public record Frame(float crouch, float lean, float[] arms, @Nullable Act act, Act[] fading, Hit[] hits,
            float idleSec, int entity, float speed, float heading, float deathSec, float walk, float walkPos, float alert,
            float air, float dashed, float bodyYaw, float headYaw, @Nullable Feet feet, float gait, float @Nullable [] kick,
            Moving[] moves) {
        /** ... with no move under way */
        public Frame(float crouch, float lean, float[] arms, @Nullable Act act, Act[] fading, Hit[] hits, float idleSec,
                int entity, float speed, float heading, float deathSec, float walk, float walkPos, float alert, float air,
                float dashed, float bodyYaw, float headYaw, @Nullable Feet feet, float gait, float @Nullable [] kick) {
            this(crouch, lean, arms, act, fading, hits, idleSec, entity, speed, heading, deathSec, walk, walkPos, alert, air,
                    dashed, bodyYaw, headYaw, feet, gait, kick, NO_MOVES);
        }

        /** the gait it goes at (BotPoser.gaitShares) */
        public float gaitPace() {
            return Float.isNaN(gait) ? BotPoser.gaitOf(speed) : gait;
        }

        /** with no turn of its own (the game's body and head) and its feet where the pose puts them */
        public Frame(float crouch, float lean, float[] arms, @Nullable Act act, Act[] fading, Hit[] hits, float idleSec,
                int entity, float speed, float heading, float deathSec, float walk, float walkPos, float alert, float air,
                float dashed) {
            this(crouch, lean, arms, act, fading, hits, idleSec, entity, speed, heading, deathSec, walk, walkPos, alert, air,
                    dashed, Float.NaN, Float.NaN, null, Float.NaN, null);
        }

        /** a frame made by hand: in a dash while its action shows */
        public Frame(float crouch, float lean, float[] arms, @Nullable Act act, Act[] fading, Hit[] hits, float idleSec,
                int entity, float speed, float heading, float deathSec, float walk, float walkPos, float alert, float air) {
            this(crouch, lean, arms, act, fading, hits, idleSec, entity, speed, heading, deathSec, walk, walkPos, alert, air,
                    act != null && BotPoser.dash(act.kind()) ? 1F : 0F);
        }

        /** on the ground */
        public Frame(float crouch, float lean, float[] arms, @Nullable Act act, Act[] fading, Hit[] hits, float idleSec,
                int entity, float speed, float heading, float deathSec, float walk, float walkPos, float alert) {
            this(crouch, lean, arms, act, fading, hits, idleSec, entity, speed, heading, deathSec, walk, walkPos, alert, 0F);
        }

        /** the weight of one arms way (AiPose.HOLD ..): none for a way the frame was made without */
        public float arm(int way) {
            return way < arms.length ? arms[way] : 0F;
        }

        /** in a fight */
        public Frame(float crouch, float lean, float[] arms, @Nullable Act act, Act[] fading, Hit[] hits, float idleSec,
                int entity, float speed, float heading, float deathSec, float walk, float walkPos) {
            this(crouch, lean, arms, act, fading, hits, idleSec, entity, speed, heading, deathSec, walk, walkPos, 1F);
        }

        /** standing where it is, alive */
        public Frame(float crouch, float lean, float[] arms, @Nullable Act act, Act[] fading, Hit[] hits, float idleSec,
                int entity) {
            this(crouch, lean, arms, act, fading, hits, idleSec, entity, 0F, 0F, -1F);
        }

        /** with no walk of its own (the stands and tests give vanilla's) */
        public Frame(float crouch, float lean, float[] arms, @Nullable Act act, Act[] fading, Hit[] hits, float idleSec,
                int entity, float speed, float heading, float deathSec) {
            this(crouch, lean, arms, act, fading, hits, idleSec, entity, speed, heading, deathSec, 0F, 0F);
        }
    }

    /** how fast the speed it is drawn at follows its own, a share a tick (a step's jolt smoothed out) */
    static final float SPEED_FOLLOW = 0.2F;
    /** vanilla's walk follows its step by this share a tick (LivingEntity.updateWalkAnimation) */
    static final float WALK_FOLLOW = 0.4F;
    /**
     * a hit as the game throws it this fast or more, blocks a tick (the motion it is sent: a blast's push): the
     * flinch that throws it back. A hit's own knockback sends 0.2 to 0.4, a bot running 0.2: neither is thrown
     */
    static final float KNOCK_PUSH = 0.45F;
    /** a step this long in a tick is no step: it was put there (a teleport), blocks */
    static final float TELEPORT = 1.5F;
    /** ticks after it is first seen, seen again after a while or put somewhere: its speed and lean are its steps' */
    static final float FRESH = 3F;
    private float freshFor;
    /** ticks after a hit its push may still come in (the motion is sent after the hit, with the tick's moves) */
    static final float KNOCK_WAIT = 2F;
    /** the most the heading it is drawn at turns, radians a tick */
    static final float HEADING_TURN = 0.5F;
    /**
     * how fast the heading follows the way it goes, a share a tick: a peek's dash aside is some 6 to 20 ticks, and
     * the legs step aside from its start
     */
    static final float HEADING_FOLLOW = 0.5F;
    /**
     * stopped mid-step (or creeping, slower than the clips come in at), the legs go on this fast (vanilla's walkPos a
     * tick) to where they pass each other (vanilla's swing at nought): it ends its step and stands, not held mid-stride
     * while the clips fade
     */
    static final float SETTLE = 0.5F;
    /** and slowing down to it: this share of the way left a tick, this much at least */
    static final float SETTLE_EASE = 0.3F, SETTLE_LEAST = 0.05F;

    /**
     * A flinch this frame.
     *
     * @param clip BotClips.HIT_*
     * @param sec  seconds into it
     * @param w    its weight, 0 .. 1
     */
    public record Hit(String clip, float sec, float w) {
    }

    static final Act[] NO_ACTS = {};
    static final Hit[] NO_HITS = {};
    /** ticks a flinch takes to come in, to go at its end, and to give way to the next or to death */
    static final float HIT_IN = 1F, HIT_OUT = 3F;

    /**
     * An action this frame.
     *
     * @param kind AiPose.ACT_*
     * @param u    0 .. 1 through it
     * @param w    its weight, 0 .. 1
     * @param t    ticks since it began
     */
    public record Act(byte kind, float u, float w, float t) {
    }

    private double last = Double.NaN;
    private float crouch;
    /** in a fight, by weight: comes and goes over ALERT_TICKS; a first look takes it at once */
    private float alert;
    private final float[] arms = { 1F, 0F, 0F, 0F, 0F };
    private byte actKind = AiPose.NONE;
    private int actTick, actLen;
    private float actW;
    /** an action cut short: what, from when, how long, its weight when cut and when that was */
    private record Out(byte kind, int tick, int len, float w, double at) {
    }

    /** a flinch cut short: which, when it began, its weight when cut and when that was */
    private record OutHit(String clip, double began, float w, double at) {
    }

    private final java.util.ArrayList<Out> outs = new java.util.ArrayList<>(2);
    private final java.util.ArrayList<OutHit> outHits = new java.util.ArrayList<>(2);
    private int lastHurt;
    private double hitAt = Double.NEGATIVE_INFINITY;
    private float hitW;
    private int hits;

    /** the speed and heading it is drawn at (Frame) */
    float speed, heading;
    /**
     * The trunk's weight (anim-feel stage 2; people: notes/anim-feel/stage2-people.md): it leans about the neck into
     * how its going changes, ahead starting off ({@link #LEAN_AHEAD}), back pulling up ({@link #LEAN_BACK}), aside into
     * a turn or a strafe ({@link #LEAN_SIDE}), each a little late; stood still it settles back over a little. The
     * velocity drawn (blocks a tick, x z); the lean (rad, ModelPart's xRot: the chest ahead, zRot: to its left) and how
     * fast each goes, a second.
     */
    private float velX, velZ, velXv, velZv, tiltXv, tiltZv;
    float tiltX, tiltZ;
    /** rad a m/s² ahead (people starting a run: 7.2 deg, a peak of 34 with the speed's own), at most (soft) */
    static final float LEAN_AHEAD = 0.126F, LEAN_AHEAD_MOST = 0.40F;
    /** rad a m/s² back (people pulling up from a run: 3.4 deg, a peak of 6.6, p10 12.6), at most */
    static final float LEAN_BACK = 0.059F, LEAN_BACK_MOST = 0.22F;
    /** rad a m/s² aside, at a walk and at a run (people: 2.3 and 3.2 deg of v w), at most (p90 of a run's 11 deg) */
    static final float LEAN_SIDE = 0.041F, LEAN_SIDE_RUN = 0.057F, LEAN_SIDE_MOST = 0.20F;
    /**
     * blocks a tick of people's run (3.8 m/s) the most leans are of: going slower, as much less as the square of the
     * share (people starting off at a walk lean 5 degrees, not the run's 23; aside in a turn 2.4, not 8.7). The bot
     * comes to its pace in a third of a second, people in a second: its pull is far more, the lean it makes not
     */
    static final float LEAN_PACE = 0.19F;
    /**
     * The velocity the lean goes by follows the steps the client gets on a critically damped spring of this (1/s), and
     * a pull under LEAN_STILL (m/s²) leans nothing: a server falling behind sends a mob's steps two in a tick and none
     * in the next, and its pace over a third of a second wavers between 2 and 4 m/s, as a start's own does, but less
     */
    static final float LEAN_FOLLOW = 6F, LEAN_STILL = 3F;
    /**
     * blocks a tick: a step this much longer than twice the pace it goes at is no going (a server falling behind
     * sends two of a sprint's 0.33 in a tick) but a mob put somewhere near or knocked back (the client's three steps
     * to a place a few blocks off, KNOCK_PUSH): the lean starts over from standing upright
     */
    static final float LEAN_PUT = KNOCK_PUSH;
    /**
     * s the pace it pulls up from is kept for, then let go over as long: the speed drawn falls in a fifth of a second,
     * people's lean back is at its most a quarter of a second in
     */
    static final float LEAN_PACE_KEPT = 0.5F;
    /** the share of LEAN_PACE it has been going at (LEAN_PACE_KEPT) and s since it was last so fast */
    private float leanPace, leanPaceFor;
    /**
     * the lean's spring, 1/s and its damping: on the move as late as people's (125 ms ahead and back; 160 ms aside at a
     * walk, 330 at a run), none over; stood still slow and over a little (people: 13 % of the way, a fifth of a second
     * past the stop's peak to a degree in 2.2 s)
     */
    static final float LEAN_SPRING = 16F, LEAN_SIDE_SPRING = 13F, LEAN_SIDE_SPRING_RUN = 6F, SETTLE_SPRING = 4F,
            SETTLE_DAMP = 0.5F;
    /** hits' pushes to the trunk; the health and the motion (blocks a tick) it had last frame; the push sampled */
    final BotImpulse impulse = new BotImpulse();
    private float lastHealth = Float.NaN;
    private double motionX, motionZ;
    private final float[] kickNow = new float[6];
    /** blocks a tick: a change of the motion less than this with a hit is no push (from the front) */
    static final float PUSHED = 0.02F;
    /** the gait drawn (Frame.gait) and how fast it changes, a second */
    float gait = Float.NaN;
    private float gaitVel;
    /**
     * how fast the gait drawn goes to the speed's (a critically damped spring, 1/s): half way in some 0.14 s, as the
     * body turns calm (BODY_SPRING)
     */
    static final float GAIT_SPRING = 12F;
    /**
     * Its walk: vanilla's (the swing's speed follows 4 times the step, a block a tick at most, by 0.4 a tick; its
     * place goes on by that speed), worked out frame by frame. Not vanilla's own: a hit sets that to 1.5 at once, in
     * the middle of a tick, and the legs and arms would kick; the flinch is the hit's clip.
     */
    float walk, walkPos;
    private double movedAt = Double.NaN;

    /** @param crouching vanilla's crouch (the mob's own pose), taken as a crouch too */
    public static Frame frame(LivingEntity e, AiPose pose, long gameTime, float partial, boolean crouching) {
        BotAnimator a = ALL.computeIfAbsent(e, k -> new BotAnimator());
        synchronized (a) {
            double now = gameTime + (double) partial;
            // the turn and the feet go by the mob's own ticks on this client: the world's time steps back when the
            // server, behind, sends its own (and their springs would stand still till it came back)
            double own = e.tickCount + (double) partial;
            // in a fight or on the move the yaws the server sent last (the body's, the head's), each drawn going there
            // over the time between two (BotAnimator.Sent): the mob AI turns the body and the head with the aim
            // (AiGunner.face; on the run the head alone), a mob on the move faces its way and looks with its head;
            // else the game's own body and head as drawn (a calm mob looking round: its body follows its head later)
            boolean fight = pose.alert() == AiPose.FIGHT, moving = a.speed > BotPoser.MOVE_FROM;
            float sent = a.sent.at(e.getInterpolation().yRot(), own), head = a.sentHead.at(headSent(e), own);
            a.turn(fight || moving ? sent : Mth.rotLerp(partial, e.yBodyRotO, e.yBodyRot),
                    fight || moving ? head : Mth.rotLerp(partial, e.yHeadRotO, e.yHeadRot), Math.max(a.alert, a.arms[AiPose.AIM]), own);
            a.move(e.getX() - e.xo, e.getZ() - e.zo, a.bodyYaw, now, BotGaitOverride.of(e.getId()));
            // the push a hit gives it comes as its motion before it shows as steps
            a.push = (float) e.getDeltaMovement().horizontalDistance();
            a.fly(!e.onGround() && !e.isInWater() && !e.isInLava() && !e.isPassenger() && !e.onClimbable() && !e.isFallFlying(),
                    e.getY() - e.yo, gameTime + (double) partial);
            Frame f = a.step(pose, gameTime, partial, e.hurtTime, e.deathTime > 0, e.tickCount + partial, e.getId(),
                    crouching);
            float death = e.deathTime > 0 ? (e.deathTime - 1 + partial) / 20F : -1F;
            // the feet stand where they were put: standing upright and alive, not crouched, not in the air or a dash
            float stand = (1F - smooth(f.crouch())) * (1F - a.air) * (1F - f.dashed())
                    * (1F - smooth((a.speed - LOCK_FROM) / (LOCK_FULL - LOCK_FROM)));
            if (e.deathTime > 0 || e.getSwimAmount(partial) > 0F || e.isPassenger()) stand = 0F;
            // a move from recordings (BotMoves: a turn on the spot, played by its own turn) standing fully: the feet's
            // lock keeps the feet it has down
            a.moves(stand >= 1F - 1e-4F, own, fight || moving ? sent : Mth.rotLerp(partial, e.yBodyRotO, e.yBodyRot));
            var at = e.getPosition(partial);
            a.feet(at.x, at.z, stand, own);
            // a hit's push to the trunk (BotImpulse): as the health it took shows (twice its share of all, as a
            // flinch's), the way the push it got goes: the motion the game sent with it less the motion it had (its own
            // run), from the front with none (the way it faces is mostly the foe's)
            float health = e.getHealth();
            var motion = e.getDeltaMovement();
            if (health < a.lastHealth && e.hurtTime > 0 && e.deathTime <= 0) {
                double px = motion.x - a.motionX, pz = motion.z - a.motionZ;
                if (px * px + pz * pz < PUSHED * PUSHED) px = pz = 0.0;
                float[] way = toModel(px, pz, a.bodyYaw);
                a.impulse.hit(way[0], 0F, way[1], Float.NaN, Float.NaN, Float.NaN, 2F * (a.lastHealth - health) / e.getMaxHealth(),
                        (float) (own / 20.0));
            }
            a.lastHealth = health;
            a.motionX = motion.x;
            a.motionZ = motion.z;
            // a new array only while a push or a lean shows: the frame keeps it. The lean not in a dash (its clip) nor in
            // the air
            boolean pushed = a.impulse.sample((float) (own / 20.0), a.kickNow);
            float leans = (1F - smooth(f.dashed())) * (1F - smooth(a.air));
            float[] kick = null;
            if (pushed || leans > 0F && (a.tiltX != 0F || a.tiltZ != 0F)) {
                kick = pushed ? a.kickNow.clone() : new float[6];
                kick[0] += a.tiltX * leans;
                kick[2] += a.tiltZ * leans;
            }
            return new Frame(f.crouch(), f.lean(), f.arms(), f.act(), f.fading(), f.hits(), f.idleSec(), f.entity(),
                    a.speed, a.heading, death, a.walk, a.walkPos, f.alert(), a.air, f.dashed(), Mth.wrapDegrees(a.bodyYaw),
                    Mth.wrapDegrees(a.headYaw), a.feetOut.snapshot(), a.gait, kick, a.moving());
        }
    }

    /**
     * ticks falling before the legs leave their step (a step down a slab or a stair, 4 ticks of it, is no jump; going
     * up, a jump, they leave it at once), and ticks they take to the jump's pose and back
     */
    static final float AIR_FROM = 5F, AIR_TICKS = 3F;
    /** blocks up in a tick from which it is going up; blocks up or down in a tick under which it does not move */
    static final double RISING = 0.02, HANGING = 1e-3;
    /** ticks not moving up or down after which it is not in the air, whatever the game says (a jump's top is one) */
    static final float HANG_MOST = 2F;
    /** in the air, by weight (Frame) */
    float air;
    private float airFor, hungFor;
    private double airAt = Double.NaN;

    /**
     * Off the ground or on it, this frame, and its step up (down: under nought) since the last tick. A mob the client
     * has just begun to see is "off the ground" till its first move comes (the game sends the flag only with a move),
     * standing still for seconds: so off the ground counts only while it moves up or down.
     */
    void fly(boolean off, double dy, double now) {
        float dt = Double.isNaN(airAt) ? Float.POSITIVE_INFINITY : (float) Math.max(0.0, now - airAt);
        airAt = now;
        boolean moving = Math.abs(dy) > HANGING;
        if (dt > 20F) {
            // a first look, or out of sight a while: as it is
            off &= moving;
            airFor = off ? AIR_FROM : 0F;
            hungFor = 0F;
            air = off ? 1F : 0F;
            return;
        }
        hungFor = moving ? 0F : hungFor + dt;
        off &= hungFor < HANG_MOST;
        airFor = !off ? 0F : dy > RISING ? Math.max(airFor + dt, AIR_FROM) : airFor + dt;
        air = approach(air, off && airFor >= AIR_FROM ? 1F : 0F, dt / AIR_TICKS);
    }

    /**
     * Its step since the last tick (the client moves it a tick at a time): the speed it is drawn at follows it by
     * SPEED_FOLLOW a tick, the heading (from where the body faces) turns to it while it moves.
     */
    void move(double dx, double dz, float bodyYaw, double now) {
        move(dx, dz, bodyYaw, now, null);
    }

    /** ... {@code swap}: a stand's gait clips in place of the mod's, each at its own pace (BotPoser.pace) */
    void move(double dx, double dz, float bodyYaw, double now, @Nullable BotGaitOverride swap) {
        float dt = Double.isNaN(movedAt) ? Float.POSITIVE_INFINITY : (float) Math.max(0.0, now - movedAt);
        movedAt = now;
        float s = (float) Math.sqrt(dx * dx + dz * dz);
        if (s > TELEPORT) {
            // put somewhere (a teleport, a ride): it stands where it is now
            speed = walk = gait = gaitVel = velX = velZ = velXv = velZv = tiltX = tiltZ = tiltXv = tiltZv = leanPace = leanPaceFor = 0F;
            freshFor = 0F;
            return;
        }
        // first seen, seen again after a while or just put somewhere: it comes to the pace its steps go at once
        // (followed from nought, a mob coming in at a run or put back on its way would be drawn walking slow, its
        // feet sliding); a step short or none (a late packet) is followed as ever
        freshFor = dt > 20F ? 0F : freshFor + dt;
        boolean fresh = freshFor <= FRESH;
        float k = fresh && s >= speed ? 1F : 1F - (float) Math.pow(1F - SPEED_FOLLOW, dt);
        // from a stand (the clips hardly in yet) the heading takes the way it goes at once
        float stand = fresh ? 1F : 1F - smooth((speed - BotPoser.MOVE_FROM) / (BotPoser.MOVE_FULL - BotPoser.MOVE_FROM));
        speed += (s - speed) * k;
        weigh(dx, dz, bodyYaw, dt, fresh);
        // the gait by the speed, over a few frames: the speed drawn wavers by a hundredth with the steps the client
        // gets, and from a jog to a sprint is a band of 0.015 (the run's lean and the clips would go over in a frame)
        float gaitTo = BotPoser.gaitOf(speed);
        if (fresh || Float.isNaN(gait)) {
            gait = gaitTo;
            gaitVel = 0F;
        } else {
            float[] g = spring(gait, gaitVel, gaitTo, GAIT_SPRING, dt / 20F);
            gait = g[0];
            gaitVel = g[1];
        }
        float walkTo = Math.min(s * 4F, 1F), kw = fresh && walkTo >= walk ? 1F : 1F - (float) Math.pow(1F - WALK_FOLLOW, dt);
        float walkWas = walk;
        walk += (walkTo - walk) * kw;
        if (dt <= 20F) {
            // on the run, fewer steps by the player's choice (Config.botRunGait tempo; vanilla's pace at a walk)
            float on = dt * BotPoser.pace((walkWas + walk) / 2F * BotPoser.tempo(speed), speed, gait, heading, crouch, swap);
            if (s < BotPoser.MOVE_FROM) {
                // only where it goes on faster than its fading walk would: a tick with no step in a run (a late
                // packet) leaves the run's pace as it was
                float rest = toRest(walkPos), settle = Math.min(SETTLE, Math.max(rest * SETTLE_EASE, SETTLE_LEAST)) * dt;
                if (settle > on) on = Math.min(rest, settle);
            }
            walkPos += on;
        }
        if (s > 1e-3F) {
            // the game's yaw: 0 is +z, 90 is -x; the mob's right is 90 more than where it faces
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float to = Mth.wrapDegrees(yaw - bodyYaw) * Mth.DEG_TO_RAD;
            // and no faster than HEADING_TURN: turned right round (a sprint ahead, then back) the legs go from one
            // clip to the next over a third of a second, not in a tick; from a stand at once
            float kh = Math.max(stand, 1F - (float) Math.pow(1F - HEADING_FOLLOW, Math.min(dt, 20F)));
            float turn = Mth.wrapDegrees((to - heading) * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD * kh, most = Mth.lerp(stand, Math.min(HEADING_TURN * dt, Mth.PI), Mth.PI);
            heading = Mth.wrapDegrees((heading + Mth.clamp(turn, -most, most)) * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD;
        }
    }

    /** the trunk's weight (tiltX, tiltZ) for the steps dx dz this frame, dt ticks after the last */
    private void weigh(double dx, double dz, float bodyYaw, float dt, boolean first) {
        double step = Math.sqrt(dx * dx + dz * dz);
        boolean fresh = first || Float.isNaN(bodyYaw);
        if (fresh || step > LEAN_PUT + 2.0 * Math.sqrt(velX * velX + velZ * velZ)) {
            // first seen (or after a long while) it goes on at the pace it has, upright; put somewhere it stands
            boolean going = fresh && step <= LEAN_PUT;
            velX = going ? (float) dx : 0F;
            velZ = going ? (float) dz : 0F;
            velXv = velZv = tiltX = tiltZ = tiltXv = tiltZv = leanPace = leanPaceFor = 0F;
            return;
        }
        if (!(dt > 0F)) return;
        float sec = dt / 20F, vx = velX, vz = velZ;
        float[] fx = spring(velX, velXv, (float) dx, LEAN_FOLLOW, sec), fz = spring(velZ, velZv, (float) dz, LEAN_FOLLOW, sec);
        velX = fx[0];
        velXv = fx[1];
        velZ = fz[0];
        velZv = fz[1];
        // m/s² at the bot's size (a block a metre, 20 ticks a second), along its left (+x) and its back (+z); less
        // LEAN_STILL
        float[] m = toModel((velX - vx) * 20F / sec, (velZ - vz) * 20F / sec, bodyYaw);
        float left = m[0] / 16F, back = m[1] / 16F, pull = (float) Math.sqrt(left * left + back * back);
        float on = pull > LEAN_STILL ? (pull - LEAN_STILL) / pull : 0F;
        left *= on;
        back *= on;
        float pace = Mth.clamp(Math.max(speed, (float) Math.sqrt(dx * dx + dz * dz)) / LEAN_PACE, 0F, 1F);
        if (pace >= leanPace) {
            leanPace = pace;
            leanPaceFor = 0F;
        } else {
            leanPaceFor += sec;
            if (leanPaceFor > LEAN_PACE_KEPT) leanPace = Math.max(pace, leanPace - sec / LEAN_PACE_KEPT);
        }
        float most = leanPace * leanPace;
        float ahead = back < 0F ? soft(-back * LEAN_AHEAD, LEAN_AHEAD_MOST * most) : -soft(back * LEAN_BACK, LEAN_BACK_MOST * most);
        float run = Mth.clamp(gait, 0F, 1F);
        float aside = left * Mth.lerp(run, LEAN_SIDE, LEAN_SIDE_RUN);
        aside = Math.copySign(soft(Math.abs(aside), LEAN_SIDE_MOST * most), aside);
        // on the move each follows its own quick spring; stood still both settle on the slow one
        float go = smooth((speed - BotPoser.MOVE_FROM) / (BotPoser.MOVE_FULL - BotPoser.MOVE_FROM));
        float damp = Mth.lerp(go, SETTLE_DAMP, 1F);
        float[] x = spring(tiltX, tiltXv, ahead, Mth.lerp(go, SETTLE_SPRING, LEAN_SPRING), damp, sec);
        float[] z = spring(tiltZ, tiltZv, aside, Mth.lerp(go, SETTLE_SPRING, Mth.lerp(run, LEAN_SIDE_SPRING, LEAN_SIDE_SPRING_RUN)), damp, sec);
        tiltX = x[0];
        tiltXv = x[1];
        tiltZ = z[0];
        tiltZv = z[1];
        // come to rest it is upright, not a ten-thousandth off for a minute (the frame's lean then left out)
        if (Math.abs(tiltX) < 1e-4F && Math.abs(tiltXv) < 1e-3F) tiltX = tiltXv = 0F;
        if (Math.abs(tiltZ) < 1e-4F && Math.abs(tiltZv) < 1e-3F) tiltZ = tiltZv = 0F;
    }

    /** v as it is while well under most, going on to most softly (tanh); none if most is */
    static float soft(float v, float most) {
        return most > 1e-6F ? most * (float) Math.tanh(v / most) : 0F;
    }

    /** a spring of any damping (1 none over), x to {@code to}: steps short enough for its omega */
    static float[] spring(float x, float v, float to, float omega, float damp, float sec) {
        int n = Math.max(1, (int) Math.ceil(sec * omega / 0.2F));
        float h = sec / n;
        for (int i = 0; i < n; i++) {
            v += (omega * omega * (to - x) - 2F * damp * omega * v) * h;
            x += v * h;
        }
        return new float[] { x, v };
    }

    // ---------------------------------------------------------------- the turn

    /**
     * The yaw the server sends, as a line through the yaws it sent: each one reached over the time it took to come
     * after the one before (a tick for an AI mob turning, MixinAiTurnSync; up to the game's three ticks for others).
     * A turn then goes on at the server's pace a tick late, not in steps.
     */
    static final class Sent {
        /** a yaw unchanged this long (ticks) is reached over it at most: the game sends a mob's yaw every third tick */
        static final double LONGEST = 3.0;
        private float last = Float.NaN, from, to;
        private double at, start, length = 1.0;

        /** the yaw to draw now (degrees, not wrapped) with `yaw` the last one sent */
        float at(float yaw, double now) {
            if (Float.isNaN(last) || now - at > 20.0 || now < at - BACK) {
                last = yaw;
                from = to = yaw;
                at = start = now;
                return yaw;
            }
            float change = Mth.wrapDegrees(yaw - last);
            if (Math.abs(change) > 1e-3F) {
                float here = value(now);
                length = Mth.clamp(now - at, 1.0, LONGEST);
                from = here;
                to += change;
                start = at = now;
                last = yaw;
            }
            return value(now);
        }

        private float value(double now) {
            return Mth.lerp((float) Mth.clamp((now - start) / length, 0.0, 1.0), from, to);
        }
    }

    final Sent sent = new Sent(), sentHead = new Sent();

    /** the head's yaw the server sent last (the client turns the head to it over three ticks), or the head's own */
    static float headSent(LivingEntity e) {
        var head = (acup.gunfeel.mixin.mc.LivingEntityAccess) e;
        return head.gunfeel$lerpHeadSteps() > 0 ? (float) head.gunfeel$lerpYHeadRot() : e.getYHeadRot();
    }

    /**
     * Ticks gone back by less than this are taken for none (a second look in a frame at an earlier part of the tick);
     * by more, the mob's ticks have begun again (it was sent anew) and its turn and feet start over
     */
    static final double BACK = 1.0;

    /**
     * How fast the body drawn follows the yaw it turns to, a critically damped spring, rad/s: it follows a steady turn
     * 2/ω behind, the yaw sent a tick behind the server's (Sent). Ready to fire (in a fight, or aiming) half a turn is
     * drawn 0.1 s after the server's: the gun points near enough where the server fires it (NTM fires along the
     * server's body); else 0.2 s, nearer a person's ease into and out of a turn (tools/motion feel.py: people's chest
     * takes some 600 deg/s² at most, the server's aim 30 degrees a tick)
     */
    static final float BODY_SPRING_READY = 40F, BODY_SPRING = 13F;
    /** the head's, twice as quick: it leads the body into a turn, as a person's does */
    static final float HEAD_SPRING = 2F;
    /** the body and the head as drawn, degrees, not wrapped; their speed, degrees a second */
    float bodyYaw = Float.NaN, headYaw = Float.NaN;
    private float bodyVel, headVel;
    private double turnedAt = Double.NaN;

    /**
     * The body and the head drawn turning to these yaws (degrees) by their springs. In a fight, or on the move, the
     * yaw the server sent: the mob AI turns the body and the head with the aim (AiGunner.face), a mob on the move
     * faces its way; else the game's own body and head (a calm mob looking round: its body follows its head later).
     *
     * @param ready 0 .. 1 how ready to fire: the body follows by BODY_SPRING_READY then
     */
    void turn(float body, float head, float ready, double now) {
        // a first look, or its ticks begun again (the mob sent anew); a look back a little within a tick changes nothing
        if (!Double.isNaN(turnedAt) && now < turnedAt && now >= turnedAt - BACK) return;
        float dt = Double.isNaN(turnedAt) || now < turnedAt ? Float.POSITIVE_INFINITY : (float) (now - turnedAt) / 20F;
        turnedAt = now;
        if (dt > 1F || Float.isNaN(bodyYaw)) {
            // a first look, or out of sight a while: where it is
            bodyYaw = body;
            headYaw = head;
            bodyVel = headVel = 0F;
            return;
        }
        float w = Mth.lerp(Mth.clamp(ready, 0F, 1F), BODY_SPRING, BODY_SPRING_READY);
        float[] b = spring(bodyYaw, bodyVel, bodyYaw + Mth.wrapDegrees(body - bodyYaw), w, dt);
        bodyYaw = b[0];
        bodyVel = b[1];
        float[] h = spring(headYaw, headVel, headYaw + Mth.wrapDegrees(head - headYaw), w * HEAD_SPRING, dt);
        headYaw = h[0];
        headVel = h[1];
    }

    /** a critically damped spring from x moving at v to `to`, over dt seconds: x and v after */
    static float[] spring(float x, float v, float to, float omega, float dt) {
        float off = x - to, k = (float) Math.exp(-omega * dt), lead = (v + omega * off) * dt;
        return new float[] { to + (off + lead) * k, (v - omega * lead) * k };
    }

    // ---------------------------------------------------------------- the moves (BotMoves)

    /**
     * a move's clip at its time and weight, for BotPoser: its hips down by drop (px), its legs turned about the mob's
     * upright by twist (radians, the model's yRot) from the body
     */
    public record Moving(BotClip clip, float t, float w, float drop, float twist) {
    }

    public static final Moving[] NO_MOVES = {};

    /**
     * A turn on the spot is played from recordings (BotMoves) no faster than this, degrees a second of the body as
     * drawn (people turn on the spot at 160 to 220 at most): an aim snapping round goes on the feet's own steps
     */
    static final float MOVE_TURN_MOST = 150F;
    /**
     * ... and goes out turning faster than this (the start's bound below it: a turn about MOVE_TURN_MOST does not
     * start and stop it frame by frame), or its legs turned farther than MOVE_TWIST_MOST from the body (a turn on past
     * the clip's: the feet's lock steps on, and a move starts again from there)
     */
    static final float MOVE_TURN_OUT = 180F, MOVE_TWIST_MOST = 45F;
    /**
     * ... from the body this far from where the feet stand (degrees), turning at least MOVE_STILL a second, and the yaw
     * it goes to no farther than MOVE_AHEAD ahead (else a snap is coming: the body will go past MOVE_TURN_MOST)
     */
    static final float MOVE_TURN_FROM = 20F, MOVE_STILL = 20F, MOVE_AHEAD = 30F;
    /** ticks the turn has stood before the move goes on to both feet down; ticks it goes in and out over */
    static final float MOVE_STILL_TICKS = 1F, MOVE_FADE = 3F;
    /**
     * a move goes on at least as fast as it was made, the body turning, while no farther than this ahead of it
     * (degrees): turning slower than it was made (its end, the body coming to stand) it does not stand still, a foot in
     * the air
     */
    static final float MOVE_LEAD = 15F;
    /**
     * a move goes at most this many times as fast as it was made (its first steps, made before the body turns much,
     * are not played in a frame); the legs are turned meanwhile by as much as the body is ahead of it (twist), its feet
     * standing where it puts them
     */
    static final float MOVE_PACE_MOST = 3F;
    /** stood, it goes on to both feet down this many times as fast as it was made, at most MOVE_SETTLE ticks */
    static final float MOVE_SETTLE_PACE = 1.5F, MOVE_SETTLE = 20F;
    /** how fast the body's turning drawn is followed, 1/s */
    static final float RATE_FOLLOW = 12F;
    /** a quarter turn goes over to the half this far into it, still turning */
    static final float MOVE_HALF_FROM = 0.9F;

    enum MoveStage {
        /** by its turn */
        PLAY,
        /** the turn stood: on by the clock to both feet down where it has caught up with the body */
        SETTLE,
        /** going out, the feet standing where it left them */
        FADE
    }

    private BotMoves.@Nullable Move move, moveOut;
    private MoveStage moveStage = MoveStage.FADE;
    private float moveT, moveW, moveFrom, moveSide, moveStill, moveSettled, moveTo, moveOutT, moveOutW;
    /** the body's turning drawn, degrees a second (+ to its right), smoothed */
    float yawRate;
    private float lastYaw = Float.NaN;
    private double movesAt = Double.NaN;
    /** a move holds the legs (PLAY, SETTLE): the feet's lock keeps each foot the move has down (moveDown) where it came down */
    boolean moveFeet;
    /** each foot (right, left) down in the move now; how far through its step one lifted is (BotMoves.Move.swing) */
    final boolean[] moveDown = new boolean[2];
    final float[] moveSwing = new float[2];
    /** the move went over to another clip this frame: a foot in a step goes on from where it is */
    boolean moveSwitched;
    /** how far the moves turn the legs from the body as drawn, degrees (+ to its right), by their weights */
    float legTwist;
    /** ticks after a move the feet's lock steps a foot its leg cannot reach (MOVE_SHORT) */
    private float settleFor;
    static final float SETTLE_TICKS = 12F;

    void moves(boolean free, double now) {
        moves(free, now, Float.NaN);
    }

    /**
     * The moves this frame ({@code free}: standing upright and still, alive, not crouched, not in the air or a dash;
     * the body drawn going to {@code target}, NaN unknown). Standing with its feet down and its body turning by
     * MOVE_STILL to MOVE_TURN_MOST a second, MOVE_TURN_FROM from where the feet stand: the quarter turn of its side from
     * its first time both feet stand, the legs turned to where the feet stand (twist) and the clip going on to where the
     * body has turned (Move.timeAt), at most MOVE_PACE_MOST times as fast as it was made; the half turn from where the
     * quarter has come once the body is past MOVE_HALF_FROM of it. The feet's lock keeps each foot the clip has down
     * where it came down (feet). Turning faster or no longer free it goes out at once; stood, it goes on to both feet
     * down where it has caught up with the body and goes out, the feet standing.
     */
    void moves(boolean free, double now, float target) {
        // a second look in a frame at an earlier part of the tick (a shadow, the hitbox taken): as it was
        if (!Double.isNaN(movesAt) && now < movesAt && now >= movesAt - BACK) return;
        float dt = Double.isNaN(movesAt) || now < movesAt ? Float.POSITIVE_INFINITY : (float) (now - movesAt);
        movesAt = now;
        moveFeet = moveSwitched = false;
        if (!(dt <= 20F) || Float.isNaN(bodyYaw) || Float.isNaN(lastYaw)) {
            move = moveOut = null;
            moveW = moveOutW = legTwist = yawRate = settleFor = 0F;
            lastYaw = bodyYaw;
            return;
        }
        float sec = dt / 20F;
        if (sec > 0F) yawRate += ((bodyYaw - lastYaw) / sec - yawRate) * (1F - (float) Math.exp(-RATE_FOLLOW * sec));
        lastYaw = bodyYaw;
        settleFor = Math.max(0F, settleFor - dt);
        float rate = Math.abs(yawRate);
        if (move == null) {
            moveOut = null;
            legTwist = 0F;
            if (!free || !planted || !BotMoves.on()) return;
            float feetYaw = (footYaw[0] + footYaw[1]) / 2F, off = bodyYaw - feetYaw;
            if (Math.abs(off) < MOVE_TURN_FROM || rate < MOVE_STILL || rate > MOVE_TURN_MOST || off * yawRate <= 0F) return;
            if (!Float.isNaN(target) && Math.abs(Mth.wrapDegrees(target - bodyYaw)) > MOVE_AHEAD) return;
            BotMoves.Move m = BotMoves.turn(off < 0F, false);
            if (m == null) return;
            move = m;
            moveStage = MoveStage.PLAY;
            // from its first both feet down, standing there as it comes in (a step blended with the pose would be a foot
            // sliding low); a foot it put down before then the lock keeps where it stands, and its step takes it over
            moveT = m.downFrom(0F);
            moveW = moveStill = 0F;
            moveFrom = feetYaw;
            moveSide = off < 0F ? -1F : 1F;
        }
        BotMoves.Move m = move;
        float along = moveSide * (bodyYaw - moveFrom), length = m.clip().length();
        boolean turning = rate >= MOVE_STILL && yawRate * moveSide > 0F, back = rate >= MOVE_STILL && !turning;
        if (moveStage != MoveStage.FADE && (!free || rate > MOVE_TURN_OUT)) out();
        // the quarter gone over from goes on by the body's turn as it fades out
        if (moveOut != null) {
            moveOutT = Math.max(moveOutT, moveOut.timeAt(along));
            if ((moveOutW -= dt / MOVE_FADE) <= 0F) moveOut = null;
        }
        switch (moveStage) {
            case PLAY -> {
                // past the quarter turn, still turning: the half from where the quarter has come, the quarter fading out
                if (turning && m.total() < 150F && along > MOVE_HALF_FROM * m.total()) {
                    BotMoves.Move h = BotMoves.turn(moveSide < 0F, true);
                    if (h != null) {
                        moveOut = m;
                        moveOutT = moveT;
                        moveOutW = moveW;
                        float t = h.timeAt(m.turnedAt(moveT));
                        move = m = h;
                        length = h.clip().length();
                        moveT = t;
                        moveSwitched = true;
                    }
                }
                if (moveW >= 1F || moveOut != null) {
                    moveT = Math.max(moveT, Math.min(m.timeAt(along), moveT + sec * MOVE_PACE_MOST));
                    if (turning) moveT = Math.max(moveT, Math.min(moveT + sec, m.timeAt(along + MOVE_LEAD)));
                } else {
                    // coming in: as fast as it was made (its both feet down outlast it), no farther ahead than MOVE_LEAD
                    moveT = Math.max(moveT, Math.min(moveT + sec, m.timeAt(along + MOVE_LEAD)));
                }
                moveStill = turning ? 0F : moveStill + dt;
                if (moveStill >= MOVE_STILL_TICKS || moveT >= length) {
                    moveStage = MoveStage.SETTLE;
                    moveSettled = 0F;
                    moveTo = m.settleAt(moveT, along);
                }
            }
            case SETTLE -> {
                if (turning) {
                    moveStage = MoveStage.PLAY;
                    moveStill = 0F;
                } else if (back) out();
                else {
                    moveSettled += dt;
                    moveT = Math.min(moveTo, moveT + sec * MOVE_SETTLE_PACE);
                    if (moveT >= moveTo || moveSettled >= MOVE_SETTLE) out();
                }
            }
            case FADE -> {
            }
        }
        if (moveStage != MoveStage.FADE && Math.abs(twistOf(move, moveT, along)) > MOVE_TWIST_MOST) out();
        moveW = approach(moveW, moveStage == MoveStage.FADE ? 0F : 1F, dt / MOVE_FADE);
        if (moveStage == MoveStage.FADE && moveW <= 0F) {
            move = null;
            moveOut = null;
            legTwist = 0F;
            return;
        }
        moveFeet = moveStage != MoveStage.FADE;
        for (int k = 0; k < 2; k++) {
            moveDown[k] = move.down(k, moveT);
            moveSwing[k] = moveDown[k] ? 1F : move.swing(k, moveT);
        }
        // the legs turned as the moves have them, each over the one before (as BotPoser lays them)
        float twist = Mth.lerp(smooth(moveW), 0F, twistOf(move, moveT, along));
        if (moveOut != null) twist = Mth.lerp(smooth(moveOutW), twist, twistOf(moveOut, moveOutT, along));
        legTwist = twist;
    }

    /** the move goes out: the feet stand where it has them, a foot its leg will not reach stepping (SETTLE_TICKS) */
    private void out() {
        moveStage = MoveStage.FADE;
        settleFor = SETTLE_TICKS;
    }

    /** how far a move at t has its legs turned from the body, gone {@code along} the way, degrees (+ to its right) */
    private float twistOf(BotMoves.Move m, float t, float along) {
        return moveSide * (m.turnedAt(t) - along);
    }

    /** the moves for the frame: the one under way, the one it went over from on top fading out */
    Moving[] moving() {
        BotMoves.Move m = move, o = moveOut;
        if (m == null && o == null) return NO_MOVES;
        float along = moveSide * (bodyYaw - moveFrom);
        java.util.List<Moving> out = new java.util.ArrayList<>(2);
        if (m != null && moveW > 0F)
            out.add(new Moving(m.clip(), moveT, smooth(moveW), m.drop(), Mth.DEG_TO_RAD * twistOf(m, moveT, along)));
        if (o != null && moveOutW > 0F)
            out.add(new Moving(o.clip(), moveOutT, smooth(moveOutW), o.drop(), Mth.DEG_TO_RAD * twistOf(o, moveOutT, along)));
        return out.toArray(NO_MOVES);
    }

    // ---------------------------------------------------------------- the feet

    /** the feet stand from this speed down (blocks a tick, drawn smoothed), fully under it; the legs walk above */
    static final float LOCK_FROM = 0.006F, LOCK_FULL = 0.03F;
    /**
     * A foot standing steps when the pose would put it this far from where it stands (px; its leg leans 12 degrees
     * then), or turned this far from the body (degrees): on a turn on the spot, or a stance changed. One foot at a time,
     * in STEP_TICKS, lifted STEP_LIFT px at the middle: a person turns 90 degrees on the spot in two steps
     */
    static final float STEP_DIST = 2.5F, STEP_YAW = 40F, STEP_TICKS = 6F, STEP_LIFT = 1.5F;
    /**
     * In a step the foot goes over only in this part of it (from STEP_OVER to 1 - STEP_OVER), up off the floor: a
     * person lifts the foot first and sets it down after; a foot going over while still down would slide. The shin
     * box tips back as the knee bends to lift the foot, and its lower edge comes up some half as far as the ankle:
     * it is clear of the floor from about a third of the step
     */
    static final float STEP_OVER = 0.3F;
    /** a foot standing turned farther than this from the body (degrees) turns on the spot with it, as on the ball of it */
    static final float PIVOT = 70F;
    /** farther than this from where a foot stands (blocks: a teleport) it is put down again where the pose has it */
    static final double LOST = 1.0;
    /**
     * The feet are put down only bearing this much: coming to stand (up from a crouch, to a stop) they go with the
     * pose's till then. A crouch's foot put down where it was would stand out of the standing leg's reach (the poses put
     * the ankles some 5 px apart), and the knee would snap as a step brought it back in
     */
    static final float PLANT = 0.9F;
    /**
     * A foot standing farther than this from where the pose has it (px) is drawn along (the body going on faster than
     * the steps): a straight leg reaches a foot that far out only a fraction of a pixel short, and its knee bends to a
     * step with no snap
     */
    static final float DRAG = 3.5F;
    /**
     * A quick turn (the body faster than QUICK_TURN degrees a second: an aim snapping round; people turn on the spot
     * at some 160 to 220) steps up to QUICKEST times quicker, the other foot lifting as the first comes down: a person
     * turning that fast all but hops round, and the feet would else be drawn along (DRAG) half the turn
     */
    static final float QUICK_TURN = 300F, QUICKEST = 1.5F;

    /**
     * Where the feet go in the model, for BotPoser: right foot then left. The poser writes back where the pose put
     * each before (neutral), so the next frame knows where a step goes.
     */
    public static final class Feet {
        /** how much the feet stand where they were put: 0 the pose's own .. 1 */
        float w;
        /** each foot: its ankle's place in the model (x, z, px), lifted (px), turned from the body (radians) */
        final float[] at = new float[8];
        /** each foot: the ankle where the pose put it (x, y, z, px in the model), written by the poser */
        final float[] neutral;
        /** each foot: how far short of it its leg comes, straight (px), written by the poser */
        final float[] shortOf;

        Feet() {
            this(new float[] { -1.9F, 24F, 0F, 1.9F, 24F, 0F }, new float[2]);
        }

        private Feet(float[] neutral, float[] shortOf) {
            this.neutral = neutral;
            this.shortOf = shortOf;
        }

        /**
         * this frame's, for its Frame: the next frame's look (a shadow, the hitbox taken) does not change it under
         * the poser; where the pose puts the ankles still comes back to the animator
         */
        Feet snapshot() {
            Feet f = new Feet(neutral, shortOf);
            f.w = w;
            System.arraycopy(at, 0, f.at, 0, at.length);
            return f;
        }
    }

    final Feet feetOut = new Feet();
    /** each foot on the floor, blocks; the way it faces, degrees; in a step: how far (0 .. 1, below 0 none), from where */
    private final double[] footX = new double[2], footZ = new double[2], fromX = new double[2], fromZ = new double[2];
    private final float[] footYaw = new float[2], fromYaw = new float[2], stepU = { -1F, -1F }, lift = new float[2],
            stepPace = { 1F, 1F };
    private boolean planted;
    /**
     * each foot lifted by a move (BotMoves), and where it was then (blocks): it goes from there to where the clip has
     * it as its step goes, there by the time it is set down
     */
    private final boolean[] lifted = new boolean[2];
    private final double[] offX = new double[2], offZ = new double[2];
    /** how far through its step a lifted foot was then (BotMoves.Move.swing): the way left goes from there */
    private final float[] swing0 = new float[2], swingLift = new float[2];
    /**
     * a foot in a move's step is lifted this much more at the middle of it (px): the recordings' steps on a turn are
     * shuffles some centimetres up, on the mod's legs less, and would be drawn sliding over the floor
     */
    static final float MOVE_LIFT = 0.5F;
    /**
     * each foot's way from the legs as the pose has them when a move took the feet (degrees), going to none as it comes
     * in: the lock had them turned from the body; none once a move set it down
     */
    private final float[] moveYaw0 = new float[2];
    private boolean feetMoved;

    /** a move has lifted the foot */
    boolean lifted(int foot) {
        return lifted[foot];
    }
    /** a foot's leg falling this far short of it (px, MOVE_SHORT a step's need) after a move steps it */
    static final float MOVE_SHORT = 0.1F;
    private double feetAt = Double.NaN;
    /** the feet's weight as drawn, going to the one asked for over FEET_FADE ticks */
    private float feetW;
    static final float FEET_FADE = 4F;

    /**
     * The feet this frame: with the mob at (x, z) and its body drawn at bodyYaw. Standing (w over 0) each stays where
     * it was put while the body turns and sways over it, and steps to where the pose has it when that is too far or
     * turned too far; else the pose's.
     */
    void feet(double x, double z, float w, double now) {
        if (!Double.isNaN(feetAt) && now < feetAt && now >= feetAt - BACK) return;
        float dt = Double.isNaN(feetAt) || now < feetAt ? Float.POSITIVE_INFINITY : (float) (now - feetAt);
        feetAt = now;
        Feet out = feetOut;
        out.w = 0F;
        if (dt > 20F || Float.isNaN(bodyYaw)) {
            planted = false;
            feetW = 0F;
            return;
        }
        // in and out over a few ticks: a fall dead, a swim, a mount let go of the feet at once
        feetW = approach(feetW, Mth.clamp(w, 0F, 1F), dt / FEET_FADE);
        if (feetW <= 0F) {
            planted = false;
            return;
        }
        out.w = smooth(feetW);
        double[] nx = new double[2], nz = new double[2];
        for (int k = 0; k < 2; k++) {
            double[] o = toWorld(out.neutral[k * 3], out.neutral[k * 3 + 2], bodyYaw);
            nx[k] = x + o[0];
            nz[k] = z + o[1];
        }
        boolean lost = false;
        for (int k = 0; k < 2 && planted; k++)
            lost |= Math.hypot(footX[k] - nx[k], footZ[k] - nz[k]) > LOST || Math.abs(footYaw[k] - bodyYaw) > 180F;
        if (!planted || lost) {
            for (int k = 0; k < 2; k++) {
                footX[k] = nx[k];
                footZ[k] = nz[k];
                footYaw[k] = bodyYaw + legTwist;
                stepU[k] = -1F;
                lift[k] = 0F;
                lifted[k] = false;
                moveYaw0[k] = 0F;
            }
            planted = feetW >= PLANT;
        }
        // a move holding the legs (BotMoves): each foot it has down stands where it came down (the clip's own, its leg
        // reaching it); each it lifts goes as the clip has it, from where it stood to where the clip sets it down;
        // turned as the clip has it, from as the lock had it as it comes in. Its steps are the clip's
        if (moveFeet && !feetMoved) for (int k = 0; k < 2; k++) moveYaw0[k] = footYaw[k] - bodyYaw - legTwist;
        feetMoved = moveFeet;
        float stay = 1F - smooth(moveW);
        for (int k = 0; k < 2; k++) {
            boolean down = !moveFeet || moveDown[k];
            if (!down) {
                // lifted (or the clip gone over to another): from where it is, the way to the clip's left as its step goes
                if (!lifted[k] || moveSwitched) {
                    offX[k] = footX[k];
                    offZ[k] = footZ[k];
                    swing0[k] = moveSwing[k];
                    lifted[k] = true;
                }
                float v = swing0[k] >= 1F ? 1F : Mth.clamp((moveSwing[k] - swing0[k]) / (1F - swing0[k]), 0F, 1F), u = smooth(v);
                footX[k] = offX[k] + (nx[k] - offX[k]) * u;
                footZ[k] = offZ[k] + (nz[k] - offZ[k]) * u;
                float up = Mth.sin(Mth.PI * v);
                swingLift[k] = MOVE_LIFT * up * up;
            } else if (lifted[k]) {
                // set down where it is (the clip's own by the end of its step), turned as the clip has it
                lifted[k] = false;
                moveYaw0[k] = 0F;
            }
            if (!lifted[k]) swingLift[k] = 0F;
            if (moveFeet) {
                footYaw[k] = bodyYaw + legTwist + moveYaw0[k] * stay;
                stepU[k] = -1F;
                lift[k] = 0F;
            }
        }
        if (moveFeet) {
            for (int k = 0; k < 2; k++) {
                float[] m = toModel(footX[k] - x, footZ[k] - z, bodyYaw);
                out.at[k * 4] = m[0];
                out.at[k * 4 + 1] = m[1];
                out.at[k * 4 + 2] = swingLift[k];
                out.at[k * 4 + 3] = (footYaw[k] - bodyYaw - legTwist) * Mth.DEG_TO_RAD;
            }
            return;
        }
        for (int k = 0; k < 2; k++) {
            if (stepU[k] < 0F) continue;
            // a step: to where the pose has the foot now (it moves on as the body turns), lifted on the way
            stepU[k] = Math.min(1F, stepU[k] + dt * stepPace[k] / STEP_TICKS);
            // lifted first, it would wait where it stood as the body goes on
            double[] from = drag(fromX[k], fromZ[k], nx[k], nz[k]);
            fromX[k] = from[0];
            fromZ[k] = from[1];
            // and turned on the ball of it no farther than PIVOT from the body
            fromYaw[k] = bodyYaw + Mth.clamp(fromYaw[k] - bodyYaw, -PIVOT, PIVOT);
            float u = smooth(Mth.clamp((stepU[k] - STEP_OVER) / (1F - 2F * STEP_OVER), 0F, 1F));
            footX[k] = Mth.lerp(u, fromX[k], nx[k]);
            footZ[k] = Mth.lerp(u, fromZ[k], nz[k]);
            // the yaws are not wrapped (turn): no jump past +-180 in a half turn
            footYaw[k] = fromYaw[k] + u * (bodyYaw - fromYaw[k]);
            // lifted and set down slowing to nought: the leg (nearly straight) does not snap at the knee
            float up = Mth.sin(Mth.PI * stepU[k]);
            lift[k] = STEP_LIFT * up * up;
            if (stepU[k] >= 1F) {
                stepU[k] = -1F;
                lift[k] = 0F;
            }
        }
        for (int k = 0; k < 2; k++) {
            float off = footYaw[k] - bodyYaw;
            if (Math.abs(off) > PIVOT) footYaw[k] = bodyYaw + Math.copySign(PIVOT, off);
            if (stepU[k] < 0F) {
                double[] at = drag(footX[k], footZ[k], nx[k], nz[k]);
                footX[k] = at[0];
                footZ[k] = at[1];
            }
        }
        float pace = Mth.clamp(Math.abs(bodyVel) / QUICK_TURN, 1F, QUICKEST);
        {
            // the foot that most needs it steps: the other standing, or (a quick turn) over and coming down
            int pick = -1;
            float most = 1F;
            for (int k = 0; k < 2; k++) {
                if (stepU[k] >= 0F || stepU[1 - k] >= 0F && (pace <= 1F || stepU[1 - k] < 1F - STEP_OVER)) continue;
                float far = (float) Math.hypot(footX[k] - nx[k], footZ[k] - nz[k]) * 16F / STEP_DIST,
                        turned = Math.abs(footYaw[k] - bodyYaw) / STEP_YAW, need = Math.max(far, turned);
                // after a move: a foot it put down out of the leg's reach (wider than a stance, the hips up again)
                if (settleFor > 0F) need = Math.max(need, out.shortOf[k] / MOVE_SHORT);
                if (need > most) {
                    most = need;
                    pick = k;
                }
            }
            if (pick >= 0) {
                stepU[pick] = 0F;
                stepPace[pick] = pace;
                fromX[pick] = footX[pick];
                fromZ[pick] = footZ[pick];
                fromYaw[pick] = footYaw[pick];
            }
        }
        for (int k = 0; k < 2; k++) {
            float[] m = toModel(footX[k] - x, footZ[k] - z, bodyYaw);
            out.at[k * 4] = m[0];
            out.at[k * 4 + 1] = m[1];
            out.at[k * 4 + 2] = lift[k];
            // from the legs as the pose has them: the moves' turn of them is in the pose already
            out.at[k * 4 + 3] = (footYaw[k] - bodyYaw - legTwist) * Mth.DEG_TO_RAD;
        }
    }

    /** a foot's place (x, z, blocks) no farther than DRAG from where the pose has it (nx, nz) */
    private static double[] drag(double x, double z, double nx, double nz) {
        double dx = x - nx, dz = z - nz, far = Math.hypot(dx, dz), most = DRAG / 16.0;
        return far <= most ? new double[] { x, z } : new double[] { nx + dx * most / far, nz + dz * most / far };
    }

    /**
     * A place in the model (x, z, px: the renderer turns it by 180 - yaw after flipping x and y) as an offset in the
     * world from the mob's place, blocks
     */
    static double[] toWorld(float px, float pz, float yaw) {
        double t = Math.toRadians(180.0 - yaw), c = Math.cos(t), s = Math.sin(t), x = -px / 16.0, z = pz / 16.0;
        return new double[] { x * c + z * s, -x * s + z * c };
    }

    /** an offset in the world from the mob's place (blocks) as a place in the model (x, z, px) */
    static float[] toModel(double wx, double wz, float yaw) {
        double t = Math.toRadians(180.0 - yaw), c = Math.cos(t), s = Math.sin(t);
        return new float[] { (float) (-(wx * c - wz * s) * 16.0), (float) ((wx * s + wz * c) * 16.0) };
    }

    /** the flinch showing (the last hit's) */
    private String hitClip = clip(0);
    /** till when a push may yet make the flinch begun the knockback */
    private double knockUntil = Double.NEGATIVE_INFINITY;
    /** the motion the game sends it now, blocks a tick (a hit's push) */
    float push;

    Frame step(AiPose pose, long gameTime, float partial, int hurtTime, boolean dead, float age, int entity) {
        return step(pose, gameTime, partial, hurtTime, dead, age, entity, false);
    }

    Frame step(AiPose pose, long gameTime, float partial, int hurtTime, boolean dead, float age, int entity,
            boolean crouching) {
        double now = gameTime + (double) partial;
        // a first look, or after a long gap (out of sight): no fade from a pose nobody saw. The clock going back (the
        // server setting the time, a late partial tick) is no gap: nothing moves this frame
        boolean fresh = Double.isNaN(last) || now - last > 20;
        float dt = fresh ? Float.POSITIVE_INFINITY : (float) Math.max(0.0, now - last);
        last = now;
        if (fresh) lastHurt = hurtTime;

        float crouchTo = pose.stance() == AiPose.CROUCH || crouching ? 1F : 0F;
        crouch = approach(crouch, crouchTo, dt / CROUCH_TICKS);

        // a way of the arms this build does not know: the gun at the chest
        int to = pose.arms() >= 0 && pose.arms() < arms.length ? pose.arms() : AiPose.HOLD;
        float step = dt / ARMS_TICKS;
        if (step >= 1F) {
            java.util.Arrays.fill(arms, 0F);
            arms[to] = 1F;
        } else {
            // the target's weight rises by the step, the others give it up in proportion: always summing to 1
            float rest = 1F - arms[to], give = Math.min(step, rest);
            if (rest > 0F) {
                for (int i = 0; i < arms.length; i++) if (i != to) arms[i] -= arms[i] / rest * give;
                arms[to] += give;
            }
        }

        // lying, the poser fades the lean out with the swim amount: no snap when a mob lies down leaning
        float lean = AiPoseGeometry.leanFrac(pose, gameTime, partial);

        byte kind = dead ? AiPose.NONE : pose.action();
        int len = kind == AiPose.NONE ? 0
                : pose.actionTicks() > 0 ? pose.actionTicks() : kind == AiPose.SHOT ? SHOT_TICKS : 20;
        if (kind != actKind || pose.actionTick() != actTick) {
            // a new action (or none): the one under way, if it still shows, fades out from where it is
            if (actKind != AiPose.NONE && actW > 0F && !fresh) outs.add(new Out(actKind, actTick, actLen, actW, now));
            actKind = kind;
            actTick = pose.actionTick();
            actLen = len;
        }
        Act act = null;
        actW = 0F;
        if (actKind != AiPose.NONE) {
            float t = (float) (now - actTick);
            if (t >= 0F && t < actLen) {
                // a shot's kick comes in half a tick (a snap, not a jump); anything else fades in and out
                // a reload takes the arms from wherever they are (over the head, firing blind) to its clip's:
                // slower in (BotPoser.RELOAD_IN)
                float in = Math.min(1F, t / (actKind == AiPose.SHOT ? SHOT_IN : actKind == AiPose.RELOAD ? BotPoser.RELOAD_IN
                        : actKind == AiPose.SLIDE || actKind == AiPose.ROLL ? DASH_IN : ACTION_FADE));
                actW = smooth(Math.min(in, Math.min(1F, (actLen - t) / ACTION_FADE)));
                act = new Act(actKind, t / actLen, actW, t);
            }
        }
        Act[] fading = NO_ACTS;
        if (!outs.isEmpty()) {
            java.util.ArrayList<Act> list = new java.util.ArrayList<>(outs.size());
            for (java.util.Iterator<Out> it = outs.iterator(); it.hasNext();) {
                Out o = it.next();
                float since = (float) (now - o.at()), t = (float) (now - o.tick());
                float w = o.w() * smooth(1F - since / ACTION_FADE);
                if (w > 0F && since >= 0F) list.add(new Act(o.kind(), Math.min(t / o.len(), 1F), w, t));
                else if (since >= 0F) it.remove();
            }
            fading = list.toArray(NO_ACTS);
        }

        // a flinch: a new hit, or death, lets the one showing go from where it is
        boolean struck = hurtTime > lastHurt && !dead;
        lastHurt = hurtTime;
        if ((struck || dead) && hitW > 0F) outHits.add(new OutHit(hitClip, hitAt, hitW, now));
        if (struck) {
            hitAt = now;
            hits++;
            // thrown fast as it is hit (a blast, a heavy round): it is thrown back; else head and chest by turns
            hitClip = push >= KNOCK_PUSH ? BotClips.HIT_KNOCK : clip(hits);
            knockUntil = now + KNOCK_WAIT;
        } else if (dead) hitAt = Double.NEGATIVE_INFINITY;
        else if (now < knockUntil && !hitClip.equals(BotClips.HIT_KNOCK) && push >= KNOCK_PUSH) {
            // the push comes after the hit (the game sends the hit at once, the motion with the tick's moves): the
            // flinch begun gives way to the knockback
            if (hitW > 0F) outHits.add(new OutHit(hitClip, hitAt, hitW, now));
            hitAt = now;
            hitClip = BotClips.HIT_KNOCK;
            knockUntil = Double.NEGATIVE_INFINITY;
        }
        hitW = 0F;
        java.util.ArrayList<Hit> shown = new java.util.ArrayList<>(2);
        float sec = (float) (now - hitAt) / 20F;
        BotClip c = BotClips.get(hitClip);
        if (c != null && sec >= 0F && sec < c.length()) {
            hitW = Math.min(1F, Math.min(sec * 20F / HIT_IN, (c.length() - sec) * 20F / HIT_OUT));
            shown.add(new Hit(hitClip, sec, hitW));
        }
        for (java.util.Iterator<OutHit> it = outHits.iterator(); it.hasNext();) {
            OutHit o = it.next();
            float since = (float) (now - o.at());
            float w = o.w() * smooth(1F - since / HIT_OUT);
            if (w > 0F && since >= 0F) shown.add(new Hit(o.clip(), (float) (now - o.began()) / 20F, w));
            else if (since >= 0F) it.remove();
        }
        Hit[] hitList = shown.isEmpty() ? NO_HITS : shown.toArray(NO_HITS);

        alert = approach(alert, pose.alert() == AiPose.FIGHT ? 1F : 0F, dt / ALERT_TICKS);
        // in a slide or a roll, and for a few ticks after: at once in (its clip takes the whole body in DASH_IN ticks
        // and hides what is under it), slowly out
        boolean dash = act != null && BotPoser.dash(act.kind());
        for (Act out : fading) dash |= BotPoser.dash(out.kind());
        dashed = dash ? approach(dashed, 1F, dt / DASH_IN) : approach(dashed, 0F, dt / DASH_OUT);
        return new Frame(crouch, lean, eased(arms), act, fading, hitList, age / 20F, entity, 0F, 0F, -1F, 0F, 0F, alert, 0F, dashed);
    }

    /** the flinch of the n-th hit, by turns: the head back, the chest, doubled up, thrown back */
    private static String clip(int n) {
        return switch (n & 3) {
            case 0 -> BotClips.HIT_HEAD;
            case 1 -> BotClips.HIT_CHEST;
            case 2 -> BotClips.get(BotClips.HIT_FOLD) != null ? BotClips.HIT_FOLD : BotClips.HIT_HEAD;
            default -> BotClips.get(BotClips.HIT_BACK) != null ? BotClips.HIT_BACK : BotClips.HIT_CHEST;
        };
    }

    /** how much more walkPos to where the legs pass each other (vanilla's swing, cos(walkPos 0.6662), at nought) */
    static float toRest(float walkPos) {
        float half = BotPoser.CYCLE / 2F, at = BotPoser.CYCLE / 4F;
        float r = ((at - walkPos) % half + half) % half;
        // there already (a float's rounding just past it): not on for another half a step
        return r > half - Math.max(1e-3F, 4F * Math.ulp(walkPos)) ? 0F : r;
    }

    static float approach(float from, float to, float step) {
        if (step >= 1F) return to;
        return from < to ? Math.min(to, from + step) : Math.max(to, from - step);
    }

    /** 0 .. 1 eased at both ends */
    static float smooth(float x) {
        x = Mth.clamp(x, 0F, 1F);
        return x * x * (3F - 2F * x);
    }

    /**
     * The arms' ways' weights as drawn: each going over (ARMS_TICKS, at an even pace) eased in and out (smooth), so an
     * arm starts and stops without a jolt; still summing to 1 (from one way to another smooth(w) + smooth(1 - w) is 1
     * as it is; among three, shared out again)
     */
    static float[] eased(float[] w) {
        float[] out = new float[w.length];
        float sum = 0F;
        for (int i = 0; i < w.length; i++) sum += out[i] = smooth(w[i]);
        if (sum > 1e-6F) for (int i = 0; i < w.length; i++) out[i] /= sum;
        return out;
    }
}
