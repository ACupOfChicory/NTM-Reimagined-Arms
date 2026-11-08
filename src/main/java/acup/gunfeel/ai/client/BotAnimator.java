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

    private static final Map<LivingEntity, BotAnimator> ALL = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * One frame of a mob: plain numbers, filled when the render state is and read by setupAnim.
     *
     * @param crouch  0 standing .. 1 vanilla's crouch
     * @param lean    −1 left .. 1 right (0 lying)
     * @param arms    weight of each arms way (AiPose.HOLD, AIM, BLIND, NONE), summing to 1
     * @param act     the action under way, or null
     * @param fading  the action it cut short, fading out under it, or null
     * @param hit     the flinch clip (BotClips.HIT_*), or null
     * @param hitSec  seconds since the hit
     * @param idleSec seconds into the idle loop
     */
    public record Frame(float crouch, float lean, float[] arms, @Nullable Act act, @Nullable Act fading,
            @Nullable String hit, float hitSec, float idleSec) {
    }

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
    private byte actKind = AiPose.ACT_NONE;
    private int actTick, actLen;
    private float actW;
    /** the action cut short: what, from when, how long, its weight when cut and when that was */
    private byte outKind = AiPose.ACT_NONE;
    private int outTick, outLen;
    private float outW;
    private double outAt;
    private int lastHurt;
    private double hitAt = Double.NEGATIVE_INFINITY;
    private int hits;

    public static Frame frame(LivingEntity e, AiPose pose, long gameTime, float partial) {
        BotAnimator a = ALL.computeIfAbsent(e, k -> new BotAnimator());
        synchronized (a) {
            return a.step(pose, gameTime, partial, e.hurtTime, e.deathTime > 0, e.tickCount + partial);
        }
    }

    Frame step(AiPose pose, long gameTime, float partial, int hurtTime, boolean dead, float age) {
        double now = gameTime + (double) partial;
        // a first look, or after a long gap (out of sight): no fade from a pose nobody saw
        boolean fresh = Double.isNaN(last) || now - last > 20 || now < last;
        float dt = fresh ? Float.POSITIVE_INFINITY : (float) (now - last);
        last = now;

        float crouchTo = pose.stance() == AiPose.CROUCH ? 1F : 0F;
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

        float lean = pose.stance() == AiPose.PRONE ? 0F : AiPoseGeometry.leanFrac(pose, gameTime, partial);

        byte kind = dead ? AiPose.ACT_NONE : pose.action();
        int len = kind == AiPose.ACT_NONE ? 0
                : pose.actionTicks() > 0 ? pose.actionTicks() : kind == AiPose.SHOT ? SHOT_TICKS : 20;
        if (kind != actKind || pose.actionTick() != actTick) {
            // a new action (or none): the one under way, if it still shows, fades out from where it is
            if (actKind != AiPose.ACT_NONE && actW > 0F && !fresh) {
                outKind = actKind;
                outTick = actTick;
                outLen = actLen;
                outW = actW;
                outAt = now;
            }
            actKind = kind;
            actTick = pose.actionTick();
            actLen = len;
        }
        Act act = null;
        actW = 0F;
        if (actKind != AiPose.ACT_NONE) {
            float t = (float) (now - actTick);
            if (t >= 0F && t < actLen) {
                // a shot's kick comes at once; anything else fades in and out
                float in = actKind == AiPose.SHOT ? 1F : Math.min(1F, t / ACTION_FADE);
                actW = smooth(Math.min(in, Math.min(1F, (actLen - t) / ACTION_FADE)));
                act = new Act(actKind, t / actLen, actW, t);
            }
        }
        Act fading = null;
        if (outKind != AiPose.ACT_NONE) {
            float since = (float) (now - outAt), t = (float) (now - outTick);
            float w = outW * smooth(1F - since / ACTION_FADE);
            if (w > 0F && since >= 0F) fading = new Act(outKind, Math.min(t / outLen, 1F), w, t);
            else outKind = AiPose.ACT_NONE;
        }

        if (hurtTime > lastHurt && !dead) {
            hitAt = now;
            hits++;
        }
        lastHurt = hurtTime;
        String hit = null;
        float hitSec = (float) (now - hitAt) / 20F;
        if (!dead && hitSec >= 0F) {
            String h = (hits & 1) == 0 ? BotClips.HIT_HEAD : BotClips.HIT_CHEST;
            BotClip c = BotClips.get(h);
            if (c != null && hitSec < c.length()) hit = h;
        }

        return new Frame(crouch, lean, arms.clone(), act, fading, hit, hitSec, age / 20F);
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
