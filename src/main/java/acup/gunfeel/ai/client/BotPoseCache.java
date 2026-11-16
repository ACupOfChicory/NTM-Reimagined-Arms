package acup.gunfeel.ai.client;

import java.util.Arrays;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;

/**
 * A bot's pose worked out once a frame. The same render state is posed by several models in a frame: the body, each
 * armour layer, the held item's host on Flywheel's workers. {@link BotPoser#apply} gives them all the same pose from
 * the same inputs, so the first one's result is kept on the state and copied to the rest.
 *
 * <p>A copy is taken only when everything apply reads is the same: the frame, the swim and walk numbers, each part's
 * place and turns as vanilla's setupAnim left them, and the parts' default poses (the model's {@link #signature}).
 * Apply writes nothing but those parts' places and turns, so a copy is the same pose to the bit.
 */
public final class BotPoseCache {
    /** off only for the stand's before/after (FireStand alt=pose-/pose+) */
    public static volatile boolean on = true;
    /** stand only: on a copy, works the pose out as well and counts copies that differ from it by a bit */
    public static volatile boolean verify;
    public static final java.util.concurrent.atomic.AtomicLong COPIES = new java.util.concurrent.atomic.AtomicLong(),
            WORKED = new java.util.concurrent.atomic.AtomicLong(), DIFFER = new java.util.concurrent.atomic.AtomicLong();
    /** per part: x, y, z, xRot, yRot, zRot */
    static final int PER = 6, PARTS = 10;

    final BotAnimator.Frame frame;
    final float[] signature;
    final float swim, walkSpeed, walkPos;
    final float[] in, out;

    private BotPoseCache(BotAnimator.Frame frame, float[] signature, float swim, float walkSpeed, float walkPos, float[] in,
            float[] out) {
        this.frame = frame;
        this.signature = signature;
        this.swim = swim;
        this.walkSpeed = walkSpeed;
        this.walkPos = walkPos;
        this.in = in;
        this.out = out;
    }

    /** One model's parts and their default poses, kept on the model (MixinBotPose). */
    public record Model(BotPoser.Parts parts, float[] signature) {
        public static Model of(BotPoser.Parts parts) {
            float[] s = new float[PARTS * 10];
            for (int i = 0; i < PARTS; i++) {
                ModelPart part = part(parts, i);
                int k = i * 10;
                if (part == null) {
                    s[k] = Float.NaN;
                    continue;
                }
                PartPose d = part.getInitialPose();
                s[k] = 1F;
                s[k + 1] = d.x();
                s[k + 2] = d.y();
                s[k + 3] = d.z();
                s[k + 4] = d.xRot();
                s[k + 5] = d.yRot();
                s[k + 6] = d.zRot();
                s[k + 7] = d.xScale();
                s[k + 8] = d.yScale();
                s[k + 9] = d.zScale();
            }
            return new Model(parts, s);
        }
    }

    /**
     * Poses {@code m}'s parts: copied from {@code cache} when it was worked out from the same inputs, else by
     * {@link BotPoser#apply}. Returns the cache to keep on the state (the same one on a copy).
     */
    static BotPoseCache pose(@Nullable BotPoseCache cache, Model m, BotAnimator.Frame f, float swim, float walkSpeed,
            float walkPos) {
        BotPoser.Parts p = m.parts();
        if (on && cache != null && cache.frame == f && eq(cache.swim, swim) && eq(cache.walkSpeed, walkSpeed)
                && eq(cache.walkPos, walkPos) && (cache.signature == m.signature() || Arrays.equals(cache.signature, m.signature()))
                && same(p, cache.in)) {
            if (verify) {
                COPIES.incrementAndGet();
                BotPoser.apply(p, f, swim, walkSpeed, walkPos);
                if (!Arrays.equals(read(p), cache.out)) DIFFER.incrementAndGet();
            }
            write(p, cache.out);
            return cache;
        }
        if (verify) WORKED.incrementAndGet();
        float[] in = read(p);
        BotPoser.apply(p, f, swim, walkSpeed, walkPos);
        return new BotPoseCache(f, m.signature(), swim, walkSpeed, walkPos, in, read(p));
    }

    static @Nullable ModelPart part(BotPoser.Parts p, int i) {
        return switch (i) {
            case 0 -> p.head();
            case 1 -> p.body();
            case 2 -> p.rightArm();
            case 3 -> p.leftArm();
            case 4 -> p.rightLeg();
            case 5 -> p.leftLeg();
            case 6 -> p.rightForearm();
            case 7 -> p.leftForearm();
            case 8 -> p.rightShin();
            default -> p.leftShin();
        };
    }

    static float[] read(BotPoser.Parts p) {
        float[] v = new float[PARTS * PER];
        for (int i = 0; i < PARTS; i++) {
            ModelPart part = part(p, i);
            if (part == null) continue;
            int k = i * PER;
            v[k] = part.x;
            v[k + 1] = part.y;
            v[k + 2] = part.z;
            v[k + 3] = part.xRot;
            v[k + 4] = part.yRot;
            v[k + 5] = part.zRot;
        }
        return v;
    }

    /** the parts as the cache's inputs, to the bit (as Arrays.equals takes floats) */
    static boolean same(BotPoser.Parts p, float[] v) {
        for (int i = 0; i < PARTS; i++) {
            ModelPart part = part(p, i);
            if (part == null) continue;
            int k = i * PER;
            if (!eq(part.x, v[k]) || !eq(part.y, v[k + 1]) || !eq(part.z, v[k + 2]) || !eq(part.xRot, v[k + 3])
                    || !eq(part.yRot, v[k + 4]) || !eq(part.zRot, v[k + 5])) return false;
        }
        return true;
    }

    private static boolean eq(float a, float b) {
        return Float.floatToIntBits(a) == Float.floatToIntBits(b);
    }

    static void write(BotPoser.Parts p, float[] v) {
        for (int i = 0; i < PARTS; i++) {
            ModelPart part = part(p, i);
            if (part == null) continue;
            int k = i * PER;
            part.x = v[k];
            part.y = v[k + 1];
            part.z = v[k + 2];
            part.xRot = v[k + 3];
            part.yRot = v[k + 4];
            part.zRot = v[k + 5];
        }
    }
}
