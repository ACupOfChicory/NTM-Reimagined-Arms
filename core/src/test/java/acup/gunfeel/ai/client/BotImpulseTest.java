package acup.gunfeel.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

// A hit's push on an AI mob's trunk (BotImpulse): when it is most and how far, back to nothing, which way the hips go
// for a push each way, the twist of a hit at the side, hits together held to MOST, and the jerk at the game's frames.
class BotImpulseTest {
    static final float FPS = 60F;

    /** the trunk's turn from the neck, degrees, over 1 s at 60 fps after a hit at 0 */
    static float[][] run(BotImpulse b) {
        float[][] o = new float[61][6];
        for (int f = 0; f <= 60; f++) b.sample(f / FPS, o[f]);
        return o;
    }

    static float lean(float[] o) {
        return (float) Math.toDegrees(Math.hypot(o[0], o[2]));
    }

    @Test
    void atRestNothing() {
        float[] o = { 9, 9, 9, 9, 9, 9 };
        assertFalse(new BotImpulse().sample(3F, o));
        for (float v : o) assertEquals(0F, v);
    }

    @Test
    void aHitPeaksSoonAndIsGoneInHalfASecond() {
        BotImpulse b = new BotImpulse();
        b.hit(0F, 0F, 1F, Float.NaN, Float.NaN, Float.NaN, 0.5F, 0F);
        float[][] o = run(b);
        int peak = 0;
        for (int f = 0; f <= 60; f++) if (lean(o[f]) > lean(o[peak])) peak = f;
        assertTrue(peak / FPS >= 0.1F && peak / FPS <= 0.15F, "peak at " + peak / FPS);
        // strength 0.5: half of a hit's own most
        assertEquals(BotImpulse.KNEE * BotImpulse.MOST / 2F, lean(o[peak]), 0.15F);
        assertTrue(lean(o[30]) < 0.2F * lean(o[peak]), "a fifth by 0.5 s: " + lean(o[30]));
        assertTrue(lean(o[45]) < 0.04F * lean(o[peak]));
        assertFalse(b.sample(1.3F, new float[6]));
        // nothing before the hit
        BotImpulse c = new BotImpulse();
        c.hit(0F, 0F, 1F, Float.NaN, Float.NaN, Float.NaN, 1F, 2F);
        assertFalse(c.sample(1.9F, new float[6]));
    }

    @Test
    void theHardestHitAloneKeepsItsShape() {
        // strength 1: a fifth by 0.5 s too, no flat top
        BotImpulse b = new BotImpulse();
        b.hit(1F, 0F, 0F, Float.NaN, Float.NaN, Float.NaN, 1F, 0F);
        float[] p = new float[6], q = new float[6];
        b.sample(BotImpulse.PEAK, p);
        b.sample(0.5F, q);
        assertEquals(BotImpulse.KNEE * BotImpulse.MOST, lean(p), 0.01F);
        assertTrue(lean(q) < 0.2F * lean(p), lean(q) / lean(p) + " by 0.5 s");
    }

    @Test
    void aPushStraightDownOrUpDoesNotLean() {
        float[] o = new float[6];
        BotImpulse b = new BotImpulse();
        b.hit(0F, 1F, 0F, 2F, 3F, 0F, 1F, 0F);
        assertFalse(b.sample(BotImpulse.PEAK, o));
        // a push half down leans the trunk by its part over the floor
        BotImpulse c = new BotImpulse();
        c.hit(0F, 1F, 1F, Float.NaN, Float.NaN, Float.NaN, 1F, 0F);
        c.sample(BotImpulse.PEAK, o);
        assertEquals(BotImpulse.KNEE * BotImpulse.MOST / (float) Math.sqrt(2), lean(o), 0.01F);
    }

