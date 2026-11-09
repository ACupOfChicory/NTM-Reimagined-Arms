package acup.gunfeel.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import acup.gunfeel.ai.AiPose;
import net.minecraft.util.Mth;

// How it goes in a fight and out of one: a run is a combat sprint (the trunk into it, the gun across the chest), a
// walk in a fight a little forward with the gun ready, calm a stroll with the gun lowered; the head stays at the eye
// the server keeps (pose-contract: 3 cm) whatever it does, and the fight comes and goes smoothly.
class BotGaitTest {
    static BotAnimator.Frame going(float speed, float heading, float alert) {
        return new BotAnimator.Frame(0F, 0F, BotMoveClipsTest.HOLD, null, BotAnimator.NO_ACTS, BotAnimator.NO_HITS, 0F, 0, speed, heading, -1F, 0F, 0F, alert);
    }

    static BotPoser.Parts posed(float speed, float heading, float alert, float walkPos) {
        BotPoser.Parts p = BotPoserTest.parts();
        BotPoser.apply(p, going(speed, heading, alert), 0F, Math.min(1F, speed * 4F), walkPos);
        return p;
    }

    static Matrix4f frame(net.minecraft.client.model.geom.ModelPart p) {
        return new Matrix4f().translate(p.x, p.y, p.z).rotateZYX(p.zRot, p.yRot, p.xRot);
    }

    /** the gun hand (the right forearm's end) in the trunk's own frame: x to its left, y down from the neck, z back */
    static Vector3f handOnTrunk(BotPoser.Parts p) {
        Vector3f hand = frame(p.rightArm()).mul(frame(p.rightForearm())).transformPosition(new Vector3f(0F, 5.5F, 0F));
        return frame(p.body()).invert().transformPosition(hand);
    }

    @Test
    void aRunIsACombatSprint() {
        for (float pos = 0F; pos < BotPoser.CYCLE; pos += BotPoser.CYCLE / 8F) {
            BotPoser.Parts p = posed(0.33F, 0F, 1F, pos);
            assertEquals(BotPoser.RUN_LEAN, p.body().xRot, 0.02F, "the trunk into the run");
            Vector3f h = handOnTrunk(p);
            String at = String.format(Locale.ROOT, "the gun hand at %.1f %.1f %.1f on the trunk", h.x, h.y, h.z);
            // at the chest: below the shoulders, above the belt, in front of it, not out at the side
            assertTrue(h.y > 3F && h.y < 9F && h.z < -2.5F && h.z > -9F && Math.abs(h.x) < 4F, at);
        }
        // and the arms do not swing with the step: the hand the same through it
        Vector3f a = handOnTrunk(posed(0.33F, 0F, 1F, 0F)), b = handOnTrunk(posed(0.33F, 0F, 1F, BotPoser.CYCLE / 2F));
        assertTrue(a.distance(b) < 0.5F, "the hand moved " + a.distance(b) + " px through the step");
    }

    @Test
    void calmTheGunIsLoweredInAFightReadyWalkingALittleForward() {
        BotPoser.Parts calm = posed(0.15F, 0F, 0F, 0F), fight = posed(0.15F, 0F, 1F, 0F);
        assertEquals(0F, calm.body().xRot, 1e-3F, "a stroll: the trunk upright");
        assertEquals(BotPoser.TAC_LEAN, fight.body().xRot, 0.02F, "a tactical walk: the trunk a little forward");
        Vector3f h = handOnTrunk(posed(0F, 0F, 0F, 0F));
        assertTrue(h.y > 6F && h.y < 12F && h.z < -2.5F, String.format(Locale.ROOT, "calm, the gun hand at %.1f %.1f %.1f: at the hip in front", h.x, h.y, h.z));
        // in a fight standing, the arms as vanilla has them (NTM's bow pose along the look): not put down
        BotPoser.Parts still = posed(0F, 0F, 1F, 0F);
        assertEquals(0F, still.rightArm().xRot, 1e-6F);
    }

    @Test
    void theHeadStaysAtTheEye() {
        // pose-contract: the head within 3 cm (0.48 px) of where it is standing still, calm or not, at any pace and way
        // and vanilla's walk lagging behind the speed (a run begun: the idle loop not yet gone)
        for (float alert : new float[] { 0F, 0.5F, 1F })
            for (float speed : new float[] { 0F, 0.05F, 0.1F, 0.19F, 0.23F, 0.28F, 0.33F })
                for (float walk : new float[] { -1F, 0F, 0.1F, 0.25F })
                    for (float heading = -Mth.PI; heading < Mth.PI; heading += Mth.PI / 4F)
                        for (float pos = 0F; pos < BotPoser.CYCLE; pos += BotPoser.CYCLE / 16F) {
                            BotPoser.Parts p = BotPoserTest.parts();
                            BotPoser.apply(p, going(speed, heading, alert), 0F, walk < 0F ? Math.min(1F, speed * 4F) : walk, pos);
                            float d = new Vector3f(p.head().x, p.head().y, p.head().z).length();
                            assertTrue(d <= 0.48F, String.format(Locale.ROOT, "alert %.1f speed %.2f walk %.2f heading %.2f: the head %.2f px off", alert, speed, walk,
                                    heading, d));
                        }
    }

    @Test
    void aFightComesAndGoesSmoothly() {
        BotAnimator a = new BotAnimator();
        AiPose calm = AiPose.IDLE, fight = AiPose.IDLE.withAlert(AiPose.FIGHT);
        int t = 0;
        for (; t < 20; t++) assertEquals(0F, a.step(calm, t, 0F, 0, false, t, 1).alert(), 1e-6F);
        float was = 0F;
        for (int n = 0; n < 20; n++, t++) {
            float now = a.step(fight, t, 0F, 0, false, t, 1).alert();
            assertTrue(now >= was && now - was <= 1F / BotAnimator.ALERT_TICKS + 1e-5F, "a jump to " + now + " from " + was);
            was = now;
        }
        assertEquals(1F, was, 1e-6F);
        // a first look: as it is at once
        assertEquals(1F, new BotAnimator().step(fight, 100, 0F, 0, false, 100, 1).alert(), 1e-6F);
    }
}
