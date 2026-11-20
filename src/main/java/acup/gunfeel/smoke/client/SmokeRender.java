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

import acup.gunfeel.host.Hook;
import acup.gunfeel.host.HookPoint;
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
 * or it lets almost nothing through. Clouds whose boxes meet are one group with one box round them all, and its ray
 * gathers all of theirs at each step (their extinctions add up, the colour is theirs by share), so overlapping clouds
 * are one smoke, not two layers in a wrong order; the noise is the world's, the same in each. The groups go far to
 * near, each over the ones behind;</li>
 * <li>that target is blended over the frame. Where glass or water is in front of the world, the frame is taken apart
 * first: its alpha was cleared before they were drawn (so it ends as their cover), the smoke is marched once to the world
 * and once to the nearest of them, and the smoke behind the glass goes under it (dimmed and tinted by it), the smoke in
 * front of the glass over it ({@code smoke_composite.fsh}). Improved transparency ("fabulous"): with NTM's Flywheel
 * (crankshaft, its translucent terrain OIT on, the default) there is no vanilla transparency chain, Flywheel lays the
 * translucent things over the main target in the main pass, their cover in its alpha and the nearest of them in its
 * depth, and the smoke goes as above. With the vanilla chain glass, water, items, particles, rain and the sky's clouds
 * are in targets of their own, laid over the main one after this: where the smoke may be they are laid first by the
 * smoke as the chain would and cleared in their targets ({@link #fold}), and the smoke goes as above too. A chain whose
 * post effect or shader a resource pack (or a mod's resources) replaced keeps the way before: the smoke behind the
 * glass into the main target, the smoke in front of it into the translucent one.</li>
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
    public static final RenderPipeline ALPHA_ZERO = alphaPipeline("smoke_alpha_zero", false, ColorTargetState.WRITE_ALPHA);
    public static final RenderPipeline ALPHA_ONE = alphaPipeline("smoke_alpha_one", true, ColorTargetState.WRITE_ALPHA);
    // a layer of the vanilla chain laid by the fold, emptied where it was laid (colour too: see fold)
    public static final RenderPipeline LAYER_CLEAR = alphaPipeline("smoke_layer_clear", false, ColorTargetState.WRITE_ALL);

    private static RenderPipeline alphaPipeline(String name, boolean one, int write) {
        RenderPipeline.Builder b = RenderPipeline.builder().withLocation(id("pipeline/" + name))
                .withVertexShader("core/screenquad")
                .withFragmentShader(id("core/smoke_alpha"))
                .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, write))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
        if (one) b.withShaderDefine("ALPHA_ONE");
        return b.build();
    }

    static final BindGroupLayout BLEND_LAYOUT = BindGroupLayout.builder()
            .withUniform("SmokeInfo", UniformType.UNIFORM_BUFFER)
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
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BLEND_LAYOUT)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();
    // with the transparency chain (improved transparency) glass and water are in a target of their own: the smoke behind
    // them goes into the main target, the smoke in front of them into theirs (smoke_composite.fsh, CHAIN_MAIN/CHAIN_GLASS)
    static final BindGroupLayout CHAIN_MAIN_LAYOUT = BindGroupLayout.builder()
            .withUniform("SmokeInfo", UniformType.UNIFORM_BUFFER)
            .withSampler("FrameOpaque")
            .withSampler("SmokeFront")
            .withSampler("SmokeFull")
            .withSampler("Depth0")
            .withSampler("Depth1")
            .build();
    static final BindGroupLayout CHAIN_GLASS_LAYOUT = BindGroupLayout.builder()
            .withUniform("SmokeInfo", UniformType.UNIFORM_BUFFER)
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
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(layout)
                .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .build();
    }

    // the vanilla transparency chain's layers laid over the main target by the smoke itself (see fold): the frame and
    // the cover (smoke_fold.fsh), and the nearest layer's depth (FOLD_DEPTH)
    static final String[] LAYERS = {"Translucent", "ItemEntity", "Particles", "Weather", "Clouds"};
    static final BindGroupLayout FOLD_LAYOUT = foldLayout(true);
    static final BindGroupLayout FOLD_DEPTH_LAYOUT = foldLayout(false);
    public static final RenderPipeline FOLD = RenderPipeline.builder()
            .withLocation(id("pipeline/smoke_fold"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(id("core/smoke_fold"))
            .withBindGroupLayout(FOLD_LAYOUT)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();
    public static final RenderPipeline FOLD_DEPTH = RenderPipeline.builder()
            .withLocation(id("pipeline/smoke_fold_depth"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(id("core/smoke_fold"))
            .withShaderDefine("FOLD_DEPTH")
            .withBindGroupLayout(FOLD_DEPTH_LAYOUT)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();

    private static BindGroupLayout foldLayout(boolean color) {
        BindGroupLayout.Builder b = BindGroupLayout.builder();
        if (color) b.withSampler("MainSampler");
        b.withSampler("MainDepthSampler");
        for (String l : LAYERS) b.withSampler(l + "Sampler").withSampler(l + "DepthSampler");
        return b.build();
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

    // the matrix, four vec4 and the inverse of the projection times the view, then per cloud (SmokeAtlas.SLOTS of them) four vec4: origin, box min, box max, colour;
    // then per group (as many at most) two: its box's min and max. Clouds[] in smoke_info.glsl must be SLOTS * 4 long,
    // Groups[] SLOTS * 2 (SmokeShaderLinkTest checks)
    static final int UBO_SIZE = 64 + 16 * 4 + 64 + SmokeAtlas.SLOTS * 4 * 16 + SmokeAtlas.SLOTS * 2 * 16;
    /** The noise is laid from a world point on a lattice this many blocks apart (the shader's PERIOD is 0.9 of it). */
    static final int ANCHOR = 1000;
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
    // only for the stands' old way (standOld): the depth with glass copied instead of read where it is
    private static final Copy POST_DEPTH = new Copy("Smoke depth with glass", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING);
    private static final Copy FRONT = new Copy("Smoke in front", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING);
    // the frame's alpha was cleared in afterOpaque and not yet put back
    private static boolean alphaCleared;
    // the part of the screen this frame's smoke can colour (x, y from the bottom left, width, height), or null: all of
    // it (see screenRect); the copies, the alpha and the composite are only done there
    private static int[] rect;

    /**
     * The stands only: the way before 2026-11-18 (the copies and the composite over the whole screen, the depth with
     * glass copied, the smoke in front of glass marched even where all of it is, a ray stopped at 1% let through), to
     * set the same frame against it.
     */
    public static int standOld;
    /** Parts of {@link #standOld}: the whole screen, the depth with glass copied, the front marched, a ray to 1%. */
    public static final int OLD_RECT = 1, OLD_DEPTH = 2, OLD_FRONT = 4, OLD_THROUGH = 8, OLD = 15;
    /** The stands only: the noise's time (seconds) and the partial tick held, so that two frames are the same; NaN: live. */
    public static float standSeconds = Float.NaN;
    /** A ray stops when this much light is let through (1% before 2026-11-18). */
    static final float THROUGH_MIN = 0.03F;

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
    private static int drawnLast, groupsLast;

    private SmokeRender() {
    }

    public static void register(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent.class, e -> {
            e.registerPipeline(MARCH);
            e.registerPipeline(MARCH_FRONT);
            e.registerPipeline(ALPHA_ZERO);
            e.registerPipeline(ALPHA_ONE);
            e.registerPipeline(LAYER_CLEAR);
            e.registerPipeline(COMPOSITE_GLASS);
            e.registerPipeline(COMPOSITE_CHAIN_MAIN);
            e.registerPipeline(COMPOSITE_CHAIN_GLASS);
            e.registerPipeline(FOLD);
            e.registerPipeline(FOLD_DEPTH);
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

    static float stepLength(int q) {
        return switch (q) {
            case 1 -> 0.6F;
            case 2 -> 0.45F;
            case 4 -> 0.22F;
            default -> 0.3F;
        };
    }

    static int maxSteps(int q) {
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

    /** Clouds and groups of them marched in the last frame (the stand). */
    public static int[] drawnLast() {
        return new int[] {drawnLast, groupsLast};
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

    /**
     * The vanilla transparency chain's layers as its post effect takes them (translucent, item entity, particles,
     * weather, clouds), or null: when the chain is not the game's own (a resource pack or a mod's resources gave it
     * another post effect or shader; a mod changing it in code is not seen) or a layer is not as the main target is.
     * Then the smoke is split between the main and the translucent target as before 2026-11-19 (COMPOSITE_CHAIN_*). A layer nothing has drawn into yet this frame is
     * null in it (the frame graph makes it first for the chain, empty: the sky's clouds with the clouds off).
     */
    private static RenderTarget[] foldLayers(RenderTarget main) {
        if (!vanillaChain()) return null;
        var lr = Minecraft.getInstance().levelRenderer;
        RenderTarget[] t = new RenderTarget[LAYERS.length];
        for (int i = 0; i < t.length; i++) {
            RenderTarget r = layer(lr, i);
            if (r == null) {
                // the chain's own target is there whenever the chain is
                if (i == 0) return null;
                continue;
            }
            if (r.width != main.width || r.height != main.height || r.getColorTexture() == null || r.getDepthTexture() == null
                    || r.getColorTexture().getFormat() != GpuFormat.RGBA8_UNORM) return null;
            t[i] = r;
        }
        return t;
    }

    // a layer's target, or null: none, or not made yet this frame (the frame graph's get() throws then)
    private static RenderTarget layer(net.minecraft.client.renderer.LevelRenderer lr, int i) {
        try {
            return switch (i) {
                case 0 -> lr.translucentTarget();
                case 1 -> lr.itemEntityTarget();
                case 2 -> lr.particlesTarget();
                case 3 -> lr.weatherTarget();
                default -> lr.cloudsTarget();
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    // a texture of one transparent texel: stands in for a layer not made yet (alpha 0, so the fold leaves it out)
    private static final Copy NO_LAYER = new Copy("Smoke no layer", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST);

    private static long chainChecked;
    private static boolean chainVanilla;

    // whether the transparency chain's post effect and its shader come from the game's own resources (checked once a second)
    static boolean vanillaChain() {
        long now = System.currentTimeMillis();
        if (now - chainChecked < 1000) return chainVanilla;
        chainChecked = now;
        boolean was = chainVanilla;
        var rm = Minecraft.getInstance().getResourceManager();
        chainVanilla = fromVanilla(rm, "post_effect/transparency.json") && fromVanilla(rm, "shaders/post/transparency.fsh");
        if (was != chainVanilla) GunFeel.LOGGER.debug("Smoke: the transparency chain is {}", chainVanilla ? "the game's own" : "changed by a pack or a mod");
        return chainVanilla;
    }

    private static boolean fromVanilla(net.minecraft.server.packs.resources.ResourceManager rm, String path) {
        try {
            var r = rm.getResource(Identifier.withDefaultNamespace(path));
            return r.isPresent() && "vanilla".equals(r.get().sourcePackId());
        } catch (RuntimeException e) {
            return false;
        }
    }

    // the depth of the nearest of the chain's layers (FOLD_DEPTH), the frame's size
    private static final Copy NEAR_DEPTH = new Copy("Smoke nearest layer depth", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING);

    /**
     * With the vanilla chain: its layers laid over the main target where the smoke may be (rect), as the chain would lay
     * them, the main target's alpha left as their cover, and cleared there in their own targets, colour and alpha (the
     * chain then has nothing to lay there; with only their alpha cleared the stand still found their colour added
     * once more, on GL, though the chain's shader leaves a layer of alpha 0 out: the cause was not found).
     * So the smoke goes over a frame with everything in it, as without the chain, and the particles, the rain and the
     * sky's clouds behind it are under it (the chain laid them over it). The frame before is
     * kept in OPAQUE_COLOR, the nearest layer's depth (over the whole screen) in NEAR_DEPTH.
     */
    private static void fold(RenderTarget main, RenderTarget[] layers) {
        var device = RenderSystem.getDevice();
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        GpuTexture color = main.getColorTexture();
        OPAQUE_COLOR.ensure(color.getFormat(), main.width, main.height);
        copyPart(color, OPAQUE_COLOR.tex, main.width, main.height);
        NEAR_DEPTH.ensure(GpuFormat.R32_FLOAT, main.width, main.height);
        if (NO_LAYER.tex == null) {
            NO_LAYER.ensure(GpuFormat.RGBA8_UNORM, 1, 1);
            device.createCommandEncoder().clearColorTexture(NO_LAYER.tex, new Vector4f(0F, 0F, 0F, 0F));
        }
        // the depth first: it reads the layers before they are cleared; then the main target and the clearing one after
        // the other, so that little can fail between them (a layer left would be laid twice)
        try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Smoke nearest layer", NEAR_DEPTH.view, Optional.empty())) {
            pass.setPipeline(FOLD_DEPTH);
            RenderSystem.bindDefaultUniforms(pass);
            bindLayers(pass, main, layers, nearest);
            pass.draw(3, 1, 0, 0);
        }
        try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Smoke chain layers", main.getColorTextureView(), Optional.empty())) {
            scissor(pass);
            pass.setPipeline(FOLD);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("MainSampler", OPAQUE_COLOR.view, nearest);
            bindLayers(pass, main, layers, nearest);
            pass.draw(3, 1, 0, 0);
        }
        // each layer cleared even if one fails: one left is laid twice, by this and by the chain
        RuntimeException failed = null;
        for (RenderTarget l : layers) {
            if (l == null) continue;
            try {
                alphaPass(l, LAYER_CLEAR);
            } catch (RuntimeException ex) {
                failed = ex;
            }
        }
        if (failed != null) throw failed;
    }

    private static void bindLayers(RenderPass pass, RenderTarget main, RenderTarget[] layers, com.mojang.blaze3d.textures.GpuSampler nearest) {
        pass.bindTexture("MainDepthSampler", main.getDepthTextureView(), nearest);
        for (int i = 0; i < layers.length; i++) {
            RenderTarget l = layers[i];
            pass.bindTexture(LAYERS[i] + "Sampler", l != null ? l.getColorTextureView() : NO_LAYER.view, nearest);
            pass.bindTexture(LAYERS[i] + "DepthSampler", l != null ? l.getDepthTextureView() : NO_LAYER.view, nearest);
        }
    }

    // how glass was told from the rest in the last frame that marched: "none" (nothing marched), "alpha" (the frame's
    // alpha), "fold" (the vanilla chain's layers laid first, see fold) or "chain" (the transparency chain's target, a
    // chain changed by a pack or a mod); for the stand
    private static String glassMode = "none";

    public static String glassMode() {
        return glassMode;
    }

    private static void alphaPass(RenderTarget main, RenderPipeline pipeline) {
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Smoke alpha", main.getColorTextureView(), Optional.empty())) {
            scissor(pass);
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.draw(3, 1, 0, 0);
        }
    }

    /**
     * AfterOpaqueFeatures: the world's depth before glass and water. Only when a drawn cloud is in the view: with none
     * the depth is not copied and nothing is marched or laid over the frame.
     */
    @Hook(HookPoint.SMOKE_DRAW)
    public static void afterOpaque(RenderLevelStageEvent.AfterOpaqueFeatures e) {
        copied = false;
        rect = null;
        VISIBLE.clear();
        if (!wanted() || SmokeAtlas.drawn().isEmpty()) return;
        var camera = e.getLevelRenderState().cameraRenderState;
        // the frustum is the camera's own unless the F4 frustum capture froze it
        boolean cull = camera.initialized && !camera.isFrustumCaptured;
        for (SmokeAtlas.Slot s : SmokeAtlas.drawn()) if (!cull || inView(s.cloud(), camera.cullFrustum)) VISIBLE.add(s);
        if (VISIBLE.isEmpty()) return;
        long t0 = System.nanoTime();
        try {
            timerBegin(RenderSystem.getDevice());
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            GpuTexture src = main.getDepthTexture();
            if (src == null) return;
            projView = camera.initialized ? projView(camera, e.getModelViewMatrix()) : null;
            rect = (standOld & OLD_RECT) != 0 || projView == null ? null : screenRect(VISIBLE, projView, camera.pos, main.width, main.height, divisor(quality()));
            if (depth == null || depthW != main.width || depthH != main.height || depthFormat != src.getFormat()) {
                closeDepth();
                depthW = main.width;
                depthH = main.height;
                depthFormat = src.getFormat();
                depth = RenderSystem.getDevice().createTexture(() -> "Smoke scene depth", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                        depthFormat, depthW, depthH, 1, 1);
                depthView = RenderSystem.getDevice().createTextureView(depth);
            }
            copyPart(src, depth, depthW, depthH);
            copied = true;
            if (chainTarget() == null) {
                // the frame before glass and water, and its alpha at 0: what they draw then leaves their cover in it
                GpuTexture color = main.getColorTexture();
                OPAQUE_COLOR.ensure(color.getFormat(), main.width, main.height);
                copyPart(color, OPAQUE_COLOR.tex, main.width, main.height);
                alphaPass(main, ALPHA_ZERO);
                alphaCleared = true;
            }
            mark(1);
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
     * a cloud is covered by it too (with the vanilla transparency chain, "fabulous", the sky's clouds, the rain and the
     * rest of its layers are laid first where the smoke may be, see fold; a chain a pack changed lays them after this,
     * over the smoke).
     */
    @Hook(HookPoint.SMOKE_DRAW)
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
            mark(7);
            timerSlot = -1;
        }
    }

    private static void marchAndLay(RenderLevelStageEvent.AfterWeather e) {
        drawnLast = groupsLast = 0;
        glassMode = "none";
        if (!copied || !wanted()) return;
        copied = false;
        List<SmokeAtlas.Slot> slots = VISIBLE;
        if (slots.isEmpty() || SmokeAtlas.view() == null) return;
        long t0 = System.nanoTime();
        try {
            int q = quality();
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            int div = divisor(q);
            // with the camera in a cloud's box the smoke fills the screen and is soft all over: marched a step coarser
            // (high: at a third of the screen, ultra: a half) and laid over the frame upscaled by depth
            boolean lowInside = div <= 2 && cameraInBox(slots, e.getLevelRenderState().cameraRenderState.pos);
            if (lowInside) div++;
            int w = Math.max(1, main.width / div), h = Math.max(1, main.height / div);
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
            // the vanilla chain: its layers laid first (fold), then as without it
            RenderTarget[] layers = chain != null ? foldLayers(main) : null;
            glassMode = layers != null ? "fold" : chain != null ? "chain" : glass ? "alpha" : "none";
            mark(2);
            postDepthView = null;
            if (layers != null) {
                fold(main, layers);
                chain = null;
            }
            if (glass) {
                RenderTarget with = chain != null ? chain : main;
                // the depth with glass is read where it is: nothing draws into it while the smoke is marched and laid
                if (layers != null) postDepthView = NEAR_DEPTH.view;
                else if ((standOld & OLD_DEPTH) != 0) {
                    POST_DEPTH.ensure(depthFormat, depthW, depthH);
                    RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(with.getDepthTexture(), POST_DEPTH.tex, 0, 0, 0, 0, 0, depthW, depthH);
                    postDepthView = POST_DEPTH.view;
                } else postDepthView = with.getDepthTextureView();
                POST_COLOR.ensure(with.getColorTexture().getFormat(), with.width, with.height);
                copyPart(with.getColorTexture(), POST_COLOR.tex, with.width, with.height);
                if (chain != null) {
                    OPAQUE_COLOR.ensure(main.getColorTexture().getFormat(), main.width, main.height);
                    copyPart(main.getColorTexture(), OPAQUE_COLOR.tex, main.width, main.height);
                }
                FRONT.ensure(GpuFormat.RGBA8_UNORM, w, h);
            }
            if (ubo == null) ubo = new MappableRingBuffer(() -> "Smoke info", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UBO_SIZE);
            Vec3 cam = e.getLevelRenderState().cameraRenderState.pos;
            // the clouds whose boxes meet go in one group, marched as one smoke; the groups far to near, each over the
            // ones behind it
            double[][] boxes = new double[slots.size()][];
            for (int i = 0; i < boxes.length; i++) boxes[i] = worldBox(slots.get(i).cloud());
            int[][] groups = groups(boxes, cam.x, cam.y, cam.z);
            List<SmokeAtlas.Slot> order = new ArrayList<>(slots.size());
            for (int[] g : groups) for (int i : g) order.add(slots.get(i));
            boolean held = !Float.isNaN(standSeconds);
            float partial = held ? 1F : Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
            float seconds = held ? standSeconds : (float) ((System.nanoTime() / 1_000_000L) % 3_600_000L) / 1000F;
            boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
            try (var view = ubo.currentBuffer().map(false, true)) {
                info(view.data(), e.getModelViewMatrix(), depthW, depthH, w, h, zeroToOne, seconds, q, partial, groups, lowInside, projView, order, boxes, cam);
            }
            var device = RenderSystem.getDevice();
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            mark(3);
            long tm = System.nanoTime();
            if (glass) {
                // the smoke from the eye to the nearest translucent surface, where there is one
                try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Smoke march in front", FRONT.view, Optional.of(new Vector4f(0F, 0F, 0F, 0F)),
                        null, OptionalDouble.empty())) {
                    pass.setPipeline(MARCH_FRONT);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("SmokeInfo", ubo.currentBuffer());
                    pass.bindTexture("SmokeAtlas", SmokeAtlas.view(), sampler);
                    pass.bindTexture("SceneDepth", postDepthView, nearest);
                    pass.bindTexture("OpaqueDepth", depthView, nearest);
                    pass.draw(36 * groups.length, 1, 0, 0);
                }
            }
            mark(4);
            try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Smoke march", targetView, Optional.of(new Vector4f(0F, 0F, 0F, 0F)),
                    null, OptionalDouble.empty())) {
                pass.setPipeline(MARCH);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("SmokeInfo", ubo.currentBuffer());
                pass.bindTexture("SmokeAtlas", SmokeAtlas.view(), sampler);
                pass.bindTexture("SceneDepth", depthView, nearest);
                pass.draw(36 * groups.length, 1, 0, 0);
            }
            mark(5);
            SmokeStats.add(SmokeStats.MARCH, System.nanoTime() - tm);
            long tc = System.nanoTime();
            if (chain != null) {
                // the main target: the world with the smoke behind the glass; the chain's: the smoke in front over the glass
                try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Smoke composite behind glass", main.getColorTextureView(), Optional.empty())) {
                    scissor(pass);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setPipeline(COMPOSITE_CHAIN_MAIN);
                    pass.bindTexture("FrameOpaque", OPAQUE_COLOR.view, nearest);
                    bindSmoke(pass, sampler, nearest);
                    pass.draw(3, 1, 0, 0);
                }
                try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Smoke composite in front of glass", chain.getColorTextureView(), Optional.empty())) {
                    scissor(pass);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setPipeline(COMPOSITE_CHAIN_GLASS);
                    pass.bindTexture("FramePost", POST_COLOR.view, nearest);
                    bindSmoke(pass, sampler, nearest);
                    pass.draw(3, 1, 0, 0);
                }
            } else try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Smoke composite", main.getColorTextureView(), Optional.empty())) {
                scissor(pass);
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
            mark(6);
            ubo.rotate();
            drawnLast = order.size();
            groupsLast = groups.length;
            // a pass that failed once and has drawn well for a minute since is forgiven
            if (failures > 0 && failures < RETRIES && System.nanoTime() - failedAt > HEALED_NANOS) failures = 0;
        } catch (RuntimeException ex) {
            fail("raymarch", ex);
        }
    }

    // the two marches, the depths and the smoke info the glass composites read
    private static void bindSmoke(RenderPass pass, com.mojang.blaze3d.textures.GpuSampler linear, com.mojang.blaze3d.textures.GpuSampler nearest) {
        pass.setUniform("SmokeInfo", ubo.currentBuffer());
        pass.bindTexture("SmokeFront", FRONT.view, linear);
        pass.bindTexture("SmokeFull", targetView, linear);
        pass.bindTexture("Depth0", depthView, nearest);
        pass.bindTexture("Depth1", postDepthView, nearest);
    }

    // whether the camera is in one of the drawn clouds' boxes
    private static boolean cameraInBox(List<SmokeAtlas.Slot> slots, Vec3 cam) {
        for (SmokeAtlas.Slot s : slots) {
            double[] b = worldBox(s.cloud());
            if (cam.x >= b[0] && cam.x <= b[3] && cam.y >= b[1] && cam.y <= b[4] && cam.z >= b[2] && cam.z <= b[5]) return true;
        }
        return false;
    }

    // this frame's depth with glass: the target's own, or the copy (standOld)
    private static GpuTextureView postDepthView;

    // a texture into one of the same size: from the bottom left corner up to the far corner of rect (all of it without one)
    private static void copyPart(GpuTexture from, GpuTexture to, int width, int height) {
        int[] c = copyCorner(rect, width, height);
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(from, to, 0, 0, 0, 0, 0, c[0], c[1]);
    }

    /**
     * How far a copy of rect reaches from the bottom left corner. Always from 0, 0: the OpenGL backend (GlCommandEncoder)
     * hands copyTextureToTexture's width and height to glBlitNamedFramebuffer as the far corner, not the size, so a copy
     * from x, y took the part between (x, y) and (width, height) there and left rect itself as an earlier frame had it
     * (Vulkan copies the size). From 0 both read the same.
     */
    static int[] copyCorner(int[] rect, int width, int height) {
        return rect == null ? new int[] {width, height} : new int[] {Math.min(width, rect[0] + rect[2]), Math.min(height, rect[1] + rect[3])};
    }

    private static void scissor(RenderPass pass) {
        if (rect != null) pass.enableScissor(rect[0], rect[1], rect[2], rect[3]);
    }

    // this frame's projection times the view, as the level is drawn (projView), or null
    private static Matrix4f projView;

    /**
     * The projection the level is drawn with times the view: the camera's projection with the hurt tilt and the view
     * bobbing on it as GameRenderer puts them. Null when nausea or a portal warps the view: then the part of the screen
     * is all of it and the smoke in front of glass is marched everywhere, as before.
     */
    static Matrix4f projView(net.minecraft.client.renderer.state.level.CameraRenderState camera, org.joml.Matrix4fc view) {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player != null && (player.portalEffectIntensity > 0F || player.oPortalEffectIntensity > 0F
                || player.hasEffect(net.minecraft.world.effect.MobEffects.NAUSEA))) return null;
        com.mojang.blaze3d.vertex.PoseStack bob = new com.mojang.blaze3d.vertex.PoseStack();
        var renderer = (acup.gunfeel.mixin.mc.GameRendererAccess) mc.gameRenderer;
        renderer.gunfeel$bobHurt(camera, bob);
        if (mc.gameRenderer.gameRenderState().optionsRenderState.bobView) renderer.gunfeel$bobView(camera, bob);
        return new Matrix4f(camera.projectionMatrix).mul(bob.last().pose()).mul(view);
    }

    /**
     * The part of the screen the drawn clouds' boxes can colour: their corners through {@code pv}, widened by two of the
     * march's pixels and one more (its texels are filtered over their neighbours). x, y from the bottom left, width,
     * height in the screen's pixels; null: all of it, when a corner is at or behind the eye or the part is most of the
     * screen anyway.
     */
    static int[] screenRect(List<SmokeAtlas.Slot> slots, Matrix4f pv, Vec3 cam, int width, int height, int div) {
        float lx = Float.MAX_VALUE, ly = Float.MAX_VALUE, hx = -Float.MAX_VALUE, hy = -Float.MAX_VALUE;
        Vector4f v = new Vector4f();
        for (SmokeAtlas.Slot s : slots) {
            double[] b = worldBox(s.cloud());
            for (int k = 0; k < 8; k++) {
                v.set((float) ((k & 1) == 0 ? b[0] - cam.x : b[3] - cam.x), (float) ((k & 2) == 0 ? b[1] - cam.y : b[4] - cam.y),
                        (float) ((k & 4) == 0 ? b[2] - cam.z : b[5] - cam.z), 1F);
                pv.transform(v);
                if (!(v.w > 0.05F)) return null;
                float x = v.x / v.w, y = v.y / v.w;
                lx = Math.min(lx, x);
                hx = Math.max(hx, x);
                ly = Math.min(ly, y);
                hy = Math.max(hy, y);
            }
        }
        int pad = 2 * div + 2;
        int x0 = clampPx((lx * 0.5F + 0.5F) * width - pad, width), x1 = clampPx((hx * 0.5F + 0.5F) * width + pad + 1, width);
        int y0 = clampPx((ly * 0.5F + 0.5F) * height - pad, height), y1 = clampPx((hy * 0.5F + 0.5F) * height + pad + 1, height);
        if (x1 <= x0 || y1 <= y0 || (long) (x1 - x0) * (y1 - y0) > (long) width * height * 9 / 10) return null;
        return new int[] {x0, y0, x1 - x0, y1 - y0};
    }

    private static int clampPx(float v, int size) {
        return (int) Math.max(0F, Math.min(size, Math.floor(v)));
    }

    // the card's own time on each pass: MARKS timestamps a frame in a ring of 8 frames, a frame's read back the next
    // time round (by then the card is long done with them). 0 before the copies before glass and water, 1 after them,
    // 2 before the copies after them, 3 after those, 4 after the march in front, 5 after the march, 6 after the
    // composite, 7 after the alpha put back
    private static final int TIMER_FRAMES = 8, MARKS = 8;
    private static com.mojang.blaze3d.systems.GpuQueryPool timer;
    // per frame of the ring: the marks written
    private static final int[] marked = new int[TIMER_FRAMES];
    private static int timerFrame, timerSlot = -1;
    private static boolean timerOff;

    // a frame's first mark; the slot's last frame read back first
    private static void timerBegin(com.mojang.blaze3d.systems.GpuDevice device) {
        timerSlot = -1;
        if (timerOff || !SmokeStats.MEASURING) return;
        try {
            if (timer == null) timer = device.createTimestampQueryPool(TIMER_FRAMES * MARKS);
            int f = timerFrame++ % TIMER_FRAMES;
            if (marked[f] == (1 << MARKS) - 1) {
                var v = timer.getValues(f * MARKS, MARKS);
                boolean all = true;
                for (var x : v) all &= x.isPresent();
                if (all) {
                    // in the card's ticks: nanoseconds on OpenGL, timestampPeriod of them on Vulkan (as TimerQuery takes them)
                    double period = device.getDeviceInfo().timestampPeriod();
                    long[] at = new long[MARKS];
                    for (int k = 0; k < MARKS; k++) at[k] = v[k].getAsLong();
                    SmokeStats.add(SmokeStats.GPU_MARCH, (long) ((at[6] - at[3]) * period));
                    long[] part = {at[1] - at[0], at[3] - at[2], at[4] - at[3], at[5] - at[4], at[6] - at[5], at[7] - at[6]};
                    for (int k = 0; k < part.length; k++) SmokeStats.cardPart[k] += (long) (part[k] * period);
                    SmokeStats.cardPartFrames++;
                }
            }
            marked[f] = 0;
            timerSlot = f;
            mark(0);
        } catch (RuntimeException ex) {
            GunFeel.LOGGER.debug("Smoke: no timestamps on this card ({}), its own time is not measured", ex.toString());
            timerOff = true;
            timerSlot = -1;
        }
    }

    private static void mark(int k) {
        if (timerSlot < 0) return;
        try {
            RenderSystem.getDevice().createCommandEncoder().writeTimestamp(timer, timerSlot * MARKS + k);
            marked[timerSlot] |= 1 << k;
        } catch (RuntimeException ex) {
            GunFeel.LOGGER.debug("Smoke: no timestamps on this card ({}), its own time is not measured", ex.toString());
            timerOff = true;
            timerSlot = -1;
        }
    }

    // SmokeInfo's matrices, kept: the JIT's C2 of JDK 25 (25.0.1, 25.0.4.1) crashed (SIGSEGV in
    // LoadNode::can_split_through_phi_base) compiling a JOML Matrix4f.get(ByteBuffer) of either of two new matrices
    // (projView != null ? new Matrix4f(projView).invert() : new Matrix4f()); one matrix set either way is no such phi
    // (notes/ports-base/bugs.md). The render thread's only.
    private static final Matrix4f VIEW = new Matrix4f(), INVERSE = new Matrix4f();

    /** SmokeInfo's bytes for a frame: the march's numbers, the clouds in their groups' order, the groups' boxes. */
    static void info(java.nio.ByteBuffer data, org.joml.Matrix4fc modelView, int depthW, int depthH, int w, int h, boolean zeroToOne, float seconds, int q,
            float partial, int[][] groups, boolean lowInside, Matrix4f projView, List<SmokeAtlas.Slot> order, double[][] boxes, Vec3 cam) {
        Std140Builder b = Std140Builder.intoBuffer(data);
        b.putMat4f(VIEW.set(modelView));
        b.putVec4((float) depthW / w, zeroToOne ? 1F : 0F, seconds, maxSteps(q));
        b.putVec4(1F / SmokeAtlas.WIDTH, 1F / SmokeAtlas.HEIGHT, partial, stepLength(q));
        b.putVec4(groups.length, lowInside ? 1F : 0F, (float) depthH / h, (standOld & OLD_FRONT) != 0 || projView == null ? 0F : 1F);
        b.putVec4((float) (Math.floor(cam.x / ANCHOR) * ANCHOR - cam.x), (float) (Math.floor(cam.y / ANCHOR) * ANCHOR - cam.y),
                (float) (Math.floor(cam.z / ANCHOR) * ANCHOR - cam.z), (standOld & OLD_THROUGH) != 0 ? 0.01F : THROUGH_MIN);
        b.putMat4f(projView != null ? INVERSE.set(projView).invert() : INVERSE.identity());
        for (int i = 0; i < SmokeAtlas.SLOTS; i++) {
            if (i >= order.size()) {
                for (int k = 0; k < 4; k++) b.putVec4(0F, 0F, 0F, 0F);
                continue;
            }
            SmokeAtlas.Slot s = order.get(i);
            SmokeClouds.Cloud c = s.cloud();
            int[] cells = cells(c);
            b.putVec4((float) (c.baseX() - cam.x), (float) (c.baseY() - cam.y), (float) (c.baseZ() - cam.z), s.index());
            b.putVec4(cells[0], cells[1], cells[2], c.kind().look());
            b.putVec4(cells[3], cells[4], cells[5], 0F);
            int rgb = c.kind().rgb();
            b.putVec4((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F, 1F);
        }
        for (int i = 0, first = 0; i < SmokeAtlas.SLOTS; i++) {
            if (i >= groups.length) {
                b.putVec4(0F, 0F, 0F, 0F);
                b.putVec4(0F, 0F, 0F, 0F);
                continue;
            }
            double[] u = union(boxes, groups[i]);
            b.putVec4((float) (u[0] - cam.x), (float) (u[1] - cam.y), (float) (u[2] - cam.z), first);
            b.putVec4((float) (u[3] - cam.x), (float) (u[4] - cam.y), (float) (u[5] - cam.z), groups[i].length);
            first += groups[i].length;
        }
    }

    // the cells the shader marches a cloud through: one around the occupied box, which the edge cells fade out into and
    // the cloud grows into; min x, y, z and max x, y, z in the grid's cells
    static int[] cells(SmokeClouds.Cloud c) {
        int[] box = c.grid.box();
        return new int[] {Math.max(-1, box[0] - 1), Math.max(-1, box[1] - 1), Math.max(-1, box[2] - 1),
                Math.min(SmokeGrid.SX + 1, box[3] + 2), Math.min(SmokeGrid.SY + 1, box[4] + 2), Math.min(SmokeGrid.SZ + 1, box[5] + 2)};
    }

    // the same in the world
    static double[] worldBox(SmokeClouds.Cloud c) {
        int[] k = cells(c);
        return new double[] {c.baseX() + k[0], c.baseY() + k[1], c.baseZ() + k[2], c.baseX() + k[3], c.baseY() + k[4], c.baseZ() + k[5]};
    }

    /**
     * The boxes (world min x, y, z, max x, y, z) in groups: two whose boxes meet or come within {@link #MEET} of each
     * other are in one, and one that meets either of them too. The groups far to near (by their box's middle), each
     * one's boxes in the order given. A group is marched as one smoke, so where clouds overlap their smoke is one; the
     * groups lie apart and far to near is their right order.
     */
    static int[][] groups(double[][] boxes, double cx, double cy, double cz) {
        int n = boxes.length;
        int[] root = new int[n];
        for (int i = 0; i < n; i++) root[i] = i;
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) if (meet(boxes[i], boxes[j])) {
            int a = find(root, i), b = find(root, j);
            if (a != b) root[Math.max(a, b)] = Math.min(a, b);
        }
        List<List<Integer>> by = new ArrayList<>();
        int[] slot = new int[n];
        java.util.Arrays.fill(slot, -1);
        for (int i = 0; i < n; i++) {
            int r = find(root, i);
            if (slot[r] < 0) {
                slot[r] = by.size();
                by.add(new ArrayList<>());
            }
            by.get(slot[r]).add(i);
        }
        int[][] out = new int[by.size()][];
        for (int g = 0; g < out.length; g++) out[g] = by.get(g).stream().mapToInt(Integer::intValue).toArray();
        double[] far = new double[out.length];
        for (int g = 0; g < out.length; g++) {
            double[] u = union(boxes, out[g]);
            double x = (u[0] + u[3]) * 0.5 - cx, y = (u[1] + u[4]) * 0.5 - cy, z = (u[2] + u[5]) * 0.5 - cz;
            far[g] = x * x + y * y + z * z;
        }
        Integer[] idx = new Integer[out.length];
        for (int g = 0; g < idx.length; g++) idx[g] = g;
        java.util.Arrays.sort(idx, Comparator.comparingDouble((Integer g) -> -far[g]));
        int[][] sorted = new int[out.length][];
        for (int g = 0; g < idx.length; g++) sorted[g] = out[idx[g]];
        return sorted;
    }

    /** Boxes this near each other are one group: the far to near order of two boxes side by side can be wrong. */
    static final double MEET = 1.0;

    private static boolean meet(double[] a, double[] b) {
        for (int k = 0; k < 3; k++) if (a[k] > b[k + 3] + MEET || b[k] > a[k + 3] + MEET) return false;
        return true;
    }

    private static int find(int[] root, int i) {
        while (root[i] != i) i = root[i] = root[root[i]];
        return i;
    }

    static double[] union(double[][] boxes, int[] which) {
        double[] u = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i : which) for (int k = 0; k < 3; k++) {
            u[k] = Math.min(u[k], boxes[i][k]);
            u[k + 3] = Math.max(u[k + 3], boxes[i][k + 3]);
        }
        return u;
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
        NEAR_DEPTH.close();
        NO_LAYER.close();
        FRONT.close();
        if (ubo != null) ubo.close();
        ubo = null;
        if (timer != null) timer.close();
        timer = null;
        java.util.Arrays.fill(marked, 0);
        timerFrame = 0;
        timerSlot = -1;
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
    @Hook(HookPoint.SMOKE_DRAW)
    public static void frame() {
        SmokeClouds all = SmokeClient.current();
        SmokeStats.frame(all == null ? 0 : all.clouds().size(), Math.max(drawnLast, SmokeSlices.drawnLast()), SmokeFog.atCamera());
    }
}
