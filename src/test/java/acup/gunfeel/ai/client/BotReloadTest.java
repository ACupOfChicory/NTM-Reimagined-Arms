package acup.gunfeel.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import acup.gunfeel.ai.AiPose;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class BotReloadTest {
    @Test
    void aReloadKeepsTheHeadOnItsAim() {
        // eyes on the threat: the arms take the clip's reload, the head keeps its aim and its place (the eye the server
        // sees and shoots from) all through it
        assertNotNull(BotClips.get(BotClips.RELOAD));
        boolean moved = false;
        for (float u = 0F; u <= 1F; u += 0.125F) {
            BotPoser.Parts p = BotPoserTest.parts();
            p.head().setRotation(-0.3F, 0.4F, 0F);
            float lx = p.leftArm().xRot, ly = p.leftArm().yRot, lz = p.leftArm().zRot;
            BotPoser.action(p, new BotAnimator.Act(AiPose.RELOAD, u, 1F, u * 32F), -0.3F, 0.4F, new float[3], new float[3]);
            assertEquals(-0.3F, p.head().xRot, 0F, "at " + u);
            assertEquals(0.4F, p.head().yRot, 0F, "at " + u);
            assertEquals(0F, p.head().zRot, 0F, "at " + u);
            assertEquals(0F, p.head().x, 0F);
            assertEquals(0F, p.head().y, 0F);
            assertEquals(0F, p.head().z, 0F);
            moved |= p.leftArm().xRot != lx || p.leftArm().yRot != ly || p.leftArm().zRot != lz;
        }
        assertTrue(moved, "the arms reload");
    }

    /** a hand in the model, px: its forearm turned about the elbow, 6 px down it */
    static Vector3f hand(ModelPart arm, ModelPart fore) {
        Vector3f at = new Matrix4f().translate(fore.x, fore.y, fore.z).rotateZYX(fore.zRot, fore.yRot, fore.xRot)
                .transformPosition(new Vector3f(0F, 6F, 0F));
        return BotPoserTest.inModel(arm, at);
    }

    @Test
    void theMagazineGoesInUnderTheEyes() {
        // the hands where tools/quaternius put them (bot_clips ARMS): the right on the grip, the gun up 35 degrees and in
        // 30; the left at the well, 3 px up the gun, after it is pushed home: some 28 degrees under the line of sight
        BotPoser.Parts p = BotPoserTest.parts();
        BotPoser.action(p, new BotAnimator.Act(AiPose.RELOAD, 0.85F, 1F, 27F), 0F, 0F, new float[3], new float[3]);
        Vector3f right = hand(p.rightArm(), p.rightForearm()), left = hand(p.leftArm(), p.leftForearm());
        assertEquals(1.0F, right.x, 0.15F);
        assertEquals(-0.94F, right.y, 0.15F);
        assertEquals(-6.3F, right.z, 0.15F);
        assertEquals(2.23F, left.x, 0.15F);
        assertEquals(-1.66F, left.y, 0.15F);
        assertEquals(-8.43F, left.z, 0.15F);
        Vector3f line = BotPoserTest.inModel(p.rightArm(), new Matrix4f().translate(p.rightForearm().x, p.rightForearm().y, p.rightForearm().z)
                .rotateZYX(p.rightForearm().zRot, p.rightForearm().yRot, p.rightForearm().xRot).transformPosition(new Vector3f(0F, 7F, 0F)))
                .sub(right);
        assertEquals(35.0, Math.toDegrees(Math.asin(-line.y)), 2.0, "the gun up");
        assertEquals(30.0, Math.toDegrees(Math.atan2(line.x, -line.z)), 2.0, "the gun in");
        // under the eyes (the head level, its face's middle at (0, -4, -4))
        assertTrue(Math.toDegrees(Math.atan2(left.y + 4F, -(left.z + 4F))) < 32.0, "the magazine under the line of sight");
    }
}
