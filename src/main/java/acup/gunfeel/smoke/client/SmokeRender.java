package acup.gunfeel.smoke.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import acup.gunfeel.Config;
import acup.gunfeel.GunFeel;
import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Client: the clouds drawn as a volume (technique A of the research). Three steps a frame, three draw calls in all:
 * <ol>
 * <li>after the opaque world and its mobs ({@code AfterOpaqueFeatures}) the depth buffer is copied: the smoke ends at
 * the first solid thing, a wall cuts it, and glass and water drawn later do not hide smoke behind them;</li>
 * <li>after the sky's clouds and the weather ({@code AfterWeather}, see {@link #afterWeather}) every drawn cloud's box goes into a target of its own at a part of the screen's
 * resolution in one draw (36 vertices a box, from {@code gl_VertexID}): the box's far side starts a ray from the camera
 * that gathers smoke through the cell atlas (Beer-Lambert) until the box ends, the depth copy says the world is there,
 * or it lets almost nothing through; the boxes go far to near, each over the ones behind;</li>
 * <li>that target is blended over the frame. Where glass or water is in front of the world, the frame is taken apart
 * first: its alpha was cleared before they were drawn (so it ends as their cover), the smoke is marched once to the world
 * and once to the nearest of them, and the smoke behind the glass goes under it (dimmed and tinted by it), the smoke in
 * front of the glass over it ({@code smoke_composite.fsh}). With the transparency chain (improved transparency) glass
 * and water are drawn into a target of their own, laid over the main one after this: there the smoke behind them goes
 * into the main target and the smoke in front of them into theirs, so the chain lays each where it is.</li>
 * </ol>
 * The hand and the gun are drawn after the level and stay in front of the smoke. 26.2 draws with the depth reversed
 * (near 1, far and the sky 0) and either depth range: the shader finds where along its ray the copied depth lies by
 * the projection itself, so neither matters.
 */
