package acup.gunfeel.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

import net.minecraft.client.model.geom.ModelPart;

/** An added clip's nod moves the head's middle as the clip has it, whichever way the head looks (BotPoser.add) */
class BotNodTest {
    /** a nod of 20 degrees ahead over a second, nothing moved */
    static BotClip nod() {
        return BotClip.parse(JsonParser.parseString("""
                {"animation_length": 1.0, "bones": {"head": {"rotation": {
                  "0.0": {"post": [0, 0, 0]}, "1.0": {"post": [20, 0, 0]}}}}}""").getAsJsonObject());
    }

    static Vector3f middle(ModelPart head) {
        Vector3f m = new Quaternionf().rotationZYX(head.zRot, head.yRot, head.xRot).transform(new Vector3f(BotPoser.HEAD_MIDDLE));
        return m.add(head.x, head.y, head.z);
    }

    /** where the head's middle goes with the nod at weight w, the head turned by x, y first */
    static Vector3f moved(float x, float y, float w, BotPoser.Parts p) {
        p.head().xRot = x;
        p.head().yRot = y;
        Vector3f was = middle(p.head());
        BotPoser.add(p, nod(), 1F, w, new float[3], new float[3], true);
        return middle(p.head()).sub(was);
    }

    @Test
    void theHeadsMiddleNodsAsTheClipHasItWhereverItLooks() {
        for (float w : new float[] { 1F, 0.4F }) {
            Vector3f straight = moved(0F, 0F, w, BotPoserTest.parts());
            for (float[] look : new float[][] { { -0.52F, 0F }, { 0.52F, 0F }, { 0F, 0.52F }, { -0.3F, -0.6F } }) {
                BotPoser.Parts p = BotPoserTest.parts();
                float bodyX = p.body().x, bodyY = p.body().y, bodyZ = p.body().z;
                Vector3f m = moved(look[0], look[1], w, p);
                assertEquals(straight.x, m.x, 1e-4F, "x looking " + look[0] + " " + look[1]);
                assertEquals(straight.y, m.y, 1e-4F, "y looking " + look[0] + " " + look[1]);
                assertEquals(straight.z, m.z, 1e-4F, "z looking " + look[0] + " " + look[1]);
                // the neck goes as one: trunk, arms and hips with the head
                float dx = p.body().x - bodyX, dy = p.body().y - bodyY, dz = p.body().z - bodyZ;
                BotPoser.Parts q = BotPoserTest.parts();
                for (ModelPart[] pair : new ModelPart[][] { { p.head(), q.head() }, { p.rightArm(), q.rightArm() },
                        { p.leftArm(), q.leftArm() }, { p.rightLeg(), q.rightLeg() }, { p.leftLeg(), q.leftLeg() } }) {
                    assertEquals(pair[1].x + dx, pair[0].x, 1e-5F);
                    assertEquals(pair[1].y + dy, pair[0].y, 1e-5F);
                    assertEquals(pair[1].z + dz, pair[0].z, 1e-5F);
                }
            }
        }
    }

    @Test
    void lookingStraightAheadNothingMoves() {
        BotPoser.Parts p = BotPoserTest.parts(), q = BotPoserTest.parts();
        BotPoser.add(p, nod(), 1F, 1F, new float[3], new float[3], true);
        assertEquals(q.body().x, p.body().x, 0F);
        assertEquals(q.body().y, p.body().y, 0F);
        assertEquals(q.body().z, p.body().z, 0F);
    }

    @Test
    void aClipThatNodsTheHeadAloneLeavesTheTrunk() {
        // the idle's breath: a head aimed down nods, the trunk stays
        BotPoser.Parts p = BotPoserTest.parts(), q = BotPoserTest.parts();
        p.head().xRot = 0.52F;
        BotPoser.add(p, nod(), 1F, 1F, new float[3], new float[3]);
        assertEquals(q.body().x, p.body().x, 0F);
        assertEquals(q.body().y, p.body().y, 0F);
        assertEquals(q.body().z, p.body().z, 0F);
    }
}
