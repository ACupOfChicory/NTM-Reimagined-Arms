package acup.gunfeel.smoke.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;

import acup.gunfeel.Config;
import acup.gunfeel.GunFeel;
import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The port's copy of the root's SmokeRender (notes/ports/de-1.21.1.md, "Дым"). The same three steps and the root's
 * march shader, on plain OpenGL: 1.21.1 has no GpuDevice, pipelines or uniform buffers in its shaders.
 * <ul>
 * <li>The shader is the root's {@code core/smoke_march} read from the resources, its imports put in by hand: the
 * SmokeInfo block becomes plain uniforms (a uniform buffer would take a binding point Flywheel may hold), ProjMat a
 * uniform of its own. 1.21.1 draws with the depth the usual way round (the sky at 1), so the one test of "something
 * there" is turned round; the rest finds the depth along the ray by the projection, as the root's.</li>
 * <li>The depth copy is a blit into a target of ours, the march draws into a target at a part of the screen's size, the
 * composite lays it over the main target with premultiplied alpha.</li>
 * <li>What GL state it changes it puts back: the program (the game skips glUseProgram for the shader it thinks is bound),
 * the framebuffers, the viewport, blend, depth and cull.</li>
 * </ul>
 * Quality, the failure handling, the Iris check and the stats are the root's.
 */
public final class SmokeRender {
    // the matrix and three vec4, then per cloud (SmokeAtlas.SLOTS of them) four vec4: as the root's (SmokeShaderLinkTest)
    static final int UBO_SIZE = 64 + 16 * 3 + SmokeAtlas.SLOTS * 4 * 16;
    private static final int CLOUD_VEC4 = SmokeAtlas.SLOTS * 4;

    private static TextureTarget depth, target;
    private static int depthW, depthH, targetW, targetH;
    private static boolean copied, irisPack;
    private static int failures;
    private static long failedAt;
    private static final int RETRIES = 3;
    private static final long RETRY_NANOS = 10_000_000_000L, HEALED_NANOS = 60_000_000_000L;
    private static long irisChecked;
    private static int drawnLast;

    // GL objects: 0 = not made yet; a build that failed is not tried again before the next release
    private static int march, composite, vao;
    private static int uView, uProj, uScreen, uAtlasInfo, uExtra, uClouds, uAtlas, uDepth, uIn;
    private static FloatBuffer mat, clouds;
    private static Matrix4f projection = new Matrix4f();

    private SmokeRender() {
    }

    public static void register(net.neoforged.bus.api.IEventBus modBus) {
        // the programs are built on first use, from the resources as they are then
    }

    /** Quality: 0 off, 1 low, 2 medium, 3 high, 4 ultra; {@code slices} counts as low. */
    static int quality() {
        return switch (Config.smokeQuality) {
            case "off" -> 0;
            case "slices" -> 1;
            case "low" -> 1;
            case "medium" -> 2;
            case "ultra" -> 4;
            default -> 3;
        };
    }

    private static int divisor(int q) {
        return switch (q) {
            case 1 -> 4;
            case 2 -> 3;
            case 4 -> 1;
            default -> 2;
        };
    }

    private static float stepLength(int q) {
        return switch (q) {
            case 1 -> 0.6F;
            case 2 -> 0.45F;
            case 4 -> 0.22F;
            default -> 0.3F;
        };
    }

    private static int maxSteps(int q) {
        return switch (q) {
            case 1 -> 16;
            case 2 -> 28;
            case 4 -> 72;
            default -> 48;
        };
    }

    /** Whether the volume is marched: smokeQuality not off or slices, no shader pack in use, the pass did not fail. */
    public static boolean wanted() {
        return quality() > 0 && !failedNow() && !irisPack() && !"slices".equals(Config.smokeQuality);
    }

    /** Whether the clouds are drawn as slices ({@link SmokeSlices}) instead: asked for, or the march cannot be used. */
    public static boolean slicesWanted() {
        return quality() > 0 && ("slices".equals(Config.smokeQuality) || failedNow() || irisPack());
    }

    /** Whether a cloud's volume was drawn in the last frame (else the screen layer stands in when inside). */
    public static boolean drawingVolume() {
        return wanted() && drawnLast > 0;
    }

