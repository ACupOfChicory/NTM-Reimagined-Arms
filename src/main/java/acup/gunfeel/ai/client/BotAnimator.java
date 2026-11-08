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

    private static final Map<LivingEntity, BotAnimator> ALL = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * One frame of a mob: plain numbers, filled when the render state is and read by setupAnim.
     *
     * @param crouch  0 standing .. 1 vanilla's crouch
     * @param lean    −1 left .. 1 right (0 lying)
     * @param arms    weight of each arms way (AiPose.HOLD, AIM, BLIND, NO_GUN), summing to 1
     * @param act     the action under way, or null
     * @param fading  the actions it cut short, fading out under it (often none)
     * @param hits    the flinches showing: the last, and any it cut short fading out
     * @param idleSec seconds into the idle loop
     * @param entity  the mob's id
     * @param speed   how fast it goes over the ground, blocks a tick (smoothed)
     * @param heading which way it goes, from where its body faces: radians, 0 ahead, + to its right
     * @param deathSec seconds since it fell dead, or below 0 alive
     */
    public record Frame(float crouch, float lean, float[] arms, @Nullable Act act, Act[] fading, Hit[] hits,
            float idleSec, int entity, float speed, float heading, float deathSec, float walk, float walkPos) {
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
    static final float HEADING_TURN = 0.3F;

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
    private final float[] arms = { 1F, 0F, 0F, 0F };
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
            Frame f = a.step(pose, gameTime, partial, e.hurtTime, e.deathTime > 0, e.tickCount + partial, e.getId(),
                    crouching);
            float death = e.deathTime > 0 ? (e.deathTime - 1 + partial) / 20F : -1F;
            return new Frame(f.crouch(), f.lean(), f.arms(), f.act(), f.fading(), f.hits(), f.idleSec(), f.entity(),
                    a.speed, a.heading, death, a.walk, a.walkPos);
        }
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
        speed += (s - speed) * k;
        float kw = dt > 20F ? 1F : 1F - (float) Math.pow(1F - WALK_FOLLOW, dt);
        float walkWas = walk;
        walk += (Math.min(s * 4F, 1F) - walk) * kw;
        if (dt <= 20F) walkPos += (walkWas + walk) / 2F * dt;
        if (s > 1e-3F) {
            // the game's yaw: 0 is +z, 90 is -x; the mob's right is 90 more than where it faces
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float to = Mth.wrapDegrees(yaw - bodyYaw) * Mth.DEG_TO_RAD;
            // and no faster than HEADING_TURN: turned right round (a sprint ahead, then back) the legs go from one
            // clip to the next over half a second, not in a tick
            float turn = Mth.wrapDegrees((to - heading) * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD * k, most = HEADING_TURN * dt;
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

        int to = Mth.clamp(pose.arms(), 0, arms.length - 1);
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

        return new Frame(crouch, lean, arms.clone(), act, fading, hitList, age / 20F, entity);
    }

    /** the flinch of the n-th hit: head and chest by turns */
    private static String clip(int n) {
        return (n & 1) == 0 ? BotClips.HIT_HEAD : BotClips.HIT_CHEST;
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
