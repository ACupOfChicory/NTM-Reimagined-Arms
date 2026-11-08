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
        Matcher g = Pattern.compile("vec4 Groups\\[(\\d+)\\];").matcher(resource("include/smoke_info.glsl"));
        if (!g.find()) throw new AssertionError("no Groups[] in smoke_info.glsl");
        assertEquals(SmokeAtlas.SLOTS * 2, Integer.parseInt(g.group(1)));
        // the matrix, four vectors, the clouds, the groups
        assertEquals(64 + 4 * 16 + SmokeAtlas.SLOTS * 4 * 16 + SmokeAtlas.SLOTS * 2 * 16, SmokeRender.UBO_SIZE);
        // the noise's lattice repeats where the anchor steps
        assertEquals(SmokeRender.ANCHOR * 0.9F, constant(resource("core/smoke_march.fsh"), "PERIOD"), 1e-3F);
        // and every octave's scale is a whole number, so its jump is a whole number of periods
        Matcher o = Pattern.compile("valueNoise\\(q \\* ([0-9.]+)").matcher(resource("core/smoke_march.fsh"));
        while (o.find()) assertEquals(Math.rint(Double.parseDouble(o.group(1))), Double.parseDouble(o.group(1)), 0.0, "octave scale " + o.group(1));
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

    @Test
    void theSamplersTheShadersDeclareAreTheOnesBound() throws Exception {
        String composite = resource("core/smoke_composite.fsh");
        for (String name : new String[]{"FramePost", "FrameOpaque", "SmokeFront", "SmokeFull", "Depth0", "Depth1"})
            org.junit.jupiter.api.Assertions.assertTrue(composite.contains("uniform sampler2D " + name + ";"), name);
        // the two passes for the transparency chain (SmokeRender.COMPOSITE_CHAIN_MAIN / _GLASS)
        org.junit.jupiter.api.Assertions.assertTrue(composite.contains("#if defined(CHAIN_MAIN)"));
        org.junit.jupiter.api.Assertions.assertTrue(composite.contains("#elif defined(CHAIN_GLASS)"));
        String march = resource("core/smoke_march.fsh");
        for (String name : new String[]{"SmokeAtlas", "SceneDepth", "OpaqueDepth"})
            org.junit.jupiter.api.Assertions.assertTrue(march.contains("uniform sampler2D " + name + ";"), name);
        org.junit.jupiter.api.Assertions.assertTrue(march.contains("#ifdef FRONT"));
    }
}