    static boolean irisPack() {
        long now = System.currentTimeMillis();
        if (now - irisChecked < 1000) return irisPack;
        irisChecked = now;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object it = api.getMethod("getInstance").invoke(null);
            irisPack = (Boolean) api.getMethod("isShaderPackInUse").invoke(it);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            irisPack = false;
        }
        return irisPack;
    }

    private static final List<SmokeAtlas.Slot> VISIBLE = new ArrayList<>();

    private static boolean inView(SmokeClouds.Cloud c, net.minecraft.client.renderer.culling.Frustum frustum) {
        int[] b = c.grid.box();
        return frustum.isVisible(new net.minecraft.world.phys.AABB(c.baseX() + b[0] - 1, c.baseY() + b[1] - 1, c.baseZ() + b[2] - 1,
                c.baseX() + b[3] + 2, c.baseY() + b[4] + 2, c.baseZ() + b[5] + 2));
    }

    /** AFTER_BLOCK_ENTITIES (the root's AfterOpaqueFeatures): the world's depth before glass and water. */
    public static void afterOpaque(RenderLevelStageEvent e) {
        copied = false;
        VISIBLE.clear();
        if (!wanted() || SmokeAtlas.drawn().isEmpty()) return;
        var frustum = e.getFrustum();
        for (SmokeAtlas.Slot s : SmokeAtlas.drawn()) if (frustum == null || inView(s.cloud(), frustum)) VISIBLE.add(s);
        if (VISIBLE.isEmpty()) return;
        long t0 = System.nanoTime();
        int read = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), draw = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            if (depth == null || depthW != main.width || depthH != main.height || depth.isStencilEnabled() != main.isStencilEnabled()) {
                closeDepth();
                depthW = main.width;
                depthH = main.height;
                depth = new TextureTarget(depthW, depthH, true, Minecraft.ON_OSX);
                // the same depth format as the main target's, or the blit is refused
                if (main.isStencilEnabled()) depth.enableStencil();
            }
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, depth.frameBufferId);
            GlStateManager._glBlitFrameBuffer(0, 0, depthW, depthH, 0, 0, depthW, depthH, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            copied = true;
        } catch (RuntimeException ex) {
            fail("depth copy", ex);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            SmokeStats.add(SmokeStats.COPY, System.nanoTime() - t0);
        }
        // the projection of this frame, for the march in AFTER_WEATHER
        projection = new Matrix4f(e.getProjectionMatrix());
    }

    /** AFTER_WEATHER: the clouds marched and laid over the frame (see the root's for why this late). */
    public static void afterWeather(RenderLevelStageEvent e) {
        drawnLast = 0;
        if (!copied || !wanted()) return;
        copied = false;
        List<SmokeAtlas.Slot> slots = VISIBLE;
        if (slots.isEmpty() || SmokeAtlas.view() == 0) return;
        int program = GlStateManager._getInteger(GL20.GL_CURRENT_PROGRAM);
        int read = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), draw = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int activeTexture = GlStateManager._getActiveTexture();
        try {
            if (!programs()) return;
            int q = quality();
            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            int div = divisor(q), w = Math.max(1, main.width / div), h = Math.max(1, main.height / div);
            if (target == null || targetW != w || targetH != h) {
                closeTarget();
                targetW = w;
                targetH = h;
                target = new TextureTarget(w, h, false, Minecraft.ON_OSX);
                target.setFilterMode(GL11.GL_LINEAR);
            }
            Vec3 cam = e.getCamera().getPosition();
            List<SmokeAtlas.Slot> order = new ArrayList<>(slots);
            order.sort(Comparator.comparingDouble((SmokeAtlas.Slot s) -> -centre(s.cloud(), cam)));
            float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);

            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            GlStateManager._glBindVertexArray(vao);

            int pair = stampStart();
            long tm = System.nanoTime();
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.frameBufferId);
            GlStateManager._viewport(0, 0, w, h);
            GlStateManager._clearColor(0F, 0F, 0F, 0F);
            GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
            GlStateManager._glUseProgram(march);
            GlStateManager._glUniformMatrix4(uView, false, put(new Matrix4f(e.getModelViewMatrix())));
            GlStateManager._glUniformMatrix4(uProj, false, put(projection));
            // 1.21.1's depth goes -1..1 (Screen.y 0)
            GL20.glUniform4f(uScreen, (float) depthW / w, 0F, (float) ((System.nanoTime() / 1_000_000L) % 3_600_000L) / 1000F, maxSteps(q));
            GL20.glUniform4f(uAtlasInfo, 1F / SmokeAtlas.WIDTH, 1F / SmokeAtlas.HEIGHT, partial, stepLength(q));
            GL20.glUniform4f(uExtra, order.size(), q >= 3 ? 1F : 0F, (float) depthH / h, 0F);
            clouds.clear();
            for (int i = 0; i < SmokeAtlas.SLOTS; i++) {
                if (i >= order.size()) {
                    for (int k = 0; k < 16; k++) clouds.put(0F);
                    continue;
                }
                SmokeAtlas.Slot s = order.get(i);
                SmokeClouds.Cloud c = s.cloud();
                int[] box = c.grid.box();
                clouds.put((float) (c.baseX() - cam.x)).put((float) (c.baseY() - cam.y)).put((float) (c.baseZ() - cam.z)).put(s.index());
                clouds.put(Math.max(-1, box[0] - 1)).put(Math.max(-1, box[1] - 1)).put(Math.max(-1, box[2] - 1)).put(c.kind().extinction());
                clouds.put(Math.min(SmokeGrid.SX + 1, box[3] + 2)).put(Math.min(SmokeGrid.SY + 1, box[4] + 2)).put(Math.min(SmokeGrid.SZ + 1, box[5] + 2))
                        .put((c.grid.seed() & 1023) * 0.37F);
                int rgb = c.kind().rgb();
                clouds.put((rgb >> 16 & 255) / 255F).put((rgb >> 8 & 255) / 255F).put((rgb & 255) / 255F).put(1F);
            }
            clouds.flip();
            GlStateManager._glUniform4(uClouds, clouds);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(SmokeAtlas.view());
            GlStateManager._glUniform1i(uAtlas, 0);
            GlStateManager._activeTexture(GL13.GL_TEXTURE1);
            GlStateManager._bindTexture(depth.getDepthTextureId());
            GlStateManager._glUniform1i(uDepth, 1);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 36 * order.size());
            SmokeStats.add(SmokeStats.MARCH, System.nanoTime() - tm);

            long tc = System.nanoTime();
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, main.frameBufferId);
            GlStateManager._viewport(0, 0, main.width, main.height);
            GlStateManager._glUseProgram(composite);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(target.getColorTextureId());
            GlStateManager._glUniform1i(uIn, 0);
            // colour only: the alpha of the main target stays as it was (the root's WRITE_COLOR)
            GlStateManager._colorMask(true, true, true, false);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GlStateManager._colorMask(true, true, true, true);
            SmokeStats.add(SmokeStats.COMPOSITE, System.nanoTime() - tc);
            if (pair >= 0) GL33.glQueryCounter(timer[pair + 1], GL33.GL_TIMESTAMP);
            drawnLast = order.size();
            if (failures > 0 && failures < RETRIES && System.nanoTime() - failedAt > HEALED_NANOS) failures = 0;
        } catch (RuntimeException ex) {
            fail("raymarch", ex);
        } finally {
            GlStateManager._activeTexture(GL13.GL_TEXTURE1);
            GlStateManager._bindTexture(0);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(0);
            GlStateManager._activeTexture(activeTexture);
            GlStateManager._glBindVertexArray(0);
            BufferUploader.invalidate();
            GlStateManager._glUseProgram(program);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            RenderSystem.defaultBlendFunc();
            if (blend) RenderSystem.enableBlend();
            else RenderSystem.disableBlend();
            if (depthTest) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull();
            else RenderSystem.disableCull();
            RenderSystem.depthMask(depthMask);
        }
    }

    private static FloatBuffer put(Matrix4f m) {
        mat.clear();
        m.get(mat);
        return mat;
    }

    // the march and the composite programs; false when they cannot be built (the slices stand in, see fail)
    private static boolean programs() {
        if (march != 0) return true;
        try {
            String vsh = source("core/smoke_march.vsh"), fsh = source("core/smoke_march.fsh");
            march = link("smoke_march", vsh, fsh);
            uView = GlStateManager._glGetUniformLocation(march, "View");
            uProj = GlStateManager._glGetUniformLocation(march, "ProjMat");
            uScreen = GlStateManager._glGetUniformLocation(march, "Screen");
            uAtlasInfo = GlStateManager._glGetUniformLocation(march, "Atlas");
            uExtra = GlStateManager._glGetUniformLocation(march, "Extra");
            uClouds = GlStateManager._glGetUniformLocation(march, "Clouds");
            uAtlas = GlStateManager._glGetUniformLocation(march, "SmokeAtlas");
            uDepth = GlStateManager._glGetUniformLocation(march, "SceneDepth");
            composite = link("smoke_composite", COMPOSITE_VSH, COMPOSITE_FSH);
            uIn = GlStateManager._glGetUniformLocation(composite, "InSampler");
            vao = GL30.glGenVertexArrays();
            if (mat == null) mat = MemoryUtil.memAllocFloat(16);
            if (clouds == null) clouds = MemoryUtil.memAllocFloat(CLOUD_VEC4 * 4);
            return true;
        } catch (IOException | RuntimeException ex) {
            closePrograms();
            throw ex instanceof RuntimeException r ? r : new IllegalStateException(ex);
        }
    }

    // the root's shader with its imports put in: SmokeInfo as plain uniforms, ProjMat, and the depth test turned round
    static String source(String path) throws IOException {
        String text = read(ResourceLocation.fromNamespaceAndPath("ntmgunfeel", "shaders/" + path));
        String info = read(ResourceLocation.fromNamespaceAndPath("ntmgunfeel", "shaders/include/smoke_info.glsl"));
        return adapt(text, info);
    }

    static String adapt(String text, String info) {
        StringBuilder uniforms = new StringBuilder("uniform mat4 ProjMat;\n");
        boolean inBlock = false;
        for (String line : info.split("\n")) {
            String t = line.trim();
            if (t.startsWith("#version") || t.startsWith("//") || t.isEmpty()) continue;
            if (t.startsWith("layout(std140) uniform SmokeInfo")) {
                inBlock = true;
                continue;
            }
            if (t.startsWith("};")) {
                inBlock = false;
                continue;
            }
            if (inBlock) uniforms.append("uniform ").append(t.replaceAll("\\s*//.*$", "")).append('\n');
        }
        String out = text.replace("#moj_import <minecraft:projection.glsl>\n", "").replace("#moj_import <ntmgunfeel:smoke_info.glsl>\n", uniforms.toString());
        if (out.contains("#moj_import")) throw new IllegalStateException("smoke shader: an import left");
        // the copied depth: "something there" is below 1 here (the root's 26.2 has the sky at 0)
        String sky = "if (raw > 0.0) {";
        if (text.contains("SceneDepth")) {
            if (!out.contains(sky)) throw new IllegalStateException("smoke shader: the depth test changed in the root, see SmokeRender");
            out = out.replace(sky, "if (raw < 1.0) {");
        }
        return out;
    }

    private static String read(ResourceLocation at) throws IOException {
        var res = Minecraft.getInstance().getResourceManager().getResourceOrThrow(at);
        try (InputStream in = res.open()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private static int link(String name, String vsh, String fsh) {
        int v = compile(name, GL20.GL_VERTEX_SHADER, vsh), f = compile(name, GL20.GL_FRAGMENT_SHADER, fsh);
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, v);
        GL20.glAttachShader(p, f);
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(v);
        GL20.glDeleteShader(f);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(p);
            GL20.glDeleteProgram(p);
            throw new IllegalStateException(name + " does not link: " + log);
        }
        return p;
    }

    private static int compile(String name, int type, String text) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, text);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(s);
            GL20.glDeleteShader(s);
            throw new IllegalStateException(name + " does not compile: " + log);
        }
        return s;
    }

    // the root's composite: 26.2's screenquad and blit_screen, a triangle over the screen sampling the volume
    private static final String COMPOSITE_VSH = """
            #version 330
            out vec2 texCoord;
            void main() {
                vec2 uv = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                texCoord = uv;
                gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String COMPOSITE_FSH = """
            #version 330
            uniform sampler2D InSampler;
            in vec2 texCoord;
            out vec4 fragColor;
            void main() {
                fragColor = texture(InSampler, texCoord);
            }
            """;

    // the card's own time on the march and the composite: two timestamps a frame in a ring of 8 frames, a pair read
    // back the next time round (by then the card is long done with it)
    private static final int TIMER_FRAMES = 8;
    private static int[] timer;
    private static final boolean[] stamped = new boolean[TIMER_FRAMES];
    private static int timerFrame;
    private static boolean timerOff;

    private static int stampStart() {
        if (timerOff || !SmokeStats.MEASURING) return -1;
        try {
            if (timer == null) {
                timer = new int[TIMER_FRAMES * 2];
                GL15.glGenQueries(timer);
            }
            int f = timerFrame++ % TIMER_FRAMES;
            if (stamped[f]) {
                long a = GL33.glGetQueryObjecti64(timer[f * 2], GL15.GL_QUERY_RESULT), b = GL33.glGetQueryObjecti64(timer[f * 2 + 1], GL15.GL_QUERY_RESULT);
                SmokeStats.add(SmokeStats.GPU_MARCH, b - a);
            }
            GL33.glQueryCounter(timer[f * 2], GL33.GL_TIMESTAMP);
            stamped[f] = true;
            return f * 2;
        } catch (RuntimeException ex) {
            GunFeel.LOGGER.debug("Smoke: no timestamps on this card ({}), its own time is not measured", ex.toString());
            timerOff = true;
            return -1;
        }
    }

    private static double centre(SmokeClouds.Cloud c, Vec3 cam) {
        int[] b = c.grid.box();
        double x = c.baseX() + (b[0] + b[3] + 1) * 0.5, y = c.baseY() + (b[1] + b[4] + 1) * 0.5, z = c.baseZ() + (b[2] + b[5] + 1) * 0.5;
        return cam.distanceToSqr(x, y, z);
    }

    private static void fail(String what, RuntimeException ex) {
        failures++;
        failedAt = System.nanoTime();
        if (failures == 1) GunFeel.LOGGER.error("Smoke {} failed; the volume is off for a few seconds and tried again (3 times at most)", what, ex);
        else if (failures >= RETRIES) GunFeel.LOGGER.warn("Smoke {} failed again; smoke is drawn as slices and fog only until the next world", what);
        else GunFeel.LOGGER.debug("Smoke {} failed again", what, ex);
        release(false);
    }

    private static boolean failedNow() {
        return failures > 0 && (failures >= RETRIES || System.nanoTime() - failedAt < RETRY_NANOS);
    }

    /**
     * Closes what the volume draws with: the depth copy, the target, the programs and the timestamps (made again when a
     * cloud is drawn). {@code world}: the player left the world, so the failures are forgotten too.
     */
    public static void release(boolean world) {
        closeDepth();
        closeTarget();
        closePrograms();
        if (timer != null) GL15.glDeleteQueries(timer);
        timer = null;
        java.util.Arrays.fill(stamped, false);
        timerFrame = 0;
        copied = false;
        drawnLast = 0;
        if (world) failures = 0;
    }

    private static void closePrograms() {
        if (march != 0) GL20.glDeleteProgram(march);
        if (composite != 0) GL20.glDeleteProgram(composite);
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        march = composite = vao = 0;
    }

    private static void closeDepth() {
        if (depth != null) depth.destroyBuffers();
        depth = null;
        depthW = depthH = 0;
    }

    private static void closeTarget() {
        if (target != null) target.destroyBuffers();
        target = null;
        targetW = targetH = 0;
    }

    /** Once a frame, after the level: the stats. */
    public static void frame() {
        SmokeClouds all = SmokeClient.current();
        SmokeStats.frame(all == null ? 0 : all.clouds().size(), Math.max(drawnLast, SmokeSlices.drawnLast()), SmokeFog.atCamera());
    }
}
