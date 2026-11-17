package acup.gunfeel.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import acup.gunfeel.ai.AiPose;
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
}
