package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import acup.gunfeel.ai.AiLean;
import acup.gunfeel.ai.AiPose;

/** The boxes the server puts for a leaning AI mob with no client: the agents' profile finds its head and eight limb parts. */
class AiLeanBoxesTest {
    @Test
    void serversBoxesFindTheHeadAndTheEightLimbParts() {
        for (boolean aiming : new boolean[] {false, true})
            for (float frac : new float[] {0F, 1F, -1F})
                for (float yaw : new float[] {0F, 135F, -60F}) {
                    var boxes = AiLean.boxes(AiPose.STAND, aiming, frac, yaw);
                    String at = (aiming ? "aim" : "hold") + " lean " + frac + " yaw " + yaw;
                    assertEquals(15, boxes.size(), at);
                    for (String type : new String[] {"hbm:entity_fbi", "hbm:entity_test_dummy"}) {
                        var head = ModelHeads.resolve(type, boxes, yaw);
                        assertEquals("dataset", head.source(), type + " " + at + " " + head.reason());
                        assertEquals(1, head.zones().size(), type + " " + at);
                        var limbs = ModelHeads.limbs(type, boxes, yaw);
                        assertEquals("dataset", limbs.source(), type + " " + at + " " + limbs.reason());
                        assertEquals(8, limbs.zones().size(), type + " " + at);
                    }
                }
    }

    @Test
    void aThighStaysPutWhileTheBodyLeans() {
        var upright = AiLean.boxes(AiPose.STAND, false, 0F, 0F);
        var leaned = AiLean.boxes(AiPose.STAND, false, 1F, 0F);
        for (int i = 0; i < upright.size(); i++) {
            var a = upright.get(i);
            var b = leaned.get(i);
            if (!a.part().contains("_leg")) continue;
            // the legs: only the lean's sideways step (a quarter block), no tip
            assertEquals(a.vx(), b.vx(), 1e-9, a.part());
            assertEquals(a.vy(), b.vy(), 1e-9, a.part());
            assertTrue(Math.abs(a.y() - b.y()) < 1e-9, a.part());
            assertEquals(0.25, Math.abs(b.x() - a.x()), 1e-6, a.part());
        }
    }

    @Test
    void theHeadZoneIsWhereTheLeanedHeadIs() {
        for (float frac : new float[] {1F, -1F})
            for (float yaw : new float[] {0F, 135F}) {
                var boxes = AiLean.boxes(AiPose.STAND, true, frac, yaw);
                var head = ModelHeads.resolve("hbm:entity_fbi", boxes, yaw);
                // the head's middle, leaned out: in the zone; where it stands upright (two thirds of a block off): not
                var leaned = acup.gunfeel.ai.AiPoseGeometry.leanPoint(0, 1.751, 0, AiPose.STAND, frac, yaw);
                var upright = acup.gunfeel.ai.AiPoseGeometry.leanPoint(0, 1.751, 0, AiPose.STAND, 0F, yaw);
                String at = "lean " + frac + " yaw " + yaw;
                assertTrue(HeadZone.contains(head.zones(), leaned), at);
                assertTrue(!HeadZone.contains(head.zones(), upright), at);
                // the body's middle is in no head zone, and the body leans: its box moved off upright
                var chest = acup.gunfeel.ai.AiPoseGeometry.leanPoint(0, 1.13, 0, AiPose.STAND, frac, yaw);
                assertTrue(!HeadZone.contains(head.zones(), chest), at);
            }
    }
}