public final class SmokeRender {
    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("ntmgunfeel", path);
    }

    static final BindGroupLayout MARCH_LAYOUT = BindGroupLayout.builder()
            .withUniform("SmokeInfo", UniformType.UNIFORM_BUFFER)
            .withSampler("SmokeAtlas")
            .withSampler("SceneDepth")
            .build();
    public static final RenderPipeline MARCH = RenderPipeline.builder()
            .withLocation(id("pipeline/smoke_march"))
            .withVertexShader(id("core/smoke_march"))
            .withFragmentShader(id("core/smoke_march"))
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(MARCH_LAYOUT)
            .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA), GpuFormat.RGBA8_UNORM,
                    ColorTargetState.WRITE_ALL))
            .withCull(false)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();
    // the same march for the part of the ray in front of the nearest translucent surface (see afterWeather)
    static final BindGroupLayout MARCH_FRONT_LAYOUT = BindGroupLayout.builder()
            .withUniform("SmokeInfo", UniformType.UNIFORM_BUFFER)
            .withSampler("SmokeAtlas")
            .withSampler("SceneDepth")
            .withSampler("OpaqueDepth")
            .build();
    public static final RenderPipeline MARCH_FRONT = RenderPipeline.builder()
            .withLocation(id("pipeline/smoke_march_front"))
            .withVertexShader(id("core/smoke_march"))
            .withFragmentShader(id("core/smoke_march"))
            .withShaderDefine("FRONT")
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(MARCH_FRONT_LAYOUT)
            .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA), GpuFormat.RGBA8_UNORM,
                    ColorTargetState.WRITE_ALL))
            .withCull(false)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();
    // the frame's alpha channel alone: cleared before the translucent things are drawn, so that it ends as their cover
    public static final RenderPipeline ALPHA_ZERO = alphaPipeline("smoke_alpha_zero", false);
    public static final RenderPipeline ALPHA_ONE = alphaPipeline("smoke_alpha_one", true);

    private static RenderPipeline alphaPipeline(String name, boolean one) {
        RenderPipeline.Builder b = RenderPipeline.builder().withLocation(id("pipeline/" + name))
                .withVertexShader("core/screenquad")
                .withFragmentShader(id("core/smoke_alpha"))
                .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALPHA))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
        if (one) b.withShaderDefine("ALPHA_ONE");
        return b.build();
    }

    static final BindGroupLayout BLEND_LAYOUT = BindGroupLayout.builder()
            .withSampler("FramePost")
            .withSampler("FrameOpaque")
            .withSampler("SmokeFront")
            .withSampler("SmokeFull")
            .withSampler("Depth0")
            .withSampler("Depth1")
            .build();
    // the smoke put into a frame that has glass or water in it: replaces the frame's pixels (alpha 1 again)
    public static final RenderPipeline COMPOSITE_GLASS = RenderPipeline.builder()
            .withLocation(id("pipeline/smoke_composite_glass"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(id("core/smoke_composite"))
            .withBindGroupLayout(BLEND_LAYOUT)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();
    // with the transparency chain (improved transparency) glass and water are in a target of their own: the smoke behind
    // them goes into the main target, the smoke in front of them into theirs (smoke_composite.fsh, CHAIN_MAIN/CHAIN_GLASS)
    static final BindGroupLayout CHAIN_MAIN_LAYOUT = BindGroupLayout.builder()
            .withSampler("FrameOpaque")
            .withSampler("SmokeFront")
            .withSampler("SmokeFull")
            .withSampler("Depth0")
            .withSampler("Depth1")
            .build();
    static final BindGroupLayout CHAIN_GLASS_LAYOUT = BindGroupLayout.builder()
            .withSampler("FramePost")
            .withSampler("SmokeFront")
            .withSampler("SmokeFull")
            .withSampler("Depth0")
            .withSampler("Depth1")
            .build();
    public static final RenderPipeline COMPOSITE_CHAIN_MAIN = chainPipeline("smoke_composite_chain_main", "CHAIN_MAIN", CHAIN_MAIN_LAYOUT);
    public static final RenderPipeline COMPOSITE_CHAIN_GLASS = chainPipeline("smoke_composite_chain_glass", "CHAIN_GLASS", CHAIN_GLASS_LAYOUT);

    private static RenderPipeline chainPipeline(String name, String define, BindGroupLayout layout) {
        return RenderPipeline.builder()
                .withLocation(id("pipeline/" + name))
                .withVertexShader("core/screenquad")
                .withFragmentShader(id("core/smoke_composite"))
                .withShaderDefine(define)
                .withBindGroupLayout(layout)
                .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .build();
    }

    public static final RenderPipeline COMPOSITE = RenderPipeline.builder()
            .withLocation(id("pipeline/smoke_composite"))
            .withVertexShader("core/screenquad")
            .withFragmentShader("core/blit_screen")
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA), GpuFormat.RGBA8_UNORM,
                    ColorTargetState.WRITE_COLOR))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();

    // the matrix and three vec4, then per cloud (SmokeAtlas.SLOTS of them) four vec4: origin, box min, box max, colour.
    // Clouds[] in smoke_info.glsl must be SLOTS * 4 long (SmokeShaderLinkTest checks)
    static final int UBO_SIZE = 64 + 16 * 3 + SmokeAtlas.SLOTS * 4 * 16;
    private static MappableRingBuffer ubo;
    private static GpuTexture depth, target;
    private static GpuTextureView depthView, targetView;
    private static int depthW, depthH, targetW, targetH;
    private static GpuFormat depthFormat;
    private static boolean copied, irisPack;
    // glass and water in the frame (see afterWeather): the frame before them, the frame after them, the depth after them,
    // and the smoke in front of the nearest of them
    private static final Copy OPAQUE_COLOR = new Copy("Smoke opaque frame", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING);
    private static final Copy POST_COLOR = new Copy("Smoke frame", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING);
    private static final Copy POST_DEPTH = new Copy("Smoke depth with glass", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING);
    private static final Copy FRONT = new Copy("Smoke in front", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING);
    // the frame's alpha was cleared in afterOpaque and not yet put back
    private static boolean alphaCleared;

    /** A texture of the frame's size (or a divisor of it) made again when that changes. */
    private static final class Copy {
        final String name;
        final int usage;
        GpuTexture tex;
        GpuTextureView view;
        int w, h;
        GpuFormat format;

        Copy(String name, int usage) {
            this.name = name;
            this.usage = usage;
        }

        GpuTextureView ensure(GpuFormat f, int width, int height) {
            if (tex == null || w != width || h != height || format != f) {
                close();
                w = width;
                h = height;
                format = f;
                tex = RenderSystem.getDevice().createTexture(() -> name, usage, f, width, height, 1, 1);
                view = RenderSystem.getDevice().createTextureView(tex);
            }
            return view;
        }

        void close() {
            if (view != null) view.close();
            if (tex != null) tex.close();
            view = null;
            tex = null;
            w = h = 0;
        }
    }
    // passes that threw: how many times, and when last (see failedNow)
    private static int failures;
    private static long failedAt;
    private static final int RETRIES = 3;
    private static final long RETRY_NANOS = 10_000_000_000L, HEALED_NANOS = 60_000_000_000L;
    private static long irisChecked;
    private static int drawnLast;

    private SmokeRender() {
    }

    public static void register(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent.class, e -> {
            e.registerPipeline(MARCH);
            e.registerPipeline(MARCH_FRONT);
            e.registerPipeline(ALPHA_ZERO);
            e.registerPipeline(ALPHA_ONE);
            e.registerPipeline(COMPOSITE_GLASS);
            e.registerPipeline(COMPOSITE_CHAIN_MAIN);
            e.registerPipeline(COMPOSITE_CHAIN_GLASS);
            e.registerPipeline(COMPOSITE);
        });
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

    // the share of the screen's width and height the volume is drawn at
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

    // Iris with a shader pack draws into its own targets: our pass would land in the wrong one (research 3.2)
    public static boolean irisPack() {
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

    // the drawn clouds whose box is in the view this frame
    private static final List<SmokeAtlas.Slot> VISIBLE = new ArrayList<>();

    // whether any of the cloud's box (the shader's: the occupied cells and one around them) is inside the view frustum
    private static boolean inView(SmokeClouds.Cloud c, net.minecraft.client.renderer.culling.Frustum frustum) {
        int[] b = c.grid.box();
        return frustum.isVisible(new net.minecraft.world.phys.AABB(c.baseX() + b[0] - 1, c.baseY() + b[1] - 1, c.baseZ() + b[2] - 1,
                c.baseX() + b[3] + 2, c.baseY() + b[4] + 2, c.baseZ() + b[5] + 2));
    }

    // the transparency chain's target for glass and water (improved transparency), or null without the chain
    private static RenderTarget chainTarget() {
        return Minecraft.getInstance().levelRenderer.translucentTarget();
    }

    // how glass was told from the rest in the last frame that marched: "none" (nothing marched), "alpha" (the frame's
    // alpha) or "chain" (the transparency chain's target); for the stand
    private static String glassMode = "none";

    public static String glassMode() {
        return glassMode;
    }

    private static void alphaPass(RenderTarget main, RenderPipeline pipeline) {
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Smoke alpha", main.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.draw(3, 1, 0, 0);
        }
    }

    /**
     * AfterOpaqueFeatures: the world's depth before glass and water. Only when a drawn cloud is in the view: with none
     * the depth is not copied and nothing is marched or laid over the frame.
     */
    public static void afterOpaque(RenderLevelStageEvent.AfterOpaqueFeatures e) {
        copied = false;
        VISIBLE.clear();
        if (!wanted() || SmokeAtlas.drawn().isEmpty()) return;
        var camera = e.getLevelRenderState().cameraRenderState;
        // the frustum is the camera's own unless the F4 frustum capture froze it
        boolean cull = camera.initialized && !camera.isFrustumCaptured;
        for (SmokeAtlas.Slot s : SmokeAtlas.drawn()) if (!cull || inView(s.cloud(), camera.cullFrustum)) VISIBLE.add(s);
        if (VISIBLE.isEmpty()) return;
        long t0 = System.nanoTime();
        try {
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            GpuTexture src = main.getDepthTexture();
            if (src == null) return;
            if (depth == null || depthW != main.width || depthH != main.height || depthFormat != src.getFormat()) {
                closeDepth();
                depthW = main.width;
                depthH = main.height;
                depthFormat = src.getFormat();
                depth = RenderSystem.getDevice().createTexture(() -> "Smoke scene depth", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                        depthFormat, depthW, depthH, 1, 1);
                depthView = RenderSystem.getDevice().createTextureView(depth);
            }
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(src, depth, 0, 0, 0, 0, 0, depthW, depthH);
            copied = true;
            if (chainTarget() == null) {
                // the frame before glass and water, and its alpha at 0: what they draw then leaves their cover in it
                GpuTexture color = main.getColorTexture();
                OPAQUE_COLOR.ensure(color.getFormat(), main.width, main.height);
                RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(color, OPAQUE_COLOR.tex, 0, 0, 0, 0, 0, main.width, main.height);
                alphaPass(main, ALPHA_ZERO);
                alphaCleared = true;
            }
        } catch (RuntimeException ex) {
            fail("depth copy", ex);
        } finally {
            SmokeStats.add(SmokeStats.COPY, System.nanoTime() - t0);
        }
    }

    /**
     * AfterWeather: the clouds marched and laid over the frame. This late because the game draws the sky's own clouds
     * after the whole main pass (its "clouds" pass, then "weather"): they test against the world's depth, which smoke
     * does not write, so smoke laid down before them would have them drawn over it wherever the sky is behind it, and
     * show them through its edges. After them the smoke covers them as it covers the sky. The price: rain in front of
     * a cloud is covered by it too (with the transparency chain, "fabulous", the sky's clouds and the rain are laid over
     * the frame after this and still lie over the smoke; glass and water are told from the rest, see the class).
     */
    public static void afterWeather(RenderLevelStageEvent.AfterWeather e) {
        try {
            marchAndLay(e);
        } finally {
            // whatever happened, the frame must not leave with the alpha cleared in afterOpaque
            if (alphaCleared) {
                alphaCleared = false;
                try {
                    alphaPass(Minecraft.getInstance().gameRenderer.mainRenderTarget(), ALPHA_ONE);
                } catch (RuntimeException ex) {
                    GunFeel.LOGGER.debug("Smoke: the frame's alpha was not put back", ex);
                }
            }
        }
    }

    private static void marchAndLay(RenderLevelStageEvent.AfterWeather e) {
        drawnLast = 0;
        glassMode = "none";
        if (!copied || !wanted()) return;
        copied = false;
        List<SmokeAtlas.Slot> slots = VISIBLE;
        if (slots.isEmpty() || SmokeAtlas.view() == null) return;
        long t0 = System.nanoTime();
        try {
            int q = quality();
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            int div = divisor(q), w = Math.max(1, main.width / div), h = Math.max(1, main.height / div);
            if (target == null || targetW != w || targetH != h) {
                closeTarget();
                targetW = w;
                targetH = h;
                target = RenderSystem.getDevice().createTexture(() -> "Smoke volume", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                        GpuFormat.RGBA8_UNORM, w, h, 1, 1);
                targetView = RenderSystem.getDevice().createTextureView(target);
            }
            // glass or water may be in the frame: the depth and the frame as they are now, and a target for the smoke in front.
            // Without the chain they are in the main target (its alpha their cover); with it in the chain's own target,
            // over the opaque world in the main one
            RenderTarget chain = alphaCleared ? null : chainTarget();
            boolean glass = alphaCleared || chain != null;
            if (glass) {
                RenderTarget with = chain != null ? chain : main;
                POST_DEPTH.ensure(depthFormat, depthW, depthH);
                RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(with.getDepthTexture(), POST_DEPTH.tex, 0, 0, 0, 0, 0, depthW, depthH);
                POST_COLOR.ensure(with.getColorTexture().getFormat(), with.width, with.height);
                RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(with.getColorTexture(), POST_COLOR.tex, 0, 0, 0, 0, 0, with.width, with.height);
                if (chain != null) {
                    OPAQUE_COLOR.ensure(main.getColorTexture().getFormat(), main.width, main.height);
                    RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(main.getColorTexture(), OPAQUE_COLOR.tex, 0, 0, 0, 0, 0, main.width, main.height);
                }
                FRONT.ensure(GpuFormat.RGBA8_UNORM, w, h);
            }
            glassMode = chain != null ? "chain" : glass ? "alpha" : "none";
            if (ubo == null) ubo = new MappableRingBuffer(() -> "Smoke info", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UBO_SIZE);
            Vec3 cam = e.getLevelRenderState().cameraRenderState.pos;
            // far to near, each box over the ones behind it
            List<SmokeAtlas.Slot> order = new ArrayList<>(slots);
            order.sort(Comparator.comparingDouble((SmokeAtlas.Slot s) -> -centre(s.cloud(), cam)));
            float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
            boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
            try (var view = ubo.currentBuffer().map(false, true)) {
                Std140Builder b = Std140Builder.intoBuffer(view.data());
                b.putMat4f(new Matrix4f(e.getModelViewMatrix()));
                b.putVec4((float) depthW / w, zeroToOne ? 1F : 0F, (float) ((System.nanoTime() / 1_000_000L) % 3_600_000L) / 1000F, maxSteps(q));
                b.putVec4(1F / SmokeAtlas.WIDTH, 1F / SmokeAtlas.HEIGHT, partial, stepLength(q));
                b.putVec4(order.size(), q >= 3 ? 1F : 0F, (float) depthH / h, 0F);
                for (int i = 0; i < SmokeAtlas.SLOTS; i++) {
                    if (i >= order.size()) {
                        for (int k = 0; k < 4; k++) b.putVec4(0F, 0F, 0F, 0F);
                        continue;
                    }
                    SmokeAtlas.Slot s = order.get(i);
                    SmokeClouds.Cloud c = s.cloud();
                    int[] box = c.grid.box();
                    b.putVec4((float) (c.baseX() - cam.x), (float) (c.baseY() - cam.y), (float) (c.baseZ() - cam.z), s.index());
                    // one cell around the occupied box: the edge cells fade out into it, and the cloud grows into it
                    b.putVec4(Math.max(-1, box[0] - 1), Math.max(-1, box[1] - 1), Math.max(-1, box[2] - 1), c.kind().look());
                    b.putVec4(Math.min(SmokeGrid.SX + 1, box[3] + 2), Math.min(SmokeGrid.SY + 1, box[4] + 2), Math.min(SmokeGrid.SZ + 1, box[5] + 2),
                            (c.grid.seed() & 1023) * 0.37F);
                    int rgb = c.kind().rgb();
                    b.putVec4((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F, 1F);
                }
            }
            var device = RenderSystem.getDevice();
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            int pair = stampStart(device);
            long tm = System.nanoTime();
            if (glass) {
                // the smoke from the eye to the nearest translucent surface, where there is one
                try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Smoke march in front", FRONT.view, Optional.of(new Vector4f(0F, 0F, 0F, 0F)),
                        null, OptionalDouble.empty())) {
                    pass.setPipeline(MARCH_FRONT);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("SmokeInfo", ubo.currentBuffer());
                    pass.bindTexture("SmokeAtlas", SmokeAtlas.view(), sampler);
                    pass.bindTexture("SceneDepth", POST_DEPTH.view, nearest);
                    pass.bindTexture("OpaqueDepth", depthView, nearest);
                    pass.draw(36 * order.size(), 1, 0, 0);
                }
            }
            try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Smoke march", targetView, Optional.of(new Vector4f(0F, 0F, 0F, 0F)),
                    null, OptionalDouble.empty())) {
                pass.setPipeline(MARCH);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("SmokeInfo", ubo.currentBuffer());
                pass.bindTexture("SmokeAtlas", SmokeAtlas.view(), sampler);
                pass.bindTexture("SceneDepth", depthView, nearest);
                pass.draw(36 * order.size(), 1, 0, 0);
            }
            SmokeStats.add(SmokeStats.MARCH, System.nanoTime() - tm);
            long tc = System.nanoTime();
            if (chain != null) {
                // the main target: the world with the smoke behind the glass; the chain's: the smoke in front over the glass
                try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Smoke composite behind glass", main.getColorTextureView(), Optional.empty())) {
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setPipeline(COMPOSITE_CHAIN_MAIN);
                    pass.bindTexture("FrameOpaque", OPAQUE_COLOR.view, nearest);
                    bindSmoke(pass, sampler, nearest);
                    pass.draw(3, 1, 0, 0);
                }
                try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Smoke composite in front of glass", chain.getColorTextureView(), Optional.empty())) {
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setPipeline(COMPOSITE_CHAIN_GLASS);
                    pass.bindTexture("FramePost", POST_COLOR.view, nearest);
                    bindSmoke(pass, sampler, nearest);
                    pass.draw(3, 1, 0, 0);
                }
            } else try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Smoke composite", main.getColorTextureView(), Optional.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                if (glass) {
                    pass.setPipeline(COMPOSITE_GLASS);
                    pass.bindTexture("FramePost", POST_COLOR.view, nearest);
                    pass.bindTexture("FrameOpaque", OPAQUE_COLOR.view, nearest);
                    bindSmoke(pass, sampler, nearest);
                } else {
                    pass.setPipeline(COMPOSITE);
                    pass.bindTexture("InSampler", targetView, sampler);
                }
                pass.draw(3, 1, 0, 0);
            }
            // the glass composite wrote the alpha back as 1
            alphaCleared = false;
            SmokeStats.add(SmokeStats.COMPOSITE, System.nanoTime() - tc);
            if (pair >= 0) device.createCommandEncoder().writeTimestamp(timer, pair + 1);
            ubo.rotate();
            drawnLast = order.size();
            // a pass that failed once and has drawn well for a minute since is forgiven
            if (failures > 0 && failures < RETRIES && System.nanoTime() - failedAt > HEALED_NANOS) failures = 0;
        } catch (RuntimeException ex) {
            fail("raymarch", ex);
        }
    }

    // the two marches and the depths the glass composites read
    private static void bindSmoke(RenderPass pass, com.mojang.blaze3d.textures.GpuSampler linear, com.mojang.blaze3d.textures.GpuSampler nearest) {
        pass.bindTexture("SmokeFront", FRONT.view, linear);
        pass.bindTexture("SmokeFull", targetView, linear);
        pass.bindTexture("Depth0", depthView, nearest);
        pass.bindTexture("Depth1", POST_DEPTH.view, nearest);
    }

    // the card's own time on the march and the composite: two timestamps a frame in a ring of 8 frames, a pair read
    // back the next time round (by then the card is long done with it)
    private static final int TIMER_FRAMES = 8;
    private static com.mojang.blaze3d.systems.GpuQueryPool timer;
    private static final boolean[] stamped = new boolean[TIMER_FRAMES];
    private static int timerFrame;
    private static boolean timerOff;

    private static int stampStart(com.mojang.blaze3d.systems.GpuDevice device) {
        if (timerOff || !SmokeStats.MEASURING) return -1;
        try {
            if (timer == null) timer = device.createTimestampQueryPool(TIMER_FRAMES * 2);
            int f = timerFrame++ % TIMER_FRAMES;
            if (stamped[f]) {
                var v = timer.getValues(f * 2, 2);
                // in the card's ticks: nanoseconds on OpenGL, timestampPeriod of them on Vulkan (as TimerQuery takes them)
                if (v[0].isPresent() && v[1].isPresent())
                    SmokeStats.add(SmokeStats.GPU_MARCH, (long) ((v[1].getAsLong() - v[0].getAsLong()) * (double) device.getDeviceInfo().timestampPeriod()));
            }
            device.createCommandEncoder().writeTimestamp(timer, f * 2);
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

    // A pass that threw: for ten seconds the slices and the screen layer stand in, then the volume is tried again with
    // fresh targets; after the third failure it stays off until the next world. Logged once, then at debug.
    private static void fail(String what, RuntimeException ex) {
        failures++;
        failedAt = System.nanoTime();
        if (failures == 1) GunFeel.LOGGER.error("Smoke {} failed; the volume is off for a few seconds and tried again (3 times at most)", what, ex);
        else if (failures >= RETRIES) GunFeel.LOGGER.warn("Smoke {} failed again; smoke is drawn as slices and fog only until the next world", what);
        else GunFeel.LOGGER.debug("Smoke {} failed again", what, ex);
        // what it was drawing with may be in a bad state
        release(false);
    }

    // whether the volume is off after a failure: for RETRY_NANOS, and for good after RETRIES of them
    private static boolean failedNow() {
        return failures > 0 && (failures >= RETRIES || System.nanoTime() - failedAt < RETRY_NANOS);
    }

    /**
     * Closes what the volume draws with: the depth copy, the target, the uniform ring and the timestamp pool (they are
     * made again when a cloud is drawn). {@code world}: the player left the world, so the failures are forgotten too.
     */
    public static void release(boolean world) {
        closeDepth();
        closeTarget();
        OPAQUE_COLOR.close();
        POST_COLOR.close();
        POST_DEPTH.close();
        FRONT.close();
        if (ubo != null) ubo.close();
        ubo = null;
        if (timer != null) timer.close();
        timer = null;
        java.util.Arrays.fill(stamped, false);
        timerFrame = 0;
        copied = false;
        drawnLast = 0;
        if (world) failures = 0;
    }

    private static void closeDepth() {
        if (depthView != null) depthView.close();
        if (depth != null) depth.close();
        depthView = null;
        depth = null;
        depthW = depthH = 0;
    }

    private static void closeTarget() {
        if (targetView != null) targetView.close();
        if (target != null) target.close();
        targetView = null;
        target = null;
        targetW = targetH = 0;
    }

    /** Once a frame, after the level: the stats. */
    public static void frame() {
        SmokeClouds all = SmokeClient.current();
        SmokeStats.frame(all == null ? 0 : all.clouds().size(), Math.max(drawnLast, SmokeSlices.drawnLast()), SmokeFog.atCamera());
    }
}
