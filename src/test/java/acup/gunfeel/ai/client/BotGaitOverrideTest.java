package acup.gunfeel.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A stand's gait clips in place of the mod's (BotGaitOverride, BotGalleryStand): the poser and the pace take them */
class BotGaitOverrideTest {
    @AfterEach
    void none() {
        BotGaitOverride.BY_ENTITY = BotGaitOverride.OWN;
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
    void backAndAsideAreTheClipsPutIn() {
        // going back with the walk put in for the back: the legs as the walk's going ahead; aside the same for each side
        BotGaitOverride.Way walk = new BotGaitOverride.Way(BotClips.WALK, 1.5F, 0.45F), sprint = new BotGaitOverride.Way(BotClips.SPRINT, 5F, 0.45F);
        BotPoser.Parts ahead = BotPoserTest.parts(), sprinting = BotPoserTest.parts();
        BotPoser.gait(ahead, 0.1F, 0F, 7F, 1F, new float[3], BotGaitOverride.all(BotClips.WALK, 1.5F, 0F, false));
        BotPoser.gait(sprinting, 0.1F, 0F, 7F, 1F, new float[3], BotGaitOverride.all(BotClips.SPRINT, 5F, 0F, false));
        float half = (float) Math.PI / 2F;
        BotGaitOverride o = BotGaitOverride.none(true).withBack(walk).withAside(sprint, walk);
        BotPoser.Parts back = BotPoserTest.parts(), right = BotPoserTest.parts(), left = BotPoserTest.parts();
        BotPoser.gait(back, 0.1F, (float) Math.PI, 7F, 1F, new float[3], o);
        BotPoser.gait(right, 0.1F, half, 7F, 1F, new float[3], o);
        BotPoser.gait(left, 0.1F, -half, 7F, 1F, new float[3], o);
        for (int i = 0; i < 10; i++) {
            for (BotPoser.Parts[] pair : new BotPoser.Parts[][] { { back, ahead }, { right, ahead }, { left, sprinting } }) {
                var a = BotPoserTest.all(pair[0])[i];
                var b = BotPoserTest.all(pair[1])[i];
                assertEquals(b.xRot, a.xRot, 1e-5F, "part " + i);
                assertEquals(b.yRot, a.yRot, 1e-5F, "part " + i);
                assertEquals(b.zRot, a.zRot, 1e-5F, "part " + i);
            }
        }
        // and each at its own pace
        BotClip jog = BotClips.get(BotClips.JOG);
        assertNotNull(jog);
        BotGaitOverride j = BotGaitOverride.none(true).withBack(new BotGaitOverride.Way(BotClips.JOG, 2F, 0.45F))
                .withAside(new BotGaitOverride.Way(BotClips.JOG, 3F, 0.45F), null);
        assertEquals(BotPoser.CYCLE * 0.15F / (2F * jog.length()), BotPoser.pace(0.7F, 0.15F, 0F, (float) Math.PI, j), 1e-5F);
        assertEquals(BotPoser.CYCLE * 0.15F / (3F * jog.length()), BotPoser.pace(0.7F, 0.15F, 0F, -half, j), 1e-5F);
        assertEquals(0.7F, BotPoser.pace(0.7F, 0.15F, 0F, half, j), 1e-5F, "none put in to its right: the mod's");
        // crouched, the walk bent low's put in
        BotGaitOverride c = BotGaitOverride.none(true).withCrouch(new BotGaitOverride.Way(BotClips.JOG, 1F, 2.8F));
        assertEquals(BotPoser.CYCLE * 0.08F / (1F * jog.length()), BotPoser.pace(0.7F, 0.08F, 0F, 0F, 1F, c), 1e-5F);
        assertEquals(0.7F, BotPoser.pace(0.7F, 0.08F, 0F, 0F, 0F, c), 1e-6F, "standing up: the mod's");
        // none put in for it: crouched, the mod's walk bent low at the mod's pace, whatever is put in ahead
        assertEquals(0.7F, BotPoser.pace(0.7F, 0.08F, 0F, 0F, 1F, BotGaitOverride.all(BotClips.JOG, 3F, 0.45F, true)), 1e-6F);
    }

    @Test
    void crouchedTheHipsAreWhereTheClipIsMadeForUnderTheTrunk() {
        // a crouched walk put in, made for hips 2.8 px down: there, under the trunk's bottom
        BotGaitOverride.BY_ENTITY = id -> BotGaitOverride.none(true).withCrouch(new BotGaitOverride.Way(BotClips.JOG, 1F, 2.8F));
        float[] arms = { 1F, 0F, 0F, 0F };
        BotPoser.Parts p = BotPoserTest.parts();
        BotPoser.apply(p, new BotAnimator.Frame(1F, 0F, arms, null, BotAnimator.NO_ACTS, BotAnimator.NO_HITS, 0F, 7, 0.1F, 0F, -1F,
                1F, 3F, 0F), 0F, 0.4F, 3F);
        float behind = (float) Math.sqrt(12F * 12F - (12F + 2.8F - 3.2F) * (12F + 2.8F - 3.2F));
        assertEquals(behind, p.rightLeg().z, 1e-3F);
        assertEquals(behind, p.leftLeg().z, 1e-3F);
        float bottomZ = p.body().z + 12F * (float) Math.sin(p.body().xRot), bottomY = p.body().y + 12F * (float) Math.cos(p.body().xRot);
        assertEquals(p.rightLeg().z, bottomZ, 1e-3F, "the thigh at the trunk's bottom");
        assertEquals(14.8F, bottomY, 1e-3F);
        assertTrue(p.rightLeg().y <= 14.8F + 1e-3F && p.rightLeg().y > 13.8F, "thigh at " + p.rightLeg().y);
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
