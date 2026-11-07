package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.mojang.blaze3d.vertex.PoseStack;

// ArmLayers.layer: a layer that throws on the arm leaves the layers after it their pose, and one that throws with the
// same armour on every frame is left out after ArmLayers.TRIES failures
class ArmLayersFailTest {
    private static final Matrix4f ARM = new Matrix4f().translation(1F, 2F, 3F).rotateY(0.5F);
    private static final Object[] PLAIN = {null, "chest", null, null};
    private static final Object[] OTHER = {null, "other chest", null, null};

    // stand-ins for the player renderer's layers (only their classes count)
    private static final class Throws {
    }

    private static final class Draws {
    }

    @AfterEach
    void clear() {
        ArmLayers.leave();
    }

    private static Matrix4f seen(Object[] worn) {
        List<Matrix4f> seen = new ArrayList<>();
        assertTrue(ArmLayers.layer(ARM, Draws.class, worn, pose -> seen.add(new Matrix4f(pose.last().pose()))));
        return seen.getFirst();
    }

    @Test
    void layerThrowingInsideItsPushLeavesTheNextItsPose() {
        // as a suit that moves, pushes, moves again and fails before its pop
        assertFalse(ArmLayers.layer(ARM, Throws.class, PLAIN, pose -> {
            pose.translate(5F, 0F, 0F);
            pose.pushPose();
            pose.translate(0F, 7F, 0F);
            throw new IllegalStateException("a layer that throws inside its push");
        }));
        assertTrue(seen(PLAIN).equals(ARM, 0F), "the next layer is posed at the arm");
    }

    @Test
    void layerPoppingMoreThanItPushedDoesNotReachTheNext() {
        assertFalse(ArmLayers.layer(ARM, Throws.class, PLAIN, pose -> {
            pose.translate(5F, 0F, 0F);
            pose.popPose();
        }));
        assertTrue(seen(PLAIN).equals(ARM, 0F));
    }

    @Test
    void layerFailingWithTheSameArmourIsLeftOutAfterTries() {
        int[] calls = {0};
        for (int frame = 0; frame < 10; frame++) {
            ArmLayers.layer(ARM, Throws.class, PLAIN, pose -> {
                calls[0]++;
                throw new IllegalStateException("fails every frame");
            });
        }
        assertEquals(ArmLayers.TRIES, calls[0]);
        // other layers go on
        seen(PLAIN);
        // other armour on: tried anew
        ArmLayers.layer(ARM, Throws.class, OTHER, pose -> calls[0]++);
        assertEquals(ArmLayers.TRIES + 1, calls[0]);
        // a new world: tried anew
        ArmLayers.leave();
        ArmLayers.layer(ARM, Throws.class, PLAIN, pose -> calls[0]++);
        assertEquals(ArmLayers.TRIES + 2, calls[0]);
    }

    @Test
    void layerFailingNowAndThenIsKept() {
        // failures with draws between them: never TRIES in a row
        int[] calls = {0};
        for (int frame = 0; frame < 3 * ArmLayers.TRIES; frame++) {
            ArmLayers.layer(ARM, Throws.class, PLAIN, pose -> {
                if (++calls[0] % ArmLayers.TRIES != 0) throw new IllegalStateException("now and then");
            });
        }
        assertEquals(3 * ArmLayers.TRIES, calls[0]);
    }

    @Test
    void layerFailingOnlyAtFirstIsKept() {
        int[] calls = {0};
        for (int frame = 0; frame < 10; frame++) {
            ArmLayers.layer(ARM, Throws.class, PLAIN, pose -> {
                if (++calls[0] < ArmLayers.TRIES) throw new IllegalStateException("before the player's data came");
            });
        }
        assertEquals(10, calls[0]);
    }

    @Test
    void stackOfItsOwn() {
        // the pose handed over is a fresh stack at the arm, one entry deep (checked out here: layer catches all)
        PoseStack[] got = new PoseStack[2];
        assertTrue(ArmLayers.layer(ARM, Draws.class, PLAIN, pose -> got[0] = pose));
        assertTrue(ArmLayers.layer(ARM, Draws.class, PLAIN, pose -> got[1] = pose));
        assertNotSame(got[0], got[1]);
        assertTrue(new Matrix4f(got[0].last().pose()).equals(ARM, 0F));
        assertThrows(java.util.NoSuchElementException.class, got[0]::popPose);
    }
}
