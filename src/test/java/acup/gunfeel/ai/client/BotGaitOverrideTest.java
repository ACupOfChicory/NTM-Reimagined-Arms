package acup.gunfeel.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A stand's gait clips in place of the mod's (BotGaitOverride, BotGalleryStand): the poser and the pace take them */
class BotGaitOverrideTest {
    @AfterEach
    void none() {
        BotGaitOverride.BY_ENTITY = id -> null;
    }

    @Test
    void aClipGoesAsFarThroughItsStepAsTheMobGoes() {
        BotClip jog = BotClips.get(BotClips.JOG);
        assertNotNull(jog);
        // going ahead at 0.15 blocks a tick, a clip of 3 blocks a second: a cycle each 3 * its length blocks
        float pace = BotPoser.pace(0.7F, 0.15F, 0F, BotGaitOverride.all(BotClips.JOG, 3F, 0.45F, true));
        assertEquals(BotPoser.CYCLE * 0.15F / (3F * jog.length()), pace, 1e-5F);
        // none: the mod's own; a pace of nought: the mod's own too
        assertEquals(0.7F, BotPoser.pace(0.7F, 0.15F, 0F, null), 0F);
        assertEquals(0.7F, BotPoser.pace(0.7F, 0.15F, 0F, BotGaitOverride.all(BotClips.JOG, 0F, 0F, false)), 1e-6F);
        // going aside the strafe's share is the mod's
        float aside = BotPoser.pace(0.7F, 0.15F, (float) Math.PI / 2F, BotGaitOverride.all(BotClips.JOG, 3F, 0.45F, true));
        assertEquals(0.7F, aside, 1e-4F);
    }

    @Test
    void theLegsAreTheClipPutIn() {
        // a walk with the sprint put in for every pace: the legs as at a full sprint with the mod's own
        BotPoser.Parts put = BotPoserTest.parts(), own = BotPoserTest.parts();
        BotPoser.gait(put, 0.1F, 0F, 7F, 1F, new float[3], BotGaitOverride.all(BotClips.SPRINT, 5F, 0F, false));
        BotPoser.gait(own, 0.4F, 0F, 7F, 1F, new float[3], null);
        for (int i = 0; i < 10; i++) {
            var a = BotPoserTest.all(put)[i];
            var b = BotPoserTest.all(own)[i];
            assertEquals(b.xRot, a.xRot, 1e-5F, "part " + i);
            assertEquals(b.yRot, a.yRot, 1e-5F, "part " + i);
            assertEquals(b.zRot, a.zRot, 1e-5F, "part " + i);
        }
    }

    @Test
    void theHipsGoDownAsFarAsTheClipsAreMadeFor() {
        // walking calm (no stoop of its own): the mob put in 0.45 px lower all over the hips; another, as it was
        BotGaitOverride.BY_ENTITY = id -> id == 7 ? BotGaitOverride.all(BotClips.WALK, 1.5F, 0.45F, true) : null;
        float[] arms = { 1F, 0F, 0F, 0F };
        BotPoser.Parts put = BotPoserTest.parts(), own = BotPoserTest.parts();
        BotPoser.apply(put, new BotAnimator.Frame(0F, 0F, arms, null, BotAnimator.NO_ACTS, BotAnimator.NO_HITS, 0F, 7, 0.1F, 0F, -1F,
                1F, 3F, 0F), 0F, 0.4F, 3F);
        BotPoser.apply(own, new BotAnimator.Frame(0F, 0F, arms, null, BotAnimator.NO_ACTS, BotAnimator.NO_HITS, 0F, 8, 0.1F, 0F, -1F,
                1F, 3F, 0F), 0F, 0.4F, 3F);
        assertEquals(own.body().y + 0.45F, put.body().y, 1e-4F);
        assertEquals(own.head().y + 0.45F, put.head().y, 1e-4F);
        assertEquals(own.rightLeg().y + 0.45F, put.rightLeg().y, 1e-4F);
    }
}
