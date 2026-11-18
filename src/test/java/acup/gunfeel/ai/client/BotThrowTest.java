package acup.gunfeel.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import acup.gunfeel.ai.AiPose;

// A grenade's throw recorded from a person (BotClips.THROW_PERSON, Config.botMoves clips): the right hand lets the grip
// go and throws, the rifle goes with the left hand down at the side, and back into both hands after
class BotThrowTest {
    /** the throw's first tick and how long it is (AiSoldier.THROW_TICKS) */
    static final int AT = 110, TICKS = 12;
    /** the grip in the hand's frame (AiPoseGeometry.GRIP_*: 10 px down the arm, 2 in front, 1 outside) */
    static final Vector3f GRIP = new Vector3f(-1F, 10F, -2F);

    /** one frame: the time, the carry, the rifle's frame and the two hands' (px, the model's), the right shoulder's height */
    record F(double now, float w, float k, Matrix4f rifle, Matrix4f right, Matrix4f left, Matrix4f fromLeft, float shoulderY,
            float[] head) {
    }

    /** what goes on: the pose the server sends at a time, a hit's tick (or -1), death from a time (or never) */
    record Script(java.util.function.DoubleFunction<AiPose> pose, long hit, double dead) {
    }

    static AiPose pose(byte stance, byte arms, byte action, int at, int ticks) {
        return new AiPose(stance, (byte) 0, (byte) 0, 0, arms, action, at, (short) ticks);
    }

    static Script thrown(byte stance) {
        return new Script(now -> now >= AT ? pose(stance, AiPose.AIM, AiPose.THROW, AT, TICKS) : pose(stance, AiPose.AIM, AiPose.NONE, 0, 0),
                -1, Double.MAX_VALUE);
    }

    /** standing aimed, a throw at AT */
    static final Script THROW = thrown(AiPose.STAND);

    static List<F> run(int perTick, Script s) {
        BotAnimator an = new BotAnimator();
        List<F> out = new ArrayList<>();
        int hurt = 0;
        long lastTick = -1;
        for (int frame = 0; frame < perTick * 40; frame++) {
            double now = 100 + frame / (double) perTick;
            long tick = (long) Math.floor(now);
            if (tick != lastTick) {
                hurt = tick == s.hit() ? 10 : Math.max(0, hurt - 1);
                lastTick = tick;
            }
            BotAnimator.Frame f = an.step(s.pose().apply(now), tick, (float) (now - tick), hurt, now >= s.dead(), (float) now, 0);
            BotPoser.Parts parts = BotPoserTest.parts();
            BotPoser.Carry c = new BotPoser.Carry();
            BotPoser.apply(parts, f, 0F, 0F, 0F, c);
            out.add(new F(now, c.w(), c.k, BotPoser.carried(parts, c, new Matrix4f()),
                    BotPoser.hand(parts.rightArm(), parts.rightForearm(), new Matrix4f()),
                    BotPoser.hand(parts.leftArm(), parts.leftForearm(), new Matrix4f()), new Matrix4f(c.fromLeft),
                    parts.rightArm().y, new float[] { parts.head().x, parts.head().y, parts.head().z, parts.head().xRot,
                            parts.head().yRot, parts.head().zRot }));
        }
        return out;
    }

    static float moved(Matrix4f a, Matrix4f b, Vector3f point) {
        return a.transformPosition(new Vector3f(point)).distance(b.transformPosition(new Vector3f(point)));
    }

    /** the barrel in the hand's frame: the aimed rifle's (forward, the model's -z) as the right hand held it */
    static Vector3f barrel(List<F> run) {
        F before = run.stream().filter(f -> f.now() < AT).reduce((a, b) -> b).orElseThrow();
        return new Matrix4f(before.right()).invert().transformDirection(new Vector3f(0F, 0F, -1F)).normalize();
    }

    /** the most the rifle's grip or muzzle moves from one frame to the next, px */
    static float mostMove(List<F> run, Vector3f barrel) {
        Vector3f tip = new Vector3f(barrel).mul(16F).add(GRIP);
        float most = 0F;
        for (int i = 1; i < run.size(); i++)
            most = Math.max(most, Math.max(moved(run.get(i - 1).rifle(), run.get(i).rifle(), GRIP),
                    moved(run.get(i - 1).rifle(), run.get(i).rifle(), tip)));
        return most;
    }

    /** no jump: at 4 times the frames each frame moves a quarter as far (a jump moves as far at any rate) */
    static void noJump(String what, Script s) {
        Vector3f b = barrel(run(3, THROW));
        float at60 = mostMove(run(3, s), b), at240 = mostMove(run(12, s), b);
        System.out.println("throw, " + what + ": the rifle's grip or muzzle moves at most " + at60 + " px a frame at 60 fps, "
                + at240 + " at 240");
        assertTrue(at240 < at60 / 4F * 1.3F, what + ": a jump: " + at60 + " at 60 fps, " + at240 + " at 240 fps");
    }

    @Test
    void theThrowFromAPersonIsTheDefaultAndOldKeepsUals() {
        BotClip t = BotClips.get(BotClips.THROW_PERSON);
        assertNotNull(t);
        assertEquals(BotClip.Mode.ONCE, t.mode());
        assertEquals(TICKS / 20F, t.length(), 1e-4F, "played at its own pace over the throw's ticks");
        float[] a = new float[3];
        for (String bone : BotPoser.UPPER) assertTrue(t.rotation(bone, 0.3F, a), bone);
        assertSame(t, BotPoser.throwClip());
        assertTrue(BotPoser.throwsAsAPerson());
        String was = System.getProperty("ntmgunfeel.botMoves");
        System.setProperty("ntmgunfeel.botMoves", "old");
        try {
            assertSame(BotClips.get(BotClips.THROW), BotPoser.throwClip());
            // and the rifle stays in the right hand
            for (F f : run(3, THROW)) {
                assertEquals(0F, f.w(), "carried at " + f.now());
                assertTrue(f.rifle().equals(f.right(), 0F), "the right hand's at " + f.now());
            }
        } finally {
            if (was == null) System.clearProperty("ntmgunfeel.botMoves");
            else System.setProperty("ntmgunfeel.botMoves", was);
        }
    }