    @Test
    void notANumberIsNoHarm() {
        float[] o = new float[6];
        // a push not a number: from the front; a place not a number: no twist; a time not a number: no hit
        BotImpulse b = new BotImpulse();
        b.hit(Float.NaN, 0F, 1F, Float.POSITIVE_INFINITY, 3F, 0F, 1F, 0F);
        b.hit(Float.POSITIVE_INFINITY, 0F, 0F, Float.NaN, 0F, 0F, 1F, 0F);
        b.hit(0F, 0F, 1F, 0F, 0F, 0F, Float.NaN, 0F);
        b.hit(0F, 0F, 1F, 0F, 0F, 0F, 1F, Float.NaN);
        assertTrue(b.sample(BotImpulse.PEAK, o));
        for (float v : o) assertTrue(Float.isFinite(v));
        assertEquals(0F, o[1]);
        assertTrue(o[0] < 0F && Math.abs(o[2]) < 1e-6F, "both from the front");
        assertFalse(b.sample(Float.NaN, o));
        // huge pushes are only their way
        BotImpulse c = new BotImpulse();
        c.hit(3e38F, 0F, 3e38F, Float.NaN, Float.NaN, Float.NaN, 0.5F, 0F);
        c.sample(BotImpulse.PEAK, o);
        assertEquals(BotImpulse.KNEE * BotImpulse.MOST / 2F, lean(o), 0.01F);
        assertEquals(o[0], -o[2], 1e-6F);
    }

    @Test
    void hitsAddAndOppositeOnesTakeAway() {
        float[] one = new float[6], two = new float[6];
        BotImpulse a = new BotImpulse(), b = new BotImpulse(), c = new BotImpulse();
        a.hit(0F, 0F, 1F, Float.NaN, Float.NaN, Float.NaN, 0.3F, 0F);
        b.hit(0F, 0F, 1F, Float.NaN, Float.NaN, Float.NaN, 0.15F, 0F);
        b.hit(0F, 0F, 1F, Float.NaN, Float.NaN, Float.NaN, 0.15F, 0F);
        a.sample(0.2F, one);
        b.sample(0.2F, two);
        for (int i = 0; i < 6; i++) assertEquals(one[i], two[i], 1e-6F);
        c.hit(0F, 0F, 1F, Float.NaN, Float.NaN, Float.NaN, 0.3F, 0F);
        c.hit(0F, 0F, -1F, Float.NaN, Float.NaN, Float.NaN, 0.3F, 0F);
        c.sample(0.2F, one);
        assertTrue(lean(one) < 1e-5F);
    }

    @Test
    void aBurstGoesOnWithoutASnap() {
        // 20 hits a second for 3 s, weak ones: every hit still counts (none dropped while it is alive), the lean
        // the sum of them all
        BotImpulse b = new BotImpulse();
        float[] o = new float[6];
        float worst = 0F;
        int next = 0;
        for (int f = 0; f <= 180; f++) {
            float t = f / FPS;
            while (next / 20F <= t) b.hit(0F, 0F, 1F, Float.NaN, Float.NaN, Float.NaN, 0.05F, next++ / 20F);
            b.sample(t, o);
            float want = 0F;
            for (int i = 0; i < next; i++) want += BotImpulse.KNEE * BotImpulse.MOST * 0.05F * BotImpulse.shape(t - i / 20F);
            float m = (float) Math.toRadians(BotImpulse.MOST);
            want = (float) Math.toDegrees(BotImpulse.soft((float) Math.toRadians(want), m));
            worst = Math.max(worst, Math.abs(lean(o) - want));
        }
        assertTrue(worst < 0.01F, "off the sum by " + worst);
        // a shotgun's pellets at once, more than a few: each counts
        BotImpulse c = new BotImpulse();
        for (int i = 0; i < 12; i++) c.hit(0F, 0F, 1F, Float.NaN, Float.NaN, Float.NaN, 0.04F, 0F);
        c.sample(BotImpulse.PEAK, o);
        assertEquals(BotImpulse.KNEE * BotImpulse.MOST * 0.48F, lean(o), 0.01F);
    }

    @Test
    void theHipsGoAgainstThePushTheHeadStays() {
        // pushed back (+Z), to its left (+X), forward, to its right; all nought: from the front, as +Z
        float[][] dirs = { { 0, 0, 1 }, { 1, 0, 0 }, { 0, 0, -1 }, { -1, 0, 0 }, { 0, 0, 0 } };
        for (float[] d : dirs) {
            BotImpulse b = new BotImpulse();
            b.hit(d[0], d[1], d[2], Float.NaN, Float.NaN, Float.NaN, 1F, 0F);
            float[] o = new float[6];
            assertTrue(b.sample(BotImpulse.PEAK, o));
            Vector3f want = new Vector3f(d[0], 0, d[2]);
            if (want.lengthSquared() == 0F) want.set(0, 0, 1);
            float along = o[3] * want.x + o[5] * want.z;
            assertTrue(along < -1.5F && along > -2.2F, "hips against the push " + java.util.Arrays.toString(d) + ": " + along);
            assertEquals(0F, o[3] * want.z - o[5] * want.x, 1e-4F, "not to the side");
            // out[3..5]: the body's bottom turned as ModelPart turns it (Rz Ry Rx), the neck (its pivot, under the
            // head) where it was
            Vector3f bottom = new Matrix3f().rotationZYX(o[2], o[1], o[0]).transform(new Vector3f(0, BotImpulse.BODY, 0));
            assertEquals(bottom.x, o[3], 1e-4F);
            assertEquals(bottom.y - BotImpulse.BODY, o[4], 1e-4F);
            assertEquals(bottom.z, o[5], 1e-4F);
        }
    }

