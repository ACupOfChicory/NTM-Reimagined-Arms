package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

// A hole on the far face of translucent glass (NTM's laminate, stained glass) is seen from behind only by its copy
// drawn under the glass, early. Both ways of drawing holes give it that copy: the batches (holeCache) once lost it.
// The copy is the hole's back only: from in front it lay under the late one and darkened its half-clear edges twice.
class HolePassesTest {
    // the holes are drawn by core since the render port (render-step1.md)
    static final Path DECALS = Path.of("core/src/main/java/acup/gunfeel/HoleDecals.java");

    @Test
    void lateUnderGlassIsDrawnEarlyToo() {
        assertTrue(HolePasses.early(false, false), "a hole on a wall");
        assertTrue(HolePasses.early(true, true), "a hole on translucent glass: under it as well");
        assertFalse(HolePasses.early(true, false), "a hole on glass with clear pixels: late only");
    }

    @Test
    void copyUnderGlassOnlyFromBehind() {
        assertTrue(HolePasses.underCopy(true, -0.5), "seen through the glass");
        assertFalse(HolePasses.underCopy(true, 0.5), "seen from in front: the late hole alone");
        assertFalse(HolePasses.underCopy(true, 0.0), "the camera in the face's plane");
        assertFalse(HolePasses.underCopy(false, -0.5), "a hole on glass with clear pixels");
    }

    // a batch of holes under glass never shares a key with one of holes drawn late only, or early only
    @Test
    void batchKeysApart() {
        Set<Integer> keys = new HashSet<>();
        int n = 0;
        for (int kind = 0; kind < 16; kind++)
            for (int f = 0; f < 8; f++, n++) keys.add(HolePasses.batchKey(kind, (f & 4) != 0, (f & 2) != 0, (f & 1) != 0));
        assertEquals(n, keys.size());
    }

    // the batches and the holes sent each frame ask the same rule
    @Test
    void bothWaysAskTheRule() throws IOException {
        String src = Files.readString(DECALS, StandardCharsets.UTF_8);
        assertTrue(src.contains("if (late ? !b.late : !HolePasses.early(b.late, b.under)) continue;"), "drawBatches leaves the copy under the glass out");
        assertTrue(src.contains("if (h.late && HolePasses.underCopy(host.under(h), towardCamera(h, camX, camY, camZ)))"),
                "the holes sent each frame leave the copy under the glass out");
        // the batches' copy under the glass: their backs alone, flat and cut
        assertTrue(src.contains("boolean back = !late && b.late;"), "drawBatches draws a whole late batch under the glass");
        assertTrue(src.contains("b.backCount = bake(b, true, b.back);"), "an under batch has no buffer of backs");
        assertTrue(src.contains("if (how == BAKE) quadAt("), "a flat hole's front in the backs' buffer");
        assertTrue(src.contains("for (int copy = back ? 1 : 0; copy < copies; copy++) {"), "a cut hole's front in the backs' buffer");
        assertTrue(src.contains("HolePasses.batchKey(h.kind, under, h.late, h.mesh != null)"), "a batch is not kept apart by under");
    }
}
