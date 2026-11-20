package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.mojang.blaze3d.PrimitiveTopology;

/**
 * 26.2's instanced pass has one index buffer, the first mesh's kind: a place's meshes of another topology are refused
 * (RenderSinkContract.oneFlushOneTopology; the pass itself needs the GPU, the check does not).
 */
class RenderPort26TopologyTest {
    @Test
    void anotherTopologyIsRefused() {
        assertDoesNotThrow(() -> RenderPort26.oneTopology(PrimitiveTopology.QUADS, PrimitiveTopology.QUADS, 7));
        assertThrows(IllegalArgumentException.class, () -> RenderPort26.oneTopology(PrimitiveTopology.QUADS, PrimitiveTopology.TRIANGLES, 7));
    }
}
