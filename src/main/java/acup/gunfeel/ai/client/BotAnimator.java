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
     */
    public record Frame(float crouch, float lean, float[] arms, @Nullable Act act, Act[] fading, Hit[] hits,
            float idleSec, int entity, float speed, float heading, float deathSec, float walk, float walkPos, float alert,
            float air, float dashed) {
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
            a.move(e.getX() - e.xo, e.getZ() - e.zo, e.yBodyRot, gameTime + (double) partial);
            // the push a hit gives it comes as its motion before it shows as steps
            a.push = (float) e.getDeltaMovement().horizontalDistance();
            a.fly(!e.onGround() && !e.isInWater() && !e.isInLava() && !e.isPassenger() && !e.onClimbable() && !e.isFallFlying(),
                    e.getY() - e.yo, gameTime + (double) partial);
            Frame f = a.step(pose, gameTime, partial, e.hurtTime, e.deathTime > 0, e.tickCount + partial, e.getId(),
                    crouching);
            float death = e.deathTime > 0 ? (e.deathTime - 1 + partial) / 20F : -1F;
            return new Frame(f.crouch(), f.lean(), f.arms(), f.act(), f.fading(), f.hits(), f.idleSec(), f.entity(),
                    a.speed, a.heading, death, a.walk, a.walkPos, f.alert(), a.air, f.dashed());
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
        float dt = Double.isNaN(movedAt) ? Float.POSITIVE_INFINITY : (float) Math.max(0.0, now - movedAt);
        movedAt = now;
        float s = (float) Math.sqrt(dx * dx + dz * dz);
        if (s > TELEPORT) {
            // put somewhere (a teleport, a ride): it stands where it is now
            speed = walk = 0F;
            return;
        }
        float k = dt > 20F ? 1F : 1F - (float) Math.pow(1F - SPEED_FOLLOW, dt);
        // from a stand (the clips hardly in yet) the heading takes the way it goes at once
        float stand = dt > 20F ? 1F : 1F - smooth((speed - BotPoser.MOVE_FROM) / (BotPoser.MOVE_FULL - BotPoser.MOVE_FROM));
        speed += (s - speed) * k;
        float kw = dt > 20F ? 1F : 1F - (float) Math.pow(1F - WALK_FOLLOW, dt);
        float walkWas = walk;
        walk += (Math.min(s * 4F, 1F) - walk) * kw;
        if (dt <= 20F) {
            float on = (walkWas + walk) / 2F * dt;
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
        return new Frame(crouch, lean, arms.clone(), act, fading, hitList, age / 20F, entity, 0F, 0F, -1F, 0F, 0F, alert, 0F, dashed);
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
}
