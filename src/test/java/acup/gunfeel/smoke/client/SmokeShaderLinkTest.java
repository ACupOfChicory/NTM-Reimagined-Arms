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
        // the matrix, four vectors, the inverse matrix, the clouds, the groups
        assertEquals(64 + 5 * 16 + 64 + SmokeAtlas.SLOTS * 4 * 16 + SmokeAtlas.SLOTS * 2 * 16, SmokeRender.UBO_SIZE);
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

    // the source as a variant with these flags sees it: #ifdef, #ifndef, #if / #elif with (!)defined(X) joined by
    // && or ||, #else, #endif
    private static String variant(String src, java.util.Set<String> flags) {
        StringBuilder out = new StringBuilder();
        java.util.ArrayDeque<boolean[]> stack = new java.util.ArrayDeque<>(); // {this branch on, one branch taken, outer on}
        boolean on = true;
        for (String line : src.split("\n")) {
            String t = line.trim();
            if (t.startsWith("#ifdef ") || t.startsWith("#ifndef ") || t.startsWith("#if ")) {
                boolean c = t.startsWith("#ifdef ") ? flags.contains(t.substring(7).trim())
                        : t.startsWith("#ifndef ") ? !flags.contains(t.substring(8).trim()) : condition(t.substring(4), flags);
                stack.push(new boolean[]{c, c, on});
                on = on && c;
            } else if (t.startsWith("#elif ")) {
                boolean[] top = stack.peek();
                boolean c = !top[1] && condition(t.substring(6), flags);
                top[0] = c;
                top[1] |= c;
                on = top[2] && c;
            } else if (t.equals("#else")) {
                boolean[] top = stack.peek();
                top[0] = !top[1];
                top[1] = true;
                on = top[2] && top[0];
            } else if (t.equals("#endif")) {
                on = stack.pop()[2];
            } else if (on) out.append(line).append('\n');
        }
        org.junit.jupiter.api.Assertions.assertTrue(stack.isEmpty(), "an #if is not closed");
        return out.toString();
    }

    private static boolean condition(String expr, java.util.Set<String> flags) {
        String e = expr.trim();
        boolean or = e.contains("||");
        org.junit.jupiter.api.Assertions.assertFalse(or && e.contains("&&"), "the test reads && or ||, not both: " + e);
        boolean result = !or;
        for (String part : e.split(or ? "\\|\\|" : "&&")) {
            Matcher m = Pattern.compile("(!?)\\s*defined\\s*\\(?\\s*(\\w+)\\s*\\)?").matcher(part.trim());
            org.junit.jupiter.api.Assertions.assertTrue(m.matches(), "the test cannot read #if " + e);
            boolean v = flags.contains(m.group(2)) != m.group(1).equals("!");
            result = or ? result || v : result && v;
        }
        return result;
    }

    private static final Pattern SAMPLER = Pattern.compile("uniform\\s+sampler2D\\s+(\\w+)\\s*;");
    private static final Pattern BLOCK = Pattern.compile("uniform\\s+(\\w+)\\s*\\{");
    private static final Pattern IMPORT = Pattern.compile("#moj_import\\s*<(\\w+):([\\w/.]+)>");

    // the samplers and uniform blocks a stage of this variant declares, its imports in
    private static void declared(String src, java.util.Set<String> flags, java.util.Set<String> samplers, java.util.Set<String> blocks) throws Exception {
        String v = variant(src, flags);
        Matcher m = SAMPLER.matcher(v);
        while (m.find()) samplers.add(m.group(1));
        m = BLOCK.matcher(v);
        while (m.find()) blocks.add(m.group(1));
        m = IMPORT.matcher(v);
        while (m.find()) {
            if (m.group(1).equals("ntmgunfeel")) declared(resource("include/" + m.group(2)), flags, samplers, blocks);
            else if (m.group(2).equals("projection.glsl")) blocks.add("Projection");
            else if (m.group(2).equals("globals.glsl")) blocks.add("Globals");
            else throw new AssertionError("the test does not know " + m.group());
        }
    }

    /**
     * Every sampler and uniform block a smoke pipeline's shaders declare in its variant is in its layout. Vulkan's
     * compiler (no optimisation) keeps each one declared, and a pipeline whose shader has one its layout lacks does
     * not load: the game then stops at its resources (the transparency chain's composites and the march once did).
     */
    @Test
    void everySmokePipelineDeclaresOnlyWhatItsLayoutHas() throws Exception {
        int checked = 0;
        for (var f : SmokeRender.class.getDeclaredFields()) {
            if (f.getType() != com.mojang.blaze3d.pipeline.RenderPipeline.class || !java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            var pipeline = (com.mojang.blaze3d.pipeline.RenderPipeline) f.get(null);
            var flags = new java.util.HashSet<>(pipeline.getShaderDefines().flags());
            flags.addAll(pipeline.getShaderDefines().values().keySet());
            var samplers = new java.util.TreeSet<String>();
            var blocks = new java.util.TreeSet<String>();
            for (var shader : new net.minecraft.resources.Identifier[]{pipeline.getVertexShader(), pipeline.getFragmentShader()}) {
                if (!shader.getNamespace().equals("ntmgunfeel")) continue;
                String ext = shader == pipeline.getVertexShader() ? ".vsh" : ".fsh";
                declared(resource(shader.getPath() + ext), flags, samplers, blocks);
            }
            var layouts = pipeline.getBindGroupLayouts();
            var haveSamplers = com.mojang.blaze3d.pipeline.BindGroupLayout.flattenSamplers(layouts);
            var haveBlocks = com.mojang.blaze3d.pipeline.BindGroupLayout.flattenUniforms(layouts).stream().map(u -> u.name()).toList();
            for (String s : samplers) org.junit.jupiter.api.Assertions.assertTrue(haveSamplers.contains(s), f.getName() + ": sampler " + s + " is not in its layout");
            for (String b : blocks) org.junit.jupiter.api.Assertions.assertTrue(haveBlocks.contains(b), f.getName() + ": uniform block " + b + " is not in its layout");
            checked++;
        }
        org.junit.jupiter.api.Assertions.assertTrue(checked >= 7, "pipelines checked: " + checked);
    }
}
