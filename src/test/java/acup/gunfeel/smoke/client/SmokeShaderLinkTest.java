package acup.gunfeel.smoke.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import acup.gunfeel.smoke.SmokeGrid;

/** The sizes the Java and the GLSL of the volume each state on their own must agree. */
class SmokeShaderLinkTest {
    private static String resource(String path) throws Exception {
        try (var in = SmokeShaderLinkTest.class.getResourceAsStream("/assets/ntmgunfeel/shaders/" + path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static float constant(String glsl, String name) {
        Matcher m = Pattern.compile("const float " + name + " = ([0-9.]+);").matcher(glsl);
        if (!m.find()) throw new AssertionError(name + " is not in the shader");
        return Float.parseFloat(m.group(1));
    }

    @Test
    void theUniformArrayHoldsFourVectorsASlot() throws Exception {
        Matcher m = Pattern.compile("vec4 Clouds\\[(\\d+)\\];").matcher(resource("include/smoke_info.glsl"));
        if (!m.find()) throw new AssertionError("no Clouds[] in smoke_info.glsl");
        assertEquals(SmokeAtlas.SLOTS * 4, Integer.parseInt(m.group(1)));
        // the matrix, three vectors, the clouds
        assertEquals(64 + 3 * 16 + SmokeAtlas.SLOTS * 4 * 16, SmokeRender.UBO_SIZE);
    }

    @Test
    void theAtlasLayoutIsTheSame() throws Exception {
        String fsh = resource("core/smoke_march.fsh");
        assertEquals(SmokeAtlas.TILE_W, constant(fsh, "TILE"));
        assertEquals(SmokeAtlas.TILE_H, constant(fsh, "TILE"));
        assertEquals(SmokeGrid.SY, constant(fsh, "LAYERS"));
        assertEquals(SmokeGrid.SX, constant(fsh, "CELLS_XZ"));
        assertEquals(SmokeGrid.SZ, constant(fsh, "CELLS_XZ"));
        // the density byte's scale
        org.junit.jupiter.api.Assertions.assertTrue(fsh.contains("* " + SmokeAtlas.SCALE), "the shader no longer scales the density by SCALE");
    }
}