    @Test
    void theRifleGoesWithTheLeftHandMuzzleDownAndBackWithoutAJump() {
        List<F> run = run(3, THROW);
        Vector3f b = barrel(run);
        int whole = 0;
        for (F f : run) {
            // from nothing on its first frame to nothing at its end
            if (f.now() <= AT || f.now() >= AT + TICKS) {
                assertEquals(0F, f.w(), "carried at " + f.now());
                assertTrue(f.rifle().equals(f.right(), 0F), "the right hand's at " + f.now());
                continue;
            }
            assertTrue(f.w() > 0F, "not carried at " + f.now());
            // the left hand's whole from the first frame of the throw: the right hand swings back without it
            if (f.now() < AT + TICKS / 2) assertEquals(1F, f.k(), "the left hand's share at " + f.now());
            if (f.k() < 1F) continue;
            Matrix4f want = new Matrix4f(f.left()).mul(f.fromLeft());
            assertTrue(moved(want, f.rifle(), GRIP) < 1e-3F, "the left hand's at " + f.now());
            if (f.w() < 1F) continue;
            whole++;
            // the left arm down at the side: the muzzle goes down, 25 degrees at least (model y is down; and to the left
            // with the trunk's turn: 28 to 40 down, 9 to 31 aside)
            Vector3f dir = f.rifle().transformDirection(new Vector3f(b)).normalize();
            assertTrue(dir.y > Math.sin(Math.toRadians(25)), "the muzzle " + dir + " at " + f.now());
        }
        assertTrue(whole > 0, "never whole");
        noJump("standing", THROW);
    }

    @Test
    void whatComesUnderOrOverTheThrowHandsTheRifleOnWithoutAJump() {
        // a reload cut short by the throw (the server's throw goes before a reload: AiSoldier)
        noJump("over a reload", new Script(now -> now >= AT ? pose(AiPose.STAND, AiPose.AIM, AiPose.THROW, AT, TICKS)
                : now >= 102 ? pose(AiPose.STAND, AiPose.AIM, AiPose.RELOAD, 102, 30) : pose(AiPose.STAND, AiPose.AIM, AiPose.NONE, 0, 0),
                -1, Double.MAX_VALUE));
        // a slide cutting the throw short (a dash goes before a throw)
        noJump("cut by a slide", new Script(now -> now >= 116 ? pose(AiPose.CROUCH, AiPose.HOLD, AiPose.SLIDE, 116, 16)
                : now >= AT ? pose(AiPose.STAND, AiPose.AIM, AiPose.THROW, AT, TICKS) : pose(AiPose.STAND, AiPose.AIM, AiPose.NONE, 0, 0),
                -1, Double.MAX_VALUE));
        // hit as it comes in and as it goes out (a flinch turns each arm its own way), falling dead in the middle of it
        noJump("hit coming in", new Script(THROW.pose(), AT, Double.MAX_VALUE));
        // hit a little before: the throw comes in over the flinch at its most (each arm turned its own way)
        for (int before = 1; before <= 6; before++) noJump("hit " + before + " ticks before", new Script(THROW.pose(), AT - before, Double.MAX_VALUE));
        noJump("hit going out", new Script(THROW.pose(), AT + 9, Double.MAX_VALUE));
        noJump("falling dead", new Script(THROW.pose(), -1, 115.5));
        noJump("crouched", thrown(AiPose.CROUCH));
    }

    @Test
    void theHeadKeepsItsAimAndItsPlace() {
        // looking down at the one it throws to: the head as it is with no throw (its middle the server's eye)
        java.util.function.DoubleFunction<AiPose> none = now -> pose(AiPose.STAND, AiPose.AIM, AiPose.NONE, 0, 0);
        List<F> thrown = run(3, THROW), not = run(3, new Script(none, -1, Double.MAX_VALUE));
        for (int i = 0; i < thrown.size(); i++)
            for (int k = 0; k < 6; k++)
                assertEquals(not.get(i).head()[k], thrown.get(i).head()[k], 1e-6F, "the head's " + k + " at " + thrown.get(i).now());
    }

    @Test
    void crouchedTheShouldersStayDown() {
        List<F> up = run(3, THROW), low = run(3, thrown(AiPose.CROUCH));
        int n = 0;
        for (int i = 0; i < up.size(); i++)
            if (up.get(i).w() >= 1F) {
                n++;
                assertEquals(acup.gunfeel.ai.AiPoseGeometry.CROUCH_ARM_DROP_PX, low.get(i).shoulderY() - up.get(i).shoulderY(), 0.05F,
                        "the shoulder's drop at " + up.get(i).now());
            }
        assertTrue(n > 0);
    }

    @Test
    void theThrowComesInFasterThanTheOthers() {
        // THROW_IN ticks in: whole from the throw's third tick (the clip lets go at 0.13 s, 2.7 ticks)
        for (F f : run(3, THROW))
            if (f.now() >= AT + BotPoser.THROW_IN && f.now() <= AT + TICKS - BotAnimator.ACTION_FADE)
                assertEquals(1F, f.w(), 1e-6F, "at " + f.now());
    }
}