    @Test
    void aHitAtTheSideTwists() {
        // pushed back at the left of the chest: the left side goes back (yRot negative), and no twist at the middle
        BotImpulse b = new BotImpulse();
        b.hit(0F, 0F, 1F, 4F, 3F, 0F, 1F, 0F);
        float[] o = new float[6];
        b.sample(BotImpulse.PEAK, o);
        assertTrue(o[1] < 0F);
        Vector3f left = new Matrix3f().rotationY(o[1]).transform(new Vector3f(1, 0, 0));
        assertTrue(left.z > 0F, "the left side back");
        assertTrue(Math.toDegrees(-o[1]) <= BotImpulse.TWIST + 1e-3);
        BotImpulse c = new BotImpulse();
        c.hit(0F, 0F, 1F, 0F, 3F, -2F, 1F, 0F);
        c.sample(BotImpulse.PEAK, o);
        assertEquals(0F, o[1], 1e-6F);
        // pushed to its left at the front of the chest: the front goes left
        BotImpulse d = new BotImpulse();
        d.hit(1F, 0F, 0F, 0F, 3F, -2F, 1F, 0F);
        d.sample(BotImpulse.PEAK, o);
        Vector3f front = new Matrix3f().rotationY(o[1]).transform(new Vector3f(0, 0, -1));
        assertTrue(front.x > 0.05F, "the front to the left: " + front.x);
    }

    @Test
    void hitsTogetherStayUnderMost() {
        BotImpulse b = new BotImpulse();
        for (int i = 0; i < 12; i++) b.hit(0F, 0F, 1F, Float.NaN, Float.NaN, Float.NaN, 1F, i * 0.03F);
        float[][] o = run(b);
        for (float[] f : o) assertTrue(lean(f) <= BotImpulse.MOST + 1e-3F, "lean " + lean(f));
        // held near MOST, not past it, and going on smooth through the knee
        assertTrue(lean(o[8]) > 0.9F * BotImpulse.MOST, "lean " + lean(o[8]));
        for (float v = 0.5F; v < 0.99F; v += 0.01F) {
            float m = (float) Math.toRadians(BotImpulse.MOST), k = BotImpulse.KNEE * m, d = 1e-4F;
            float x = v * m * 1.5F;
            float slope = (BotImpulse.soft(x + d, m) - BotImpulse.soft(x - d, m)) / (2 * d);
            assertTrue(slope > 0F && slope <= 1.0001F, "slope at " + x);
            if (x < k - d) assertEquals(x, BotImpulse.soft(x, m), 1e-7F);
        }
        // a strength of 1 alone: the hips 2 px at most
        BotImpulse c = new BotImpulse();
        c.hit(0F, 0F, 1F, Float.NaN, Float.NaN, Float.NaN, 1F, 0F);
        float[] p = new float[6];
        c.sample(BotImpulse.PEAK, p);
        assertTrue(Math.hypot(p[3], p[5]) <= 2.1F);
    }

    @Test
    void theJerkAtTheFrames() {
        // the largest change of the turn's speed from frame to frame, deg/frame^2, at the hardest hit (strength 1):
        // the kick itself, from rest to its most speed in a frame
        BotImpulse b = new BotImpulse();
        // just after a frame: the next one shows the most of the kick's first stretch
        b.hit(0F, 0F, 1F, Float.NaN, Float.NaN, Float.NaN, 1F, 2F / FPS + 0.001F);
        float[][] o = run(b);
        float most = 0F;
        for (int f = 1; f < 60; f++) most = Math.max(most, Math.abs(lean(o[f + 1]) - 2 * lean(o[f]) + lean(o[f - 1])));
        assertTrue(most < 3.5F, "jerk " + most);
        System.out.println("BotImpulse jerk at strength 1: " + most);
    }
}
